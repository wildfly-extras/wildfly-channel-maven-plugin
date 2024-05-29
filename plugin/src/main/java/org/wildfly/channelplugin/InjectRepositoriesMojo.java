package org.wildfly.channelplugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.RepositoryBase;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.io.PomIO;
import org.jboss.logging.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channelplugin.utils.PMEUtils;

import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inject repositories into project pom.xml. Either use `-DfromChannelFile=path/to/channel.yaml` to inject repositories
 * referenced in given channel file, or use `-Drepositories=repoID::repoURL,...` to inject specified repositories.
 */
@Mojo(name = "inject-repositories", requiresDirectInvocation = true)
public class InjectRepositoriesMojo extends AbstractMojo {

    private static final Logger logger = Logger.getLogger(InjectRepositoriesMojo.class);

    private static final String CENTRAL_URL = "https://repo.maven.apache.org/maven2";

    /**
     * Channel file path to extract repositories from.
     */
    @Parameter(property = "fromChannelFile")
    String fromChannelFile;

    /**
     * Comma separated list of "repositoryID::repositoryURL" strings
     */
    @Parameter(property = "repositories")
    List<String> repositories;

    @Inject
    MavenSession mavenSession;

    @Inject
    PomIO pomIO;

    @Inject
    MavenProject mavenProject;

    @Override
    public void execute() throws MojoExecutionException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

        if (StringUtils.isBlank(fromChannelFile) && (repositories == null || repositories.isEmpty())) {
            throw new MojoExecutionException("Exactly one of `fromChannelFile` and `repositories` parameters is needed.");
        }
        if (StringUtils.isNotBlank(fromChannelFile) && repositories != null && !repositories.isEmpty()) {
            throw new MojoExecutionException("Exactly one of `fromChannelFile` and `repositories` parameters is needed.");
        }

        final Map<String, String> repositoriesToInject = new HashMap<>();

        if (StringUtils.isNotBlank(fromChannelFile)) {
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
            channels.stream().flatMap(c -> c.getRepositories().stream()).forEach(repository -> {
                if (!repositoriesToInject.containsValue(repository.getUrl())) {
                    repositoriesToInject.put(repository.getId(), repository.getUrl());
                }
            });
        } else if (repositories != null && !repositories.isEmpty()) {
            AbstractChannelMojo.createRepositories(repositories).forEach(r -> {
                repositoriesToInject.put(r.getId(), r.getUrl());
            });
        }

        try {
            List<Project> projects = PMEUtils.parsePmeProjects(pomIO, mavenProject);
            Project rootProject = PMEUtils.findRootProject(projects);
            getLog().info("Root project: " + rootProject.getArtifactId());
            PomManipulator manipulator = new PomManipulator(rootProject);
            insertRepositories(rootProject, manipulator, repositoriesToInject);
            manipulator.writePom();
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Can't parse project POM files", e);
        }
    }

    static void insertRepositories(Project project, PomManipulator manipulator, Map<String, String> repositories) {
        Set<String> existingRepositories = project.getModel().getRepositories().stream()
                .map(RepositoryBase::getUrl)
                .collect(Collectors.toSet());
        existingRepositories.add(CENTRAL_URL);

        repositories.forEach((id, url) -> {
            if (!existingRepositories.contains(url)) {
                try {
                    logger.infof("Inserting repository %s", url);
                    manipulator.injectRepository(id, url);
                } catch (XMLStreamException e) {
                    ChannelPluginLogger.LOGGER.errorf("Failed to inject repository: %s", e.getMessage());
                }
            } else {
                logger.infof("Repository with URL %s is already present.", url);
            }
        });

        Set<String> existingPluginRepositories = project.getModel().getPluginRepositories().stream()
                .map(RepositoryBase::getUrl)
                .collect(Collectors.toSet());
        existingPluginRepositories.add(CENTRAL_URL);

        repositories.forEach((id, url) -> {
            if (!existingPluginRepositories.contains(url)) {
                try {
                    logger.infof("Inserting plugin repository %s", url);
                    manipulator.injectPluginRepository(id, url);
                } catch (XMLStreamException e) {
                    ChannelPluginLogger.LOGGER.errorf("Failed to inject plugin repository: %s", e.getMessage());
                }
            } else {
                logger.infof("Plugin repository with URL %s is already present.", url);
            }
        });
    }

}
