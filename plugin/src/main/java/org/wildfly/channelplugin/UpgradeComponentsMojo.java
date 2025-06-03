package org.wildfly.channelplugin;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.wildfly.channel.NoStreamFoundException;
import org.wildfly.channel.Repository;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.VersionResult;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channelplugin.utils.PMEUtils;
import org.wildfly.channelplugin.utils.VersionComparator;
import org.wildfly.channeltools.util.VersionUtils;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.wildfly.channeltools.util.ConversionUtils.toArtifactRef;
import static org.wildfly.channeltools.util.ConversionUtils.toProjectRefs;

/**
 * This tasks overrides dependencies versions according to provided channel file.
 * <p>
 * One of following properties needs to be set:
 * <li>`channelFile` to use a channel file located on a local file system,</li>
 * <li>`channelGAV` to lookup an artifact containing the channel file.</li>
 */
@Mojo(name = "upgrade", requiresDirectInvocation = true)
public class UpgradeComponentsMojo extends AbstractChannelMojo {

    private final static Comparator<String> VERSION_COMPARATOR = new VersionComparator();

    /**
     * Comma separated list of dependency G:As that should not be upgraded.
     */
    @Parameter(property = "ignoreStreams")
    List<String> ignoreStreams;

    /**
     * Takes precedence over the ignoreStreams settings. One can use it to set "-DignoreStreams=org.wildfly.core:*" and
     * "-DdontIgnoreStreams=org.wildfly.core:wildfly-core-parent" which would together ignore all org.wildfly.core:*
     * streams except for org.wildfly.core:wildfly-core-parent.
     */
    @Parameter(property = "dontIgnoreStreams")
    List<String> dontIgnoreStreams;

    /**
     * Comma separated list of module G:As that should not be processed.
     */
    @Parameter(property = "ignoreModules")
    List<String> ignoreModules;

    /**
     * Comma separated list of property names. Project properties that match one of these names will not get
     * overridden.
     */
    @Parameter(property = "ignoreProperties")
    List<String> ignoreProperties;

    /**
     * Comma separated list of property names prefixes. Project properties that match one of these prefixes will not get
     * overridden.
     */
    @Parameter(property = "ignorePropertiesPrefixedWith")
    List<String> ignorePropertiesPrefixedWith;

    /**
     * Comma separated list of propertyName=propertyValue. Given properties will be overridden to given values.
     * <p>
     * This setting takes precedence over channel streams.
     */
    @Parameter(property = "overrideProperties")
    List<String> overrideProperties;

    /**
     * Comma separated list of groupId:artifactId:version triplets. All existing dependencies in the project with given
     * groupId and artifactId will be set to given version.
     * <p>
     * This setting takes precedence over channel streams.
     */
    @Parameter(property = "overrideDependencies")
    List<String> overrideDependencies;

    /**
     * If true, upgraded versions would be inlined in the dependency version element, possibly replacing a property
     * reference if it was originally used. If false, the property would be updated instead (less robust option).
     */
    @Parameter(property = "inlineUpgradedVersions", defaultValue = "false")
    boolean inlineUpgradedVersions;

    /**
     * If true, transitive dependencies of the project that are also declared in the channel will be injected into root
     * POM's dependencyManagement section.
     */
    @Parameter(property = "injectTransitiveDependencies", defaultValue = "true")
    boolean injectTransitiveDependencies;

    @Parameter(property = "ignoreTestDependencies", defaultValue = "true")
    boolean ignoreTestDependencies;

    @Parameter(property = "ignoreScopes", defaultValue = "test")
    Set<String> ignoreScopes = new HashSet<>();

    /**
     * When a dependency is defined with version string referencing a property, and that property is defined in a parent
     * pom outside the project, the property would be injected into a pom where the dependency is defined, if this
     * parameter is set to true (default).
     */
    @Parameter(property = "injectExternalProperties", defaultValue = "true")
    boolean injectExternalProperties;

