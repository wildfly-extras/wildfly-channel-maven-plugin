package org.wildfly.channelplugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.RepositoryBase;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.io.PomIO;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channelplugin.manipulation.PomManipulator;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This Mojo takes a channel file, extracts Maven repositories used by channels in the file, and injects those
 * repositories into the project POM, so that Maven would use them to download dependencies from.
 */
@Mojo(name = "inject-repositories", requiresProject = true, requiresDirectInvocation = true)
public class InjectRepositoriesMojo extends AbstractMojo {

    /**
     * Channel file path to extract repositories from.
     */
    @Parameter(required = true, property = "fromChannelFile")
    String fromChannelFile;

    @Inject
    MavenSession mavenSession;

    @Inject
    MavenProject mavenProject;

    @Inject
    PomIO pomIO;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

        Path channelFilePath = Path.of(fromChannelFile);
        if (!channelFilePath.isAbsolute()) {
            channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
        }

        getLog().info("Reading channel file " + channelFilePath);
        List<Channel> channels;
        try (InputStream is = channelFilePath.toUri().toURL().openStream()) {
            channels = ChannelMapper.fromString(new String(is.readAllBytes()));
        } catch (IOException e) {
            throw new MojoExecutionException("Can't read channel file", e);
        }

        try {
            Project project = parseRootProject();
            getLog().info("Root project: " + project.getArtifactId());
            PomManipulator manipulator = new PomManipulator(project);

            Set<String> existingRepositories = project.getModel().getRepositories().stream()
                    .map(RepositoryBase::getUrl)
                    .collect(Collectors.toSet());

            channels.stream()
                    .flatMap(c -> c.getRepositories().stream()).distinct()
                    .forEach(r -> {
                        if (!existingRepositories.contains(r.getUrl())) {
                            try {
                                getLog().info("Inserting repository " + r.getUrl());
                                manipulator.injectRepository(r.getId(), r.getUrl());
                            } catch (XMLStreamException e) {
                                ChannelPluginLogger.LOGGER.errorf("Failed to inject repository: %s", e.getMessage());
                            }
                        } else {
                            getLog().info(String.format("Repository with URL %s is already present.", r.getUrl()));
                        }
                    });

            manipulator.writePom();
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Can't parse project POM files", e);
        }
    }


    /**
     * Returns PME representation of current project module and its submodules.
     */
    private Project parseRootProject() throws ManipulationException {
        return pomIO.parseProject(mavenProject.getModel().getPomFile()).stream().filter(Project::isExecutionRoot).findFirst().get();
    }

}
