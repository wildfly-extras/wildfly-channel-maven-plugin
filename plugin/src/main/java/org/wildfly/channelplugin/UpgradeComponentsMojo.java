package org.wildfly.channelplugin;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channelplugin.manipulation.PomWriter;
import org.wildfly.channelplugin.prospero.MavenSessionManager;
import org.wildfly.channelplugin.prospero.WfChannelMavenResolverFactory;

/**
 * This tasks overrides dependencies versions according to provided channel file.
 *
 * One of following properties needs to be set:
 * <li>`channelFile` to use a channel file located on a local file system,</li>
 * <li>`channelGAV` to lookup an artifact containing the channel file.</li>
 */
@Mojo(name = "upgrade", requiresProject = true, requiresDirectInvocation = true)
public class UpgradeComponentsMojo extends AbstractMojo {

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
    ChannelSession session;

    private void init() throws MojoExecutionException{
        channel = loadChannel();
        MavenSessionManager mavenSessionManager;
        if (StringUtils.isNotBlank(localRepository)) {
            mavenSessionManager = new MavenSessionManager(Path.of(localRepository));
        } else {
            mavenSessionManager = new MavenSessionManager();
        }
        session = new ChannelSession(Collections.singletonList(channel),
                new WfChannelMavenResolverFactory(mavenSessionManager, remoteRepositories));
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
        List<Map.Entry<ArtifactRef, Dependency>> dependencies = new ArrayList<>();
        dependencies.addAll(project.getResolvedManagedDependencies(manipulationSession).entrySet());
        dependencies.addAll(project.getResolvedDependencies(manipulationSession).entrySet());

        if (dependencies.size() == 0) {
            getLog().info("No dependencies found in " + project.getArtifactId());
        }

        ArrayList<MavenArtifact> dependenciesToUpgrade = new ArrayList<>();
        for (Map.Entry<ArtifactRef, Dependency> entry : dependencies) {
            ArtifactRef artifactRef = entry.getKey();

            if (artifactRef.getVersionStringRaw() == null) {
                getLog().info("Null version: " + artifactRef);
            }

            try {
                MavenArtifact mavenArtifact = session.resolveLatestMavenArtifact(artifactRef.getGroupId(),
                        artifactRef.getArtifactId(), artifactRef.getType(), artifactRef.getClassifier());

                if (!mavenArtifact.getVersion().equals(artifactRef.getVersionString())) {
                    getLog().info("Overriding dependency version " + artifactRef.getGroupId()
                            + ":" + artifactRef.getArtifactId() + ":" + artifactRef.getVersionString()
                            + " to version " + mavenArtifact.getVersion());
                    dependenciesToUpgrade.add(mavenArtifact);
                }
            } catch (UnresolvedMavenArtifactException e) {
                getLog().debug("Can't resolve artifact: " + artifactRef);
            }
        }

        PomWriter.manipulatePom(project, dependenciesToUpgrade);
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
