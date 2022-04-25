package org.wildfly.channelplugin;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.Stream;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channelplugin.channel.ComparableMavenArtifact;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channelplugin.resolution.DefaultMavenVersionsResolverFactory;

/**
 * This tasks overrides dependencies versions according to provided channel file.
 *
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
     * Alternative for the `channelGAV` parameter.
     */
    @Parameter(readonly = true, required = false, property = "channelFile")
    String channelFile;

    /**
     * GAV of an artifact than contains the channel file.
     * <p>
     * Alternative for the `channelFile` parameter.
     */
    @Parameter(readonly = true, required = false, property = "channelGAV")
    String channelGAV;

    /**
     * Comma separated list of remote repositories URLs, that should be used to resolve artifacts.
     */
    @Parameter(readonly = true, required = false, property = "remoteRepositories")
    List<String> remoteRepositories;

    /**
     * Local repository path.
     */
    @Parameter(readonly = true, property = "localRepository")
    String localRepository;

    /**
     * Inject dependencies from channel definition that are missing in the project (supposedly transitive dependencies)
     * into dependency management section?
     *
     * Experimental
     */
    @Parameter(readonly = true, property = "injectMissingDependencies", defaultValue = "false")
    boolean injectMissingDependencies;

    /**
     * Disables TLS verification, in case the remote maven repository uses a self-signed or otherwise
     * invalid certificate.
     */
    @Parameter(readonly = true, property = "disableTlsVerification", defaultValue = "false")
    boolean disableTlsVerification;

    /**
     * List of G:As that should not be upgraded in the project.
     */
    @Parameter(readonly = true, property = "ignoreGAs", defaultValue = "")
    List<String> ignoreGAs;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    MavenSession mavenSession;

    @Parameter(defaultValue = "${basedir}", readonly = true)
    File basedir;

    @Inject
    PomIO pomIO;

    @Inject
    ManipulationSession manipulationSession;

    Channel channel;
    ChannelSession channelSession;
    List<ProjectRef> ignoredStreams;

    private void init() throws MojoExecutionException {
        if (localRepository == null) {
            localRepository = LOCAL_MAVEN_REPO;
        }

        channel = loadChannel();
        channelSession = new ChannelSession(Collections.singletonList(channel),
                new DefaultMavenVersionsResolverFactory(remoteRepositories, localRepository, disableTlsVerification));

        ignoredStreams = new ArrayList<>();
        for (String ga: ignoreGAs) {
            ignoredStreams.add(SimpleProjectRef.parse(ga));
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        init();

        try {
            processProject(parseProject());
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Project parsing failed", e);
        }
    }

    /**
     * Processes single maven module
     */
    private void processProject(Project project) throws ManipulationException {
        Map<ArtifactRef, Dependency> projectDependencies = new HashMap<>();
        projectDependencies.putAll(project.getResolvedManagedDependencies(manipulationSession));
        projectDependencies.putAll(project.getResolvedDependencies(manipulationSession));

        if (projectDependencies.size() == 0) {
            getLog().info("No dependencies found in " + project.getArtifactId());
        }

        ArrayList<MavenArtifact> dependenciesToUpgrade = new ArrayList<>();
        for (ArtifactRef artifactRef : projectDependencies.keySet()) {

            if (ignoredStreams.contains(artifactRef.asProjectRef())) {
                getLog().info("Ignoring stream " + artifactRef.getGroupId() + ":" + artifactRef.getArtifactId());
                continue;
            }

            if (artifactRef.getVersionStringRaw() == null) {
                getLog().warn("Null version: " + artifactRef);
            }

            try {
                MavenArtifact mavenArtifact = channelSession.resolveMavenArtifact(artifactRef.getGroupId(),
                        artifactRef.getArtifactId(), artifactRef.getType(), artifactRef.getClassifier());

                if (!mavenArtifact.getVersion().equals(artifactRef.getVersionString())) {
                    getLog().info("Overriding dependency version " + artifactRef.getGroupId()
                            + ":" + artifactRef.getArtifactId() + ":" + artifactRef.getVersionString()
                            + " to version " + mavenArtifact.getVersion());
                    dependenciesToUpgrade.add(mavenArtifact);
                }
            } catch (UnresolvedMavenArtifactException e) {
                getLog().debug("Can't resolve artifact: " + artifactRef, e);
            }
        }

        // Following is an experiment to inject missing dependencies into the project's dependencyManagement section.
        // The goal is to have a way to override transitive dependencies, this is a simplistic approach. We are missing
        // metadata about the dependency type and classifier.
        ArrayList<MavenArtifact> dependenciesToInject = new ArrayList<>();
        if (injectMissingDependencies) {
            for (Stream stream : channel.getStreams()) {
                try {
                    MavenArtifact mavenArtifact = channelSession.resolveMavenArtifact(stream.getGroupId(),
                            stream.getArtifactId(), "jar", null);
                    SimpleArtifactRef artifactRef = new SimpleArtifactRef(mavenArtifact.getGroupId(),
                            mavenArtifact.getArtifactId(), mavenArtifact.getVersion(), mavenArtifact.getExtension(),
                            mavenArtifact.getClassifier());
                    ComparableMavenArtifact comparableMavenArtifact = new ComparableMavenArtifact(mavenArtifact);

                    if (!projectDependencies.containsKey(artifactRef)
                            && !dependenciesToUpgrade.contains(comparableMavenArtifact)
                            && !ignoredStreams.contains(artifactRef.asProjectRef())) {
                        dependenciesToInject.add(mavenArtifact);
                    }
                } catch (UnresolvedMavenArtifactException e) {
                    getLog().debug("Can't resolve latest stream version: "
                            + stream.getGroupId() + ":" + stream.getArtifactId(), e);
                }
            }
        }

        try {
            String recordedChannelYaml = ChannelMapper.toYaml(channelSession.getRecordedChannel());
            Files.write(Path.of(project.getPom().getParent(), "recorded-channel.yaml"), recordedChannelYaml.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Couldn't write recorder channel", e);
        }
        PomManipulator pomWriter = new PomManipulator(project);
        pomWriter.overrideDependenciesVersions(dependenciesToUpgrade);
        pomWriter.injectDependencies(dependenciesToInject);
        pomWriter.writePom();
    }

    private Channel loadChannel() throws MojoExecutionException {
        if (channelFile != null) {
            try {
                Path channelFilePath = Path.of(channelFile);
                if (!channelFilePath.isAbsolute()) {
                    channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
                }
                getLog().info("Reading channel file " + channelFilePath);
                return ChannelMapper.from(channelFilePath.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Could not read channelFile", e);
            }
        } else if (StringUtils.isNotBlank(channelGAV)) {
            // download the maven artifact with the channel
            throw new MojoExecutionException("Not implemented yet, the channelFile parameter must be set.");
        } else {
            throw new MojoExecutionException("Either channelFile or channelGAV parameter needs to be set.");
        }
    }

    /**
     * This returns a PME representation of currently processed Maven module.
     *
     * PME (POM Manipulation Extension) is subproject of the PNC (Project Newcastle) productization system. PME does
     * a very similar job to what this Maven module does.
     */
    private Project parseProject() throws MojoExecutionException, ManipulationException {
        List<Project> projects = pomIO.parseProject(project.getModel().getPomFile());
        List<Project> roots = projects.stream().filter(Project::isExecutionRoot)
                .collect(Collectors.toList());
        if (roots.size() != 1) {
            throw new MojoExecutionException("Couldn't determine the execution root project, candidates are: ["
                    + roots.stream().map(Project::getArtifactId).collect(Collectors.joining(", "))
                    + "]");
        }
        return roots.get(0);
    }

}