    /**
     * Should the remote maven repositories (specified via -DremoteRepositories or in input channels) be injected into
     * the parent project POM, to ensure that project is buildable?
     */
    @Parameter(property = "injectRepositories", defaultValue = "true")
    boolean injectRepositories;

    /**
     * If set to true, the plugin will not downgrade versions.
     */
    @Parameter(property = "doNotDowngrade", defaultValue = "false")
    boolean doNotDowngrade;

    @Inject
    DependencyGraphBuilder dependencyGraphBuilder;

    @Inject
    ManipulationSession manipulationSession;

    private final Set<ProjectRef> ignoredStreams = new HashSet<>();
    private final Set<ProjectRef> unignoredStreams = new HashSet<>();
    private Set<ProjectVersionRef> projectGavs;
    private final Map<Pair<String, String>, PomManipulator> manipulators = new HashMap<>();
    private PomManipulator rootManipulator;
    private final Map<Pair<Project, String>, String> lockedProperties = new HashMap<>();
    private final Set<ProjectRef> declaredDependencies = new HashSet<>();
    private final Set<String> overriddenProperties = new HashSet<>(); // Names of properties that were explicitly overridden via `overrideProperties` parameter.
    private final Set<Dependency> overriddenDependencies = new HashSet<>(); // Collected dependency instances that were explicitly overridden via `overrideDependencies` parameter.

    /**
     * This includes pre-processing of input parameters.
     */
    private void init() throws MojoExecutionException {
        initChannelSession();

        ignoreStreams.forEach(ga -> ignoredStreams.add(SimpleProjectRef.parse(ga)));
        dontIgnoreStreams.forEach(ga -> unignoredStreams.add(SimpleProjectRef.parse(ga)));
        if (ignoreTestDependencies) {
            ignoreScopes.add("test");
        }
    }

