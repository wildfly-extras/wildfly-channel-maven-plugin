package org.wildfly.channelplugin;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

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
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.Repository;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channeltools.util.VersionUtils;

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
    private static final String CHANNEL_CLASSIFIER = "channel";
    private static final String CHANNEL_EXTENSION = "yaml";

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
    @Parameter(property = "ignoreStreams", defaultValue = "")
    List<String> ignoreStreams;

    /**
     * Comma separated list of module G:As that should not be processed.
     */
    @Parameter(property = "ignoreModules", defaultValue = "")
    List<String> ignoreModules;

    /**
     * Comma separated list of property names. Project properties that match one of these names will not get
     * overridden.
     */
    @Parameter(property = "ignoreProperties", defaultValue = "")
    List<String> ignoreProperties;

    /**
     * Comma separated list of property names prefixes. Project properties that match one of these prefixes will not get
     * overridden.
     */
    @Parameter(property = "ignorePropertiesPrefixedWith", defaultValue = "")
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

    private Channel channel;
    private ChannelSession channelSession;
    private final List<ProjectRef> ignoredStreams = new ArrayList<>();
    private final List<ProjectRef> ignoredModules = new ArrayList<>();
    private Set<ProjectVersionRef> projectGavs;
    private final HashMap<Pair<String, String>, PomManipulator> manipulators = new HashMap<>();
    private final HashMap<Pair<Project, String>, String> upgradedProperties = new HashMap<>();
    private final Set<ProjectRef> declaredDependencies = new HashSet<>();

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
            channel = overrideRemoteRepositories(channel, remoteRepositories);
        }

        channelSession = new ChannelSession(Collections.singletonList(channel),
                new VersionResolverFactory(repositorySystem, repositorySystemSession));

        ignoreStreams.forEach(ga -> ignoredStreams.add(SimpleProjectRef.parse(ga)));
        ignoreModules.forEach(ga -> ignoredModules.add(SimpleProjectRef.parse(ga)));
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

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

        List<Pair<Dependency, String>> dependenciesToUpgrade = findDepenenciesToUpgrade(resolvedProjectDependencies);
        for (Pair<Dependency, String> upgrade: dependenciesToUpgrade) {
            String newVersion = upgrade.getRight();
            Dependency locatedDependency = upgrade.getLeft();

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
                Project targetProject = projectProperty.getLeft();
                String targetPropertyName = projectProperty.getRight();

                if (projectProperty == null) {
                    Dependency d = locatedDependency;
                    ChannelPluginLogger.LOGGER.errorf(
                            "Unable to upgrade %s:%s:%s to '%s', can't locate property '%s' in POM file %s",
                            d.getGroupId(), d.getArtifactId(), d.getVersion(), newVersion,
                            versionPropertyName, pmeProject.getPom().getPath());
                    continue;
                }
                if (isIgnoredProperty(targetPropertyName)) {
                    getLog().info(String.format("Ignoring property '%s' (ignored prefix)", targetPropertyName));
                    continue;
                }

                if (upgradedProperties.containsKey(projectProperty)) {
                    if (!upgradedProperties.get(projectProperty).equals(newVersion)) {
                        // property has already been changed to different value
                        Dependency d = locatedDependency;
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
                PomManipulator targetManipulator = manipulators.get(
                        Pair.of(targetProject.getGroupId(), targetProject.getArtifactId()));
                targetManipulator.overrideProperty(targetPropertyName, newVersion);
            } else { // dependency version is inlined in version element, can be directly overriden
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
        Map<ArtifactRef, List<ProjectRef>> undeclaredDependencies = collectUndeclaredDependencies();

        List<Map.Entry<ArtifactRef, List<ProjectRef>>> dependenciesToInject = undeclaredDependencies.entrySet().stream()
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                // filter only deps that have a stream defined in the channel
                .filter(entry -> {
                    // verify if the artifact from the project dependency tree is modified by the channel
                    ArtifactRef a = entry.getKey();
                    try {
                        String newVersion = channelSession.findLatestMavenArtifactVersion(a.getGroupId(), a.getArtifactId(),
                                a.getType(), a.getClassifier(), a.getVersionString());
                        return !newVersion.equals(a.getVersionString());
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
        for (Map.Entry<ArtifactRef, List<ProjectRef>> entry : dependenciesToInject) {
            ArtifactRef a = entry.getKey();
            try {
                String newVersion = channelSession.findLatestMavenArtifactVersion(a.getGroupId(), a.getArtifactId(),
                        a.getType(), a.getClassifier(), a.getVersionString());
                if (!newVersion.equals(a.getVersionString())) {
                    SimpleArtifactRef newDependency = new SimpleArtifactRef(a.getGroupId(), a.getArtifactId(), newVersion,
                            a.getType(), a.getClassifier());
                    getLog().info(String.format("Injecting undeclared dependency: %s", newDependency));
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

        if (projectDependencies.size() == 0) {
            getLog().debug("No dependencies found in " + pmeProject.getArtifactId());
        }

        return projectDependencies;
    }

    private List<Pair<Dependency, String>> findDepenenciesToUpgrade(
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
                getLog().warn("Resolved dependency has null version: " + artifactRef);
                continue;
            }
            if (VersionUtils.isProperty(artifactRef.getVersionString())) {
                // didn't manage to resolve dependency version
                getLog().warn("Resolved dependency has version with property: " + artifactRef);
                continue;
            }


            try {
                String channelVersion = channelSession.findLatestMavenArtifactVersion(artifactRef.getGroupId(),
                        artifactRef.getArtifactId(), artifactRef.getType(), artifactRef.getClassifier(),
                        artifactRef.getVersionString());

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

        if ((StringUtils.isNotBlank(manifestFile) || StringUtils.isNotBlank(manifestGAV)) && remoteRepositories.isEmpty()) {
            // throw new MojoExecutionException("The remoteRepositories property is mandatory when manifest is given.");
        }

        try {
            if (StringUtils.isNotBlank(channelFile)) {
                Path channelFilePath = Path.of(channelFile);
                if (!channelFilePath.isAbsolute()) {
                    channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
                }
                getLog().info("Reading channel file " + channelFilePath);
                channel = ChannelMapper.from(channelFilePath.toUri().toURL());
            } else if (StringUtils.isNotBlank(channelGAV)) {
                channel = resolveChannel(channelGAV);
            } else if (StringUtils.isNotBlank(manifestFile)) {
                URL manifestUrl = Path.of(manifestFile).toUri().toURL();
                channel = new Channel("a-channel", null, null, null, new ChannelManifestCoordinate(manifestUrl), null, null);
            } else if (StringUtils.isNotBlank(manifestGAV)) {
                ProjectVersionRef gav = SimpleProjectVersionRef.parse(manifestGAV);
                channel = new Channel("a-channel", null, null, null, new ChannelManifestCoordinate(gav.getGroupId(), gav.getArtifactId(), gav.getVersionString()), null,
                        null);
            } else {
                throw new MojoExecutionException("No channel or manifest specified.");
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Can't parse the channel or manifest file path", e);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Failed to resolve the channel artifact", e);
        }
    }

    /**
     * Resolves channel file specified by a GAV.
     * <p>
     * This searches in all remote repositories specified in the processed project and the settings.xml.
     */
    private Channel resolveChannel(String gavString) throws ArtifactResolutionException, MalformedURLException {
        ProjectVersionRef gav = SimpleProjectVersionRef.parse(gavString);
        DefaultArtifact artifact = new DefaultArtifact(gav.getGroupId(), gav.getArtifactId(), CHANNEL_CLASSIFIER,
                CHANNEL_EXTENSION, gav.getVersionString());

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(mavenProject.getRemoteProjectRepositories());
        ArtifactResult artifactResult = repositorySystem.resolveArtifact(mavenSession.getRepositorySession(), request);
        getLog().info(String.format("Channel file resolved from %s in repository %s",
                artifact, artifactResult.getRepository().getId()));
        File channelFile = artifactResult.getArtifact().getFile();
        return ChannelMapper.from(channelFile.toURI().toURL());
    }

    private final Channel overrideRemoteRepositories(Channel channel, List<String> repositories) {
        return new Channel(channel.getName(), channel.getDescription(), channel.getVendor(), createRepositories(repositories),
                channel.getManifestCoordinate(), channel.getBlocklistCoordinate(), channel.getNoStreamStrategy());
    }

    private final List<Repository> createRepositories(List<String> userRepositories) {
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
     * Collects transitive dependencies from all project's modules.
     * <p>
     * This has to be called after all submodules has been processed (so that all declared dependencies has been
     * collected).
     */
    private Map<ArtifactRef, List<ProjectRef>> collectUndeclaredDependencies() throws DependencyGraphBuilderException {
        // This performs a traversal of a dependency tree of all submodules in the project. All discovered dependencies
        // that are not directly declared in the project are considered transitive dependencies.
        Map<ArtifactRef, List<ProjectRef>> undeclaredDependencies = new HashMap<>();
        for (MavenProject module: mavenProject.getCollectedProjects()) {
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());
            buildingRequest.setProject(module);
            DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);
            CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
            rootNode.accept(visitor);
            visitor.getNodes().forEach(node -> {
                ArtifactRef artifact = toArtifactRef(node.getArtifact());
                if (!declaredDependencies.contains(artifact.asProjectRef())) {
                    List<ProjectRef> exclusions = toProjectRefs(node.getExclusions());

                    /*if ("httpclient".equals(artifact.getArtifactId())) {
                        getLog().warn(String.format("Found undeclared dependency %s:%s in module %s", artifact.getGroupId(),
                                artifact.getArtifactId(), module.getArtifactId()));
                        getLog().warn(String.format("  Exclusions:\n  %s",
                                exclusions.stream().map(a -> a.getGroupId() + ":" + a.getArtifactId()).collect(Collectors.joining("\n  "))));
                    }*/

                    List<ProjectRef> previousExclusions = undeclaredDependencies.put(artifact, exclusions);
                    if (previousExclusions != null) {
                        for (ProjectRef previousExclusion: previousExclusions) {
                            if (!exclusions.contains(previousExclusion)) {
                                exclusions.add(previousExclusion);
                            }
                        }
                    }
                }
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

}
