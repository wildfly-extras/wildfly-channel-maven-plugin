package org.wildfly.channelplugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.RepositoryBase;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.jboss.logging.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channelplugin.manipulation.PomManipulator;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This Mojo takes a channel file, extracts Maven repositories used by channels in the file, and injects those
 * repositories into the project POM, so that Maven would use them to download dependencies from.
 */
@Mojo(name = "inject-repositories", requiresProject = true, requiresDirectInvocation = true)
public class InjectRepositoriesMojo extends AbstractChannelMojo {

    private static final Logger logger = Logger.getLogger(InjectRepositoriesMojo.class);

    /**
     * Channel file path to extract repositories from.
     */
    @Parameter(required = true, property = "fromChannelFile")
    String fromChannelFile;

    @Inject
    MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException {
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
            List<Project> projects = parsePmeProjects();
            Project rootProject = findRootProject(projects);
            getLog().info("Root project: " + rootProject.getArtifactId());
            PomManipulator manipulator = new PomManipulator(rootProject);
            insertRepositories(rootProject, manipulator, channels);
            manipulator.writePom();
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Can't parse project POM files", e);
        }
    }

    static void insertRepositories(Project project, PomManipulator manipulator, Collection<Channel> channels) {

        Set<String> existingRepositories = project.getModel().getRepositories().stream()
                .map(RepositoryBase::getUrl)
                .collect(Collectors.toSet());

        channels.stream()
                .flatMap(c -> c.getRepositories().stream()).distinct()
                .forEach(r -> {
                    if (!existingRepositories.contains(r.getUrl())) {
                        try {
                            logger.infof("Inserting repository %s", r.getUrl());
                            manipulator.injectRepository(r.getId(), r.getUrl());
                        } catch (XMLStreamException e) {
                            ChannelPluginLogger.LOGGER.errorf("Failed to inject repository: %s", e.getMessage());
                        }
                    } else {
                        logger.infof("Repository with URL %s is already present.", r.getUrl());
                    }
                });
    }

}