    /**
     * This updates the configuration according to a config file living in the project root. Should be called before the
     * {@link #init()} method.
     */
    private void reconfigure() throws MojoExecutionException {
        File configFile = new File(mavenSession.getExecutionRootDirectory(), MojoConfigurator.DEFAULT_CONFIGURATION_FILE);
        try {
            MojoConfigurator configurator = new MojoConfigurator(configFile);
            configurator.configureProperties(this);
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to read plugin configuration from " + MojoConfigurator.DEFAULT_CONFIGURATION_FILE, e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

        reconfigure();
        init();

        try {
            List<Project> pmeProjects = PMEUtils.parsePmeProjects(pomIO, mavenProject);

            // collect GAVs of in-project modules, these are not going to be upgraded
            projectGavs = pmeProjects.stream()
                    .map(p -> new SimpleProjectVersionRef(p.getGroupId(), p.getArtifactId(), p.getVersion()))
                    .collect(Collectors.toSet());

            // process project modules
            for (Project project: pmeProjects) {
                if (isIgnoredModule(project.getGroupId(), project.getArtifactId())) {
                    getLog().info(String.format("Skipping module %s:%s", project.getGroupId(), project.getArtifactId()));
                    continue;
                }

                getLog().info(String.format("Processing module %s:%s", project.getGroupId(), project.getArtifactId()));

                // create manipulator for given module
                PomManipulator manipulator = new PomManipulator(project);
                manipulators.put(Pair.of(project.getGroupId(), project.getArtifactId()), manipulator);

                processModule(project, manipulator);
            }

            Project rootProject = PMEUtils.findRootProject(pmeProjects);
            rootManipulator = manipulators.get(Pair.of(rootProject.getGroupId(), rootProject.getArtifactId()));

            if (injectTransitiveDependencies) {
                injectTransitiveDependencies();
            }

            // if channel was given as an input, insert channel repositories into the parent pom
            if (injectRepositories) {
                Map<String, String> repositoriesToInject = channels.stream().flatMap(c -> c.getRepositories().stream()).distinct()
                        .collect(Collectors.toMap(Repository::getId, Repository::getUrl));
                InjectRepositoriesMojo.insertRepositories(rootProject, rootManipulator, repositoriesToInject);
            }

            // override modified poms
            for (PomManipulator manipulator: manipulators.values()) {
                manipulator.writePom();
            }

        } catch (ManipulationException | XMLStreamException e) {
            throw new MojoExecutionException("Project parsing failed", e);
        } catch (DependencyGraphBuilderException e) {
            throw new MojoExecutionException("Dependency collector error", e);
        }
    }

    /**
     * Processes single project module:
     * <li>collects all declared dependencies,</li>
     * <li>performs hard overrides of properties and dependency versions in the module,</li>
     * <li>upgrades dependencies according to channel definition.</li>
     */
    private void processModule(Project pmeProject, PomManipulator manipulator)
            throws ManipulationException, XMLStreamException {
        Map<ArtifactRef, Dependency> resolvedProjectDependencies = collectResolvedProjectDependencies(pmeProject);
        resolvedProjectDependencies.keySet().forEach(a -> declaredDependencies.add(a.asProjectRef()));

        performHardPropertyOverrides(manipulator);
        performHardDependencyOverrides(resolvedProjectDependencies, manipulator);
        processDependencies(manipulator, pmeProject, resolvedProjectDependencies);
    }

    private void processDependencies(PomManipulator manipulator, Project pmeProject,
                                     Map<ArtifactRef, Dependency> resolvedProjectDependencies)
            throws XMLStreamException {

        for (Map.Entry<ArtifactRef, Dependency> entry: resolvedProjectDependencies.entrySet()) {
            Dependency dependency = entry.getValue();
            ArtifactRef originalArtifact = entry.getKey();
            String originalVersion = originalArtifact.getVersionString().trim();
            Optional<String> channelVersionOpt = resolveChannelVersion(originalArtifact);
            if (channelVersionOpt.isEmpty()) {
                // Channel doesn't resolve this, nothing to do
                continue;
            }
            String channelVersion = channelVersionOpt.get();

            Objects.requireNonNull(originalArtifact);
            Objects.requireNonNull(dependency);

            if (isIgnoredDependency(originalArtifact, dependency)) {
                continue;
            }

            if (VersionUtils.isProperty(dependency.getVersion()) && !inlineUpgradedVersions) {
                // Dependency version is set from a property
                processDependencyWithVersionProperty(pmeProject, manipulator, dependency, originalVersion, channelVersion);
            } else {
                // Dependency version is to be written directly into the version element
                if (shouldUpgrade(originalVersion, channelVersion)) {
                    manipulator.overrideDependencyVersionWithComment(originalArtifact, channelVersion);
                }
            }
        }
    }

    private void processDependencyWithVersionProperty(Project pmeProject, PomManipulator manipulator, Dependency dependency,
                                                      String originalVersion, String newVersion) throws XMLStreamException {
        @SuppressWarnings("UnnecessaryLocalVariable")
        Dependency d = dependency;
        String originalVersionString = dependency.getVersion();
        String versionPropertyName = VersionUtils.extractPropertyName(originalVersionString);

        /*if (overriddenProperties.contains(versionPropertyName)) {
            // this property has been overridden based on `overrideProperties` parameter, do not process again
            return;
        }*/

        Pair<Project, String> mavenPropertyRef = lookupMavenProperty(pmeProject, versionPropertyName);

        if (mavenPropertyRef == null) {
            ChannelPluginLogger.LOGGER.errorf(
                    "Unable to upgrade %s:%s:%s to '%s', can't locate property '%s' in the project",
                    d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion,
                    versionPropertyName);
            return;
        }

        String targetPropertyName = mavenPropertyRef.getRight();

        if (isIgnoredProperty(targetPropertyName)) {
            getLog().info(String.format("Ignoring property '%s'", targetPropertyName));
            return;
        }

        if (!lockedProperties.containsKey(mavenPropertyRef)) {
            lockedProperties.put(mavenPropertyRef, newVersion);
            if (shouldUpgrade(originalVersion, newVersion)) {
                // overwrite property
                updateVersionProperty(pmeProject, dependency, mavenPropertyRef, newVersion);
            }
        } else {
            if (!newVersion.equals(lockedProperties.get(mavenPropertyRef))) {
                // overwrite dependency version inline
                manipulator.overrideDependencyVersion(d.getGroupId(), d.getArtifactId(),
                        originalVersionString, newVersion);
            }
        }
    }

    private void updateVersionProperty(Project pmeProject, Dependency dependency, Pair<Project, String> mavenPropertyRef, String newVersion)
            throws XMLStreamException {
        Project targetProject = mavenPropertyRef.getLeft();
        String targetPropertyName = mavenPropertyRef.getRight();

        if (targetProject != null) {
            // property has been located in some project module
            // => override the located property in the module where it has been located
            PomManipulator targetManipulator = manipulators.get(
                    Pair.of(targetProject.getGroupId(), targetProject.getArtifactId()));
            targetManipulator.overrideProperty(targetPropertyName, newVersion);
        } else if (injectExternalProperties) {
            // property has been located in external parent pom
            // => inject the property into current module
            PomManipulator targetManipulator = manipulators.get(
                    Pair.of(pmeProject.getGroupId(), pmeProject.getArtifactId()));
            targetManipulator.injectProperty(targetPropertyName, newVersion);
        } else {
            getLog().warn(String.format("Can't upgrade %s:%s:%s to %s, property %s is not defined in the " +
                            "scope of the project (consider enabling the injectExternalProperties parameter).",
                    dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(), newVersion,
                    targetPropertyName));
        }
    }

    private Pair<Project, String> lookupMavenProperty(Project pmeProject, String propertyName) {
        Pair<Project, String> mavenPropertyRef = followProperties(pmeProject, propertyName);
        if (mavenPropertyRef == null) {
            Pair<String, String> externalProperty = resolveExternalProperty(mavenProject, propertyName);
            if (externalProperty != null) {
                mavenPropertyRef = Pair.of(null, externalProperty.getLeft());
            }
        }
        return mavenPropertyRef;
    }

    /**
     * Performs hard property overrides based on the `overrideProperties` parameter input.
     *
     * @param manipulator manipulator for current module
     */
    private void performHardPropertyOverrides(PomManipulator manipulator) throws XMLStreamException {
        for (String nameValue: overrideProperties) {
            String[] split = nameValue.split("=");
            if (split.length != 2) {
                getLog().error(String.format("Can't interpret property to override settings: '%s'", nameValue));
                continue;
            }
            String propertyName = split[0];
            String propertyValue = split[1];
            if (manipulator.overrideProperty(propertyName, propertyValue)) {
                getLog().info(String.format("Property '%s' overridden to '%s'", propertyName, propertyValue));
                overriddenProperties.add(propertyName);
            }
        }
    }

    /**
     * Performs hard dependency versions overrides based on the `overrideDependencies` parameter input. These versions
     * are inlined into the version elements, version properties are not followed.
     *
     * @param resolvedProjectDependencies collection of all resolved dependencies in the module
     * @param manipulator manipulator for current module
     */
    private void performHardDependencyOverrides(Map<ArtifactRef, Dependency> resolvedProjectDependencies,
            PomManipulator manipulator) throws XMLStreamException {
        for (Dependency dependency: resolvedProjectDependencies.values()) {
            Optional<String> overriddenVersion = findOverriddenVersion(dependency);
            if (overriddenVersion.isPresent()) {
                manipulator.overrideDependencyVersion(toArtifactRef(dependency), overriddenVersion.get());
                overriddenDependencies.add(dependency);
            }
        }
    }

    private Optional<String> findOverriddenVersion(Dependency dependency) {
        for (String gav: overrideDependencies) {
            String[] split = gav.split(":");
            if (split.length != 3) {
                continue;
            }
            String g = split[0];
            String a = split[1];
            String v = split[2];
            if (dependency.getGroupId().equals(g) && dependency.getArtifactId().equals(a)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean isIgnoredProperty(String propertyName) {
        if (ignoreProperties.contains(propertyName)) {
            return true;
        }
        for (String prefix: ignorePropertiesPrefixedWith) {
            if (propertyName.startsWith(prefix)) {
                return true;
            }
        }
        if (overriddenProperties.contains(propertyName)) {
            // this property has been overridden based on `overrideProperties` parameter, do not process again
            return true;
        }
        return false;
    }

    private Map<ArtifactRef, Dependency> collectResolvedProjectDependencies(Project pmeProject)
            throws ManipulationException {
        Map<ArtifactRef, Dependency> projectDependencies = new HashMap<>();

        projectDependencies.putAll(pmeProject.getResolvedManagedDependencies(manipulationSession));
        projectDependencies.putAll(pmeProject.getResolvedDependencies(manipulationSession));

        if (projectDependencies.isEmpty()) {
            getLog().debug("No dependencies found in " + pmeProject.getArtifactId());
        }

        // If the version was controlled by a property, it should have been resolved into a specific version string
        // by now.
        // PME doesn't seem to resolve properties defined in parent poms, so now we have a chance to fix that.
        // If the property could still not be resolved from parent poms, ignore this dependency.
        Map<ArtifactRef, Dependency> correctedDependencies = new HashMap<>();
        projectDependencies.forEach((artifact, dependency) -> {
            if (VersionUtils.isProperty(artifact.getVersionString())) {
                Pair<String, String> externalProperty = resolveExternalProperty(mavenProject,
                        VersionUtils.extractPropertyName(artifact.getVersionString()));
                if (externalProperty != null) {
                    SimpleArtifactRef newArtifact = new SimpleArtifactRef(artifact.getGroupId(), artifact.getArtifactId(),
                            externalProperty.getRight(), artifact.getType(), artifact.getClassifier());
                    correctedDependencies.put(newArtifact, dependency);
                } else {
                    getLog().error("Following dependency uses a version property that could not be resolved: " + dependency.toString());
                }
            } else {
                correctedDependencies.put(artifact, dependency);
            }
        });

        return correctedDependencies;
    }

    private boolean isIgnoredDependency(ArtifactRef artifact, Dependency dependency) {
        // Ignore internal project dependencies (project submodules)
        if (projectGavs.contains(artifact.asProjectVersionRef())) {
            getLog().debug("Ignoring in-project dependency: "
                    + artifact.asProjectVersionRef().toString());
            return true;
        }

        // Ignore based on ignoreStreams / dontIgnoreStreams parameters
        if (!unignoredStreams.contains(artifact.asProjectRef())) {
            if (ignoredStreams.contains(artifact.asProjectRef())) {
                getLog().info("Skipping dependency (ignored stream): "
                        + artifact.asProjectVersionRef().toString());
                return true;
            }
            ProjectRef wildCardIgnoredProjectRef = new SimpleProjectRef(artifact.getGroupId(), "*");
            if (ignoredStreams.contains(wildCardIgnoredProjectRef)) {
                getLog().info("Skipping dependency (ignored stream): "
                        + artifact.asProjectVersionRef().toString());
                return true;
            }
        }

        // Ignore based on scope
        if (ignoreScopes.contains(dependency.getScope())) {
            getLog().info("Skipping dependency (ignored scope): "
                    + artifact.asProjectVersionRef().toString());
            return true;
        }

        // Ignore if the dependency has been specifically overridden via the overrideDependencies parameter
        if (overriddenDependencies.contains(dependency)) {
            return true;
        }

        // Ignore if it was not possible to resolve the current dependency version
        if (artifact.getVersionString() == null) {
            // this is not expected to happen
            getLog().error("Resolved dependency has null version: " + artifact);
            return true;
        }

        return false;
    }

    private Optional<String> resolveChannelVersion(ArtifactRef artifactRef) {
        try {
            VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(artifactRef.getGroupId(),
                    artifactRef.getArtifactId(), artifactRef.getType(), artifactRef.getClassifier(),
                    artifactRef.getVersionString());
            return Optional.of(versionResult.getVersion());
        } catch (UnresolvedMavenArtifactException e) {
            // this produces a lot of noise due to many of e.g. test artifacts not being managed by channels, so keep it
            // at the debug level
            getLog().debug("Can't resolve artifact: " + artifactRef, e);
            return Optional.empty();
        }
    }

    /**
     * Overrides versions of transitive dependencies in all project's modules.
     * <p>
     * This has to be called after all submodules has been processed (so that all declared dependencies has been
     * collected).
     */
    private void injectTransitiveDependencies() throws DependencyGraphBuilderException {
        final List<ProjectRef> projectGAs = projectGavs.stream().map(ProjectRef::asProjectRef)
                .collect(Collectors.toList());

        // This performs a traversal of a dependency tree of all submodules in the project. All discovered dependencies
        // that are not directly declared in the project are considered transitive dependencies.
        Map<ArtifactRef, Collection<ProjectRef>> dependenciesToInject = new HashMap<>();
        ArrayList<MavenProject> projects = new ArrayList<>();
        projects.add(mavenProject);
        List<MavenProject> collectedProjects = mavenProject.getCollectedProjects().stream()
                .filter(p -> !isIgnoredModule(p.getGroupId(), p.getArtifactId()))
                .collect(Collectors.toList());
        projects.addAll(collectedProjects);
        for (MavenProject module: projects) {

            // Collect exclusions from the effective POM
            Map<ArtifactRef, Collection<ProjectRef>> artifactExclusions = getDependencyExclusions(module);

            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
            buildingRequest.setProject(module);
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
            CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
            rootNode.accept(visitor);
            visitor.getNodes().forEach(node -> {
                ArtifactRef artifact = toArtifactRef(node.getArtifact());
                // Project modules should not be counted into undeclared dependencies.
                if (projectGAs.contains(artifact.asProjectRef())) {
                    return;
                }
                // Declared project dependencies should not be counted as undeclared.
                if (declaredDependencies.contains(artifact.asProjectRef())) {
                    return;
                }
                // Ignore specific scopes.
                if (ignoreScopes.contains(node.getArtifact().getScope())) {
                    // Ignore test scope undeclared dependencies entirely.
                    return;
                }
                // Check if the dependency channel stream is configured as ignored.
                boolean isIgnored = ignoredStreams.contains(artifact.asProjectRef())
                        || ignoredStreams.contains(new SimpleProjectRef(artifact.getGroupId(), "*"));
                boolean isUnignored = unignoredStreams.contains(artifact.asProjectRef());
                if (isIgnored && !isUnignored) {
                    return;
                }

                Collection<ProjectRef> exclusions = artifactExclusions.getOrDefault(artifact, Collections.emptyList());
                Collection<ProjectRef> existingExclusions = dependenciesToInject.computeIfAbsent(artifact, a -> new HashSet<>());
                existingExclusions.addAll(exclusions);
            });
        }

        dependenciesToInject.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEachOrdered(entry -> {
            ArtifactRef artifact = entry.getKey();
            Collection<ProjectRef> exclusions = entry.getValue();
            String newVersion;
            try {
                // Check if the dependency is updated by the channel.
                VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(artifact.getGroupId(),
                        artifact.getArtifactId(), artifact.getType(), artifact.getClassifier(), artifact.getVersionString());
                newVersion = versionResult.getVersion();
            } catch (NoStreamFoundException e) {
                // No stream found -> dependency remains the same.
                return;
            }

            if (shouldUpgrade(artifact.getVersionString(), newVersion)) {
                SimpleArtifactRef newDependency = new SimpleArtifactRef(artifact.getGroupId(), artifact.getArtifactId(),
                        newVersion, artifact.getType(), artifact.getClassifier());
                getLog().info(String.format("Injecting undeclared dependency: %s (original version was %s)", newDependency,
                        artifact.getVersionString()));

                try {
                    rootManipulator.injectManagedDependency(newDependency, exclusions, artifact.getVersionString());
                } catch (XMLStreamException e) {
                    throw new RuntimeException("Failed to inject a managed dependency", e);
                }
            }
        });

    }

    private static Map<ArtifactRef, Collection<ProjectRef>> getDependencyExclusions(MavenProject module) {
        Map<ArtifactRef, Collection<ProjectRef>> artifactExclusions = new HashMap<>();
        List<Dependency> managedDependencies = Collections.emptyList();
        if (module.getModel().getDependencyManagement() != null) {
            managedDependencies = module.getModel().getDependencyManagement().getDependencies();
        }
        managedDependencies.forEach(dep -> artifactExclusions.put(toArtifactRef(dep), toProjectRefs(dep.getExclusions())));
        return artifactExclusions;
    }

    private boolean isIgnoredModule(String groupId, String artifactId) {
        return ignoreModules.contains(groupId + ":" + artifactId)
                || (groupId.equals(mavenProject.getGroupId()) && ignoreModules.contains(":" + artifactId));
    }

    /**
     * If a property references another property (possibly recursively), this method returns the final referenced
     * property name and the module where the property is defined.
     * <p>
     * This method doesn't support cases when a property value is a composition of multiple properties, or a composition
     * of properties and strings.
     */
    static Pair<Project, String> followProperties(Project pmeProject, String propertyName) {
        Properties properties = pmeProject.getModel().getProperties();
        if (!properties.containsKey(propertyName)) {
            // property not present in current module, look into parent module
            Project parentProject = pmeProject.getProjectParent();
            if (parentProject == null) {
                return null;
            } else {
                return followProperties(parentProject, propertyName);
            }
        } else {
            // property is defined in this module
            String propertyValue = (String) properties.get(propertyName);
            if (VersionUtils.isProperty(propertyValue)) {
                // the property value is also a property reference -> follow the chain
                String newPropertyName = VersionUtils.extractPropertyName(propertyValue);
                Pair<Project, String> targetProperty = followProperties(pmeProject, newPropertyName);
                if (targetProperty != null) {
                    return targetProperty;
                }
            }
            return Pair.of(pmeProject, propertyName);
        }
    }

    /**
     * Resolves a property from external parent pom. If given property references another property, this method
     * tries to traverse the property chain.
     *
     * @return pair [property, value], where the property is the last property name in the traversal chain
     */
    static Pair<String, String> resolveExternalProperty(MavenProject mavenProject, String propertyName) {
        if (mavenProject == null) {
            return null;
        }
        Properties properties = mavenProject.getModel().getProperties();
        if (!properties.containsKey(propertyName)) {
            return resolveExternalProperty(mavenProject.getParent(), propertyName);
        } else {
            // property is defined in this module
            String propertyValue = (String) properties.get(propertyName);
            if (VersionUtils.isProperty(propertyValue)) {
                // the property value is also a property reference -> follow the chain
                String newPropertyName = VersionUtils.extractPropertyName(propertyValue);
                Pair<String, String> targetProperty = resolveExternalProperty(mavenProject, newPropertyName);
                if (targetProperty != null) {
                    return targetProperty;
                }
            }
            return Pair.of(propertyName, propertyValue);
        }
    }

    /**
     * @return should the version be upgraded?
     */
    private boolean shouldUpgrade(String originalVersion, String newVersion) {
        int compare = VERSION_COMPARATOR.compare(newVersion.trim(), originalVersion.trim());
        return compare > 0 || (!doNotDowngrade && compare < 0);
    }
}
