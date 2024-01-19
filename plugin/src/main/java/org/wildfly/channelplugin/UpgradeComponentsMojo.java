package org.wildfly.channelplugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
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
import org.commonjava.maven.ext.io.PomIO;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.Repository;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.VersionResult;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channeltools.util.VersionUtils;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
@Mojo(name = "upgrade", requiresProject = true, requiresDirectInvocation = true)
public class UpgradeComponentsMojo extends AbstractMojo {

    private static final String LOCAL_MAVEN_REPO = System.getProperty("user.home") + "/.m2/repository";

    /**
     * Path to the channel definition file on a local filesystem.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(required = false, property = "channelFile")
    String channelFile;

    /**
     * Path to the channel manifest file on a local filesystem.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(required = false, property = "manifestFile")
    String manifestFile;

    /**
     * GAV of an artifact than contains the channel file.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(required = false, property = "channelGAV")
    String channelGAV;

    /**
     * GAV of an artifact than contains the channel file.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(required = false, property = "manifestGAV")
    String manifestGAV;

    /**
     * Comma separated list of remote repositories URLs, that should be used to resolve artifacts.
     */
    @Parameter(required = false, property = "remoteRepositories")
    List<String> remoteRepositories;

    /**
     * Local repository path.
     */
    @Parameter(property = "localRepository")
    String localRepositoryPath;

    /**
     * Comma separated list of dependency G:As that should not be upgraded.
     */
    @Parameter(property = "ignoreStreams")
    List<String> ignoreStreams;

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
     * Replace property reference in dependency version element with inlined version string, when it's not possible to
     * override property value due to conflicting versions for different dependencies that use the property.
     */
    @Parameter(property = "inlineVersionOnConflict", defaultValue = "true")
    boolean inlineVersionOnConflict;

    /**
     * If true, transitive dependencies of the project that are also declared in the channel will be injected into root
     * POM's dependencyManagement section.
     */
    @Parameter(property = "injectTransitiveDependencies", defaultValue = "true")
    boolean injectTransitiveDependencies;

    /**
     * When injecting new dependency elements (to override transitive dependency versions), add exclussions according to given
     * project submodule (given in G:A format).
     * <p>
     * Effectively, this means that when a transitive dependency G:A:V is going to be injected to override the dependency version,
     * the plugin will try to find this same dependency in a dependency tree of a given submodule and copy exclusions it finds
     * there to the injected dependency element.
     * <p>
     * If this parameter is unset, the dependency exclusions are taken from managed dependencies section of the root module
     * effective POM.
     */
    @Parameter(property = "copyExclusionsFrom")
    String copyExclusionsFrom;

    @Parameter(property = "ignoreTestDependencies", defaultValue = "true")
    boolean ignoreTestDependencies;

    /**
     * When a dependency is defined with version string referencing a property, and that property is defined in a parent
     * pom outside the project, the property would be injected into a pom where the dependency is defined, if this
     * parameter is set to true (default).
     */
    @Parameter(property = "injectExternalProperties", defaultValue = "true")
    boolean injectExternalProperties;

    @Inject
    DependencyGraphBuilder dependencyGraphBuilder;

    @Inject
    MavenProject mavenProject;

    @Inject
    MavenSession mavenSession;

    @Inject
    PomIO pomIO;

    @Inject
    ManipulationSession manipulationSession;

    @Inject
    RepositorySystem repositorySystem;

    private List<Channel> channels;
    private ChannelSession channelSession;
    private final List<ProjectRef> ignoredStreams = new ArrayList<>();
    private final List<ProjectRef> ignoredModules = new ArrayList<>();
    private Set<ProjectVersionRef> projectGavs;
    private final HashMap<Pair<String, String>, PomManipulator> manipulators = new HashMap<>();
    private final HashMap<Pair<Project, String>, String> upgradedProperties = new HashMap<>();
    private final Set<ProjectRef> declaredDependencies = new HashSet<>();

    /**
     * This includes pre-processing of input parameters.
     */
    private void init() throws MojoExecutionException {
        if (StringUtils.isBlank(localRepositoryPath)) {
            localRepositoryPath = LOCAL_MAVEN_REPO;
        }

        final DefaultRepositorySystemSession repositorySystemSession = MavenRepositorySystemUtils.newSession();
        final LocalRepository localRepository = new LocalRepository(localRepositoryPath);
        final LocalRepositoryManager localRepoManager = repositorySystem.newLocalRepositoryManager(repositorySystemSession,
                localRepository);
        repositorySystemSession.setLocalRepositoryManager(localRepoManager);

        initChannel();
        if (!remoteRepositories.isEmpty()) {
            // TODO: Include remote repositories defined in the project pom.xml?
            channels = overrideRemoteRepositories(channels, remoteRepositories);
        }

        channelSession = new ChannelSession(channels, new VersionResolverFactory(repositorySystem, repositorySystemSession));

        ignoreStreams.forEach(ga -> ignoredStreams.add(SimpleProjectRef.parse(ga)));
        ignoreModules.forEach(ga -> ignoredModules.add(SimpleProjectRef.parse(ga)));
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
            List<Project> pmeProjects = parsePmeProjects();

            // collect GAVs of in-project modules, these are not going to be upgraded
            projectGavs = pmeProjects.stream()
                    .map(p -> new SimpleProjectVersionRef(p.getGroupId(), p.getArtifactId(), p.getVersion()))
                    .collect(Collectors.toSet());

            // process project modules
            for (Project project: pmeProjects) {
                ProjectRef moduleGA = project.getKey().asProjectRef();
                if (ignoredModules.contains(moduleGA)) {
                    getLog().info(String.format("Skipping module %s:%s", project.getGroupId(), project.getArtifactId()));
                    continue;
                }

                getLog().info(String.format("Processing module %s:%s", project.getGroupId(), project.getArtifactId()));

                // create manipulator for given module
                PomManipulator manipulator = new PomManipulator(project);
                manipulators.put(Pair.of(project.getGroupId(), project.getArtifactId()), manipulator);

                processModule(project, manipulator);
            }

            if (injectTransitiveDependencies) {
                injectTransitiveDependencies();
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

        List<String> overriddenProperties = performHardPropertyOverrides(manipulator);
        List<Dependency> overriddenDependencies = performHardDependencyOverrides(resolvedProjectDependencies, manipulator);

        List<Pair<Dependency, String>> dependenciesToUpgrade = findDependenciesToUpgrade(resolvedProjectDependencies);
        for (Pair<Dependency, String> upgrade: dependenciesToUpgrade) {
            String newVersion = upgrade.getRight();
            Dependency locatedDependency = upgrade.getLeft();

            @SuppressWarnings("UnnecessaryLocalVariable")
            Dependency d = locatedDependency;

            if (overriddenDependencies.contains(locatedDependency)) {
                // if there was a hard version override, the dependency is not processed again
                continue;
            }

            if (VersionUtils.isProperty(locatedDependency.getVersion())) { // dependency version is set from a property
                String originalVersionString = locatedDependency.getVersion();
                String versionPropertyName = VersionUtils.extractPropertyName(originalVersionString);

                if (overriddenProperties.contains(versionPropertyName)) {
                    // this property has been overridden based on `overrideProperties` parameter, do not process again
                    continue;
                }

                Pair<Project, String> projectProperty = followProperties(pmeProject, versionPropertyName);
                if (projectProperty == null) {
                    Pair<String, String> externalProperty = resolveExternalProperty(mavenProject, versionPropertyName);
                    if (externalProperty != null) {
                        projectProperty = Pair.of(null, externalProperty.getLeft());
                    }
                }

                if (projectProperty == null) {
                    ChannelPluginLogger.LOGGER.errorf(
                            "Unable to upgrade %s:%s:%s to '%s', can't locate property '%s' in the project",
                            d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion,
                            versionPropertyName);
                    continue;
                }

                Project targetProject = projectProperty.getLeft();
                String targetPropertyName = projectProperty.getRight();

                if (isIgnoredProperty(targetPropertyName)) {
                    getLog().info(String.format("Ignoring property '%s'", targetPropertyName));
                    continue;
                }

                if (upgradedProperties.containsKey(projectProperty)) {
                    if (!upgradedProperties.get(projectProperty).equals(newVersion)) {
                        // property has already been changed to different value
                        String propertyName = projectProperty.getRight();
                        String currentPropertyValue = upgradedProperties.get(projectProperty);
                        if (inlineVersionOnConflict) {
                            getLog().warn(String.format("Inlining version string for %s:%s:%s, new version '%s'. " +
                                    "The original version property '%s' has already been modified to '%s'.",
                                    d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion, propertyName,
                                    currentPropertyValue));
                            manipulator.overrideDependencyVersion(d.getGroupId(), d.getArtifactId(),
                                    originalVersionString, newVersion);
                        } else {
                            getLog().warn(String.format(
                                    "Can't upgrade %s:%s:%s to '%s', property '%s' was already upgraded to '%s'.",
                                    d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion,
                                    propertyName,
                                    currentPropertyValue));
                        }
                        continue; // do not override the property again
                    }
                }

                // get manipulator for the module where the target property is located
                upgradedProperties.put(projectProperty, newVersion);

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
                            d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion, targetPropertyName));
                }
            } else { // dependency version is inlined in version element, can be directly overwritten
                manipulator.overrideDependencyVersion(toArtifactRef(locatedDependency), newVersion);
            }
        }

    }

    /**
     * Injects all transitive dependencies that are present in channels into parent POM's dependencyManagement section.
     * <p>
     * Call this method after all modules has been processed via the `processModule()` method.
     */
    private void injectTransitiveDependencies() throws DependencyGraphBuilderException, XMLStreamException {
        PomManipulator rootManipulator = manipulators.get(
                Pair.of(mavenProject.getGroupId(), mavenProject.getArtifactId()));
        // (dependency => exclusions list) map of undeclared dependencies
        Map<ArtifactRef, Collection<ProjectRef>> undeclaredDependencies = collectUndeclaredDependencies();

        List<Map.Entry<ArtifactRef, Collection<ProjectRef>>> dependenciesToInject = undeclaredDependencies.entrySet().stream()
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                // filter only deps that have a stream defined in the channel
                .filter(entry -> {
                    // verify if the artifact from the project dependency tree is modified by the channel
                    ArtifactRef a = entry.getKey();
                    try {
                        VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(a.getGroupId(), a.getArtifactId(),
                                a.getType(), a.getClassifier(), a.getVersionString());
                        return !versionResult.getVersion().equals(a.getVersionString());
                    } catch (UnresolvedMavenArtifactException e) {
                        // no stream found -> no change
                        return false;
                    }
                })
                .filter(entry -> {
                    ArtifactRef a = entry.getKey();
                    return !(ignoredStreams.contains(new SimpleProjectRef(a.getGroupId(), a.getArtifactId()))
                        || ignoredStreams.contains(new SimpleProjectRef(a.getGroupId(), "*")));
                })
                .collect(Collectors.toList());
        for (Map.Entry<ArtifactRef, Collection<ProjectRef>> entry : dependenciesToInject) {
            ArtifactRef a = entry.getKey();
            try {
                VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(a.getGroupId(), a.getArtifactId(),
                        a.getType(), a.getClassifier(), a.getVersionString());
                String newVersion = versionResult.getVersion();
                if (!newVersion.equals(a.getVersionString())) {
                    SimpleArtifactRef newDependency = new SimpleArtifactRef(a.getGroupId(), a.getArtifactId(), newVersion,
                            a.getType(), a.getClassifier());
                    getLog().info(String.format("Injecting undeclared dependency: %s (original version was %s)", newDependency,
                            a.getVersionString()));
                    rootManipulator.injectManagedDependency(newDependency, entry.getValue());
                }
            } catch (UnresolvedMavenArtifactException e) {
                getLog().error(String.format("Unable to resolve dependency %s", a));
            }
        }
    }

    /**
     * Performs hard property overrides based on the `overrideProperties` parameter input.
     *
     * @param manipulator manipulator for current module
     * @return list of overridden properties
     */
    private List<String> performHardPropertyOverrides(PomManipulator manipulator) throws XMLStreamException {
        ArrayList<String> overriddenProperties = new ArrayList<>();
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
        return overriddenProperties;
    }

    /**
     * Performs hard dependency versions overrides based on the `overrideDependencies` parameter input. These versions
     * are inlined into the version elements, version properties are not followed.
     *
     * @param resolvedProjectDependencies collection of all resolved dependencies in the module
     * @param manipulator manipulator for current module
     * @return list of updated dependencies
     */
    private List<Dependency> performHardDependencyOverrides(Map<ArtifactRef, Dependency> resolvedProjectDependencies,
            PomManipulator manipulator) throws XMLStreamException {
        List<Dependency> overriddenDependencies = new ArrayList<>();
        for (Dependency dependency: resolvedProjectDependencies.values()) {
            Optional<String> overridenVersion = findOverridenVersion(dependency);
            if (overridenVersion.isPresent()) {
                manipulator.overrideDependencyVersion(toArtifactRef(dependency), overridenVersion.get());
                overriddenDependencies.add(dependency);
            }
        }
        return overriddenDependencies;
    }

    private Optional<String> findOverridenVersion(Dependency dependency) {
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

    private boolean isIgnoredProperty(String propertyName) {
        if (ignoreProperties.contains(propertyName)) {
            return true;
        }
        for (String prefix: ignorePropertiesPrefixedWith) {
            if (propertyName.startsWith(prefix)) {
                return true;
            }
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

        return projectDependencies;
    }

    private List<Pair<Dependency, String>> findDependenciesToUpgrade(
            Map<ArtifactRef, Dependency> resolvedProjectDependencies) {
        List<Pair<Dependency, String>> dependenciesToUpgrade = new ArrayList<>();
        for (Map.Entry<ArtifactRef, Dependency> entry : resolvedProjectDependencies.entrySet()) {
            ArtifactRef artifactRef = entry.getKey();
            Dependency dependency = entry.getValue();

            Objects.requireNonNull(artifactRef);
            Objects.requireNonNull(dependency);

            if (projectGavs.contains(artifactRef.asProjectVersionRef())) {
                getLog().debug("Ignoring in-project dependency: "
                        + artifactRef.asProjectVersionRef().toString());
                continue;
            }
            if (ignoredStreams.contains(artifactRef.asProjectRef())) {
                getLog().info("Skipping dependency (ignored stream): "
                        + artifactRef.asProjectVersionRef().toString());
                continue;
            }
            ProjectRef wildCardIgnoredProjectRef = new SimpleProjectRef(artifactRef.getGroupId(), "*");
            if (ignoredStreams.contains(wildCardIgnoredProjectRef)) {
                getLog().info("Skipping dependency (ignored stream): "
                        + artifactRef.asProjectVersionRef().toString());
                continue;
            }
            if (artifactRef.getVersionString() == null) {
                // this is not expected to happen
                getLog().error("Resolved dependency has null version: " + artifactRef);
                continue;
            }
            if (VersionUtils.isProperty(artifactRef.getVersionString())) {
                // hack: PME doesn't seem to resolve properties from external parent poms
                Pair<String, String> externalProperty = resolveExternalProperty(mavenProject,
                        VersionUtils.extractPropertyName(artifactRef.getVersionString()));
                if (externalProperty != null) {
                    artifactRef = new SimpleArtifactRef(artifactRef.getGroupId(), artifactRef.getArtifactId(),
                            externalProperty.getRight(), artifactRef.getType(), artifactRef.getClassifier());
                } else {
                    // didn't manage to resolve dependency version, this is not expected to happen
                    getLog().error("Resolved dependency has version with property: " + artifactRef);
                    continue;
                }
            }
            if ("test".equals(dependency.getScope()) && ignoreTestDependencies) {
                getLog().info("Skipping dependency (ignored scope): "
                        + artifactRef.asProjectVersionRef().toString());
                continue;
            }


            try {
                VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(artifactRef.getGroupId(),
                        artifactRef.getArtifactId(), artifactRef.getType(), artifactRef.getClassifier(),
                        artifactRef.getVersionString());
                String channelVersion = versionResult.getVersion();

                if (!channelVersion.equals(artifactRef.getVersionString())) {
                    getLog().info("Updating dependency " + artifactRef.getGroupId()
                            + ":" + artifactRef.getArtifactId() + ":" + artifactRef.getVersionString()
                            + " to version " + channelVersion);
                }
                dependenciesToUpgrade.add(Pair.of(dependency, channelVersion));
            } catch (UnresolvedMavenArtifactException e) {
                // this produces a lot of noise due to many of e.g. test artifacts not being managed by channels, so keep it
                // at the debug level
                getLog().debug("Can't resolve artifact: " + artifactRef, e);
            }
        }
        return dependenciesToUpgrade;
    }

    private void initChannel() throws MojoExecutionException {
        int numberOfSources = 0;
        if (StringUtils.isNotBlank(channelFile)) numberOfSources++;
        if (StringUtils.isNotBlank(channelGAV)) numberOfSources++;
        if (StringUtils.isNotBlank(manifestFile)) numberOfSources++;
        if (StringUtils.isNotBlank(manifestGAV)) numberOfSources++;
        if (numberOfSources > 1) {
            throw new MojoExecutionException("Exactly one of [channelFile, channelGAV, manifestFile, manifestGAV] has to be given.");
        }

        // Do not enforce this for now, repositories are also read from project pom.xml currently.
        /*if ((StringUtils.isNotBlank(manifestFile) || StringUtils.isNotBlank(manifestGAV)) && remoteRepositories.isEmpty()) {
            throw new MojoExecutionException("The remoteRepositories property is mandatory when manifest is given.");
        }*/

        try {
            if (StringUtils.isNotBlank(channelFile)) {
                Path channelFilePath = Path.of(channelFile);
                if (!channelFilePath.isAbsolute()) {
                    channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
                }
                getLog().info("Reading channel file " + channelFilePath);
                channels = List.of(ChannelMapper.from(channelFilePath.toUri().toURL()));
            } else if (StringUtils.isNotBlank(channelGAV)) {
                channels = resolveChannelsFromGav(channelGAV);
            } else if (StringUtils.isNotBlank(manifestFile)) {
                URL manifestUrl = Path.of(manifestFile).toUri().toURL();
                ChannelManifestCoordinate coordinate = new ChannelManifestCoordinate(manifestUrl);
                channels = List.of(new Channel("a-channel", null, null, null, coordinate, null, null));
            } else if (StringUtils.isNotBlank(manifestGAV)) {
                ChannelManifestCoordinate coordinate = toManifestCoordinate(manifestGAV);
                // Compose list of repositories to look for the manifest as a union of the remoteRepositories property
                // and repositories from the project pom.xml.
                List<Repository> repositories = mavenProject.getRemoteProjectRepositories().stream()
                        .map(rr -> new Repository(rr.getId(), rr.getUrl()))
                        .collect(Collectors.toList());
                repositories.addAll(createRepositories(remoteRepositories));
                channels = List.of(new Channel("a-channel", null, null, repositories, coordinate, null, null));
            } else {
                throw new MojoExecutionException("No channel or manifest specified.");
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Can't parse the channel or manifest file path", e);
        }
    }

    private List<Channel> resolveChannelsFromGav(String gavString) {
        ChannelCoordinate channelCoordinate = toChannelCoordinate(gavString);

        RepositorySystemSession repoSession = mavenSession.getRepositorySession();

        // Collect repositories that should be used to locate the channel. Probably this should be a union of the
        // repositories given in the `remoteRepositories` property and the repositories defined in the project pom.xml.
        final List<RemoteRepository> channelRepos = new ArrayList<>(mavenProject.getRemoteProjectRepositories());
        int repoNumber = 0;
        if (remoteRepositories != null && !remoteRepositories.isEmpty()) {
            for (String repoUrl: remoteRepositories) {
                RemoteRepository repo = new RemoteRepository.Builder("repo-" + repoNumber++, "default", repoUrl).build();
                channelRepos.add(repo);
            }
        }

        try (VersionResolverFactory versionResolverFactory = new VersionResolverFactory(repositorySystem, repoSession)) {
            return versionResolverFactory.resolveChannels(List.of(channelCoordinate), channelRepos);
        } catch (MalformedURLException e) {
            // This should not happen here, URL coordinates are not supposed to be present.
            throw new IllegalStateException("Couldn't resolve channel GAV", e);
        }
    }

    private static ChannelCoordinate toChannelCoordinate(String gavString) {
        String[] gavSegments = gavString.split(":");
        ChannelCoordinate coordinate;
        if (gavSegments.length == 2) {
            coordinate = new ChannelCoordinate(gavSegments[0], gavSegments[1]);
        } else if (gavSegments.length == 3) {
            coordinate = new ChannelCoordinate(gavSegments[0], gavSegments[1], gavSegments[2]);
        } else {
            throw new IllegalArgumentException("Invalid GAV string, channel GAV has to have two or three segments separated with ':'. Given value was: " + gavString);
        }
        return coordinate;
    }

    private static ChannelManifestCoordinate toManifestCoordinate(String gavString) {
        String[] gavSegments = gavString.split(":");
        ChannelManifestCoordinate coordinate;
        if (gavSegments.length == 2) {
            coordinate = new ChannelManifestCoordinate(gavSegments[0], gavSegments[1]);
        } else if (gavSegments.length == 3) {
            coordinate = new ChannelManifestCoordinate(gavSegments[0], gavSegments[1], gavSegments[2]);
        } else {
            throw new IllegalArgumentException("Invalid GAV string, manifest GAV has to have two or three segments separated with ':'. Given value was: " + gavString);
        }
        return coordinate;
    }

    private List<Channel> overrideRemoteRepositories(List<Channel> channels, List<String> repositories) {
        List<Channel> updatedChannels = new ArrayList<>(channels.size());
        for (Channel channel: channels) {
            updatedChannels.add(new Channel(channel.getName(), channel.getDescription(), channel.getVendor(), createRepositories(repositories),
                    channel.getManifestCoordinate(), channel.getBlocklistCoordinate(), channel.getNoStreamStrategy()));
        }
        return updatedChannels;
    }

    private List<Repository> createRepositories(List<String> userRepositories) {
        HashMap<String, Repository> result = new HashMap<>();
        int idx = 0;
        for (String input: userRepositories) {
            String[] segments = input.split("::");
            Repository previous;
            String id;
            if (segments.length == 1) {
                id = "repo-" + idx++;
                previous = result.put(id, new Repository(id, segments[0]));
            } else if (segments.length == 2) {
                id = segments[0];
                previous = result.put(id, new Repository(id, segments[1]));
            } else {
                throw new IllegalArgumentException("Invalid remote repository entry: " + input);
            }
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate remote repository key: '" + id + "'");
            }
        }
        return new ArrayList<>(result.values());
    }

    /**
     * Returns PME representation of current project module and its submodules.
     */
    private List<Project> parsePmeProjects() throws ManipulationException {
        return pomIO.parseProject(mavenProject.getModel().getPomFile());
    }

    /**
     * Collects transitive dependencies from all project's modules, which are not declared in the project.
     * <p>
     * This has to be called after all submodules has been processed (so that all declared dependencies has been
     * collected).
     *
     * @return a map containing a dependencies as keys and list of exclusions as values.
     */
    private Map<ArtifactRef, Collection<ProjectRef>> collectUndeclaredDependencies() throws DependencyGraphBuilderException {
        Map<ArtifactRef, Collection<ProjectRef>> artifactExclusions = new HashMap<>();

        // First of all, if `copyExclusionsFrom` module has been set, we are going to remember exclusions from all dependencies
        // of this module. These exclusions will be used for the newly injected dependency elements.

        // First of all, we want to collect information about exclusions for the dependencies that are eventually going to be
        // injected into the project. There are two possible strategies:
        if (copyExclusionsFrom != null) {
            // Either collect the exclusions from a dependency tree of a project submodule that a user specified.
            ProjectRef exclusionsModule = SimpleProjectRef.parse(copyExclusionsFrom);
            Optional<MavenProject> exclusionsProject = mavenProject.getCollectedProjects().stream()
                    .filter(p -> exclusionsModule.getGroupId().equals(p.getGroupId())
                             && exclusionsModule.getArtifactId().equals(p.getArtifactId()))
                    .findFirst();
            if (exclusionsProject.isPresent()) {
                ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
                buildingRequest.setProject(exclusionsProject.get());
                DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
                CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
                rootNode.accept(visitor);
                visitor.getNodes().forEach(node -> {
                    HashSet<ProjectRef> exclusionSet = new HashSet<>(toProjectRefs(node.getExclusions()));
                    Collection<ProjectRef> previousExclusions = artifactExclusions.put(toArtifactRef(node.getArtifact()), exclusionSet);
                    if (previousExclusions != null) {
                        exclusionSet.addAll(previousExclusions);
                    }
                });
            }
        } else {
            // Or else collect the exclusions from a dependency management section of an effective POM of the root module.
            List<Dependency> managedDependencies = mavenProject.getModel().getDependencyManagement().getDependencies();
            managedDependencies.forEach(d -> artifactExclusions.put(toArtifactRef(d), toProjectRefs(d.getExclusions())));
        }

        // This performs a traversal of a dependency tree of all submodules in the project. All discovered dependencies
        // that are not directly declared in the project are considered transitive dependencies.
        Map<ArtifactRef, Collection<ProjectRef>> undeclaredDependencies = new HashMap<>();
        for (MavenProject module: mavenProject.getCollectedProjects()) {
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
            buildingRequest.setProject(module);
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
            CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
            rootNode.accept(visitor);
            visitor.getNodes().forEach(node -> {
                ArtifactRef artifact = toArtifactRef(node.getArtifact());
                Collection<ProjectRef> exclusions = artifactExclusions.get(artifact);
                if (declaredDependencies.contains(artifact.asProjectRef())) {
                    return;
                }
                if ("test".equals(node.getArtifact().getScope()) && ignoreTestDependencies) {
                    // Ignore test scope undeclared dependencies entirely.
                    return;
                }
                undeclaredDependencies.put(artifact, exclusions);
            });
        }
        return undeclaredDependencies;
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

}
