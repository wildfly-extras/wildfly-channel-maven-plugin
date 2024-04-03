package org.wildfly.channelplugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
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
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.channelplugin.utils.IOUtils;

import javax.inject.Inject;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractChannelMojo extends AbstractMojo {

    /**
     * Path to the channel definition file on a local filesystem.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(property = "channelFile")
    String channelFile;

    /**
     * Path to the channel manifest file on a local filesystem.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(property = "manifestFile")
    String manifestFile;

    /**
     * GAV of an artifact than contains the channel file.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(property = "channelGAV")
    String channelGAV;

    /**
     * GAV of an artifact than contains the channel file.
     * <p>
     * Exactly one of 'channelFile', 'manifestFile', 'channelGAV', 'manifestGAV' can be set.
     */
    @Parameter(property = "manifestGAV")
    String manifestGAV;

    /**
     * Comma separated list of remote repositories URLs, that should be used to resolve artifacts.
     */
    @Parameter(property = "remoteRepositories")
    List<String> remoteRepositories;

    /**
     * Local repository path.
     */
    @Parameter(property = "localRepository")
    String localRepositoryPath;

    @Inject
    MavenSession mavenSession;

    @Inject
    MavenProject mavenProject;

    @Inject
    RepositorySystem repositorySystem;

    @Inject
    PomIO pomIO;

    protected List<Channel> channels = new ArrayList<>();
    protected ChannelSession channelSession;

    protected void initChannelSession() throws MojoExecutionException {
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
                String[] paths = channelFile.split(",");
                for (String path: paths) {
                    Path channelFilePath = Path.of(path);
                    if (!channelFilePath.isAbsolute()) {
                        channelFilePath = Path.of(mavenSession.getExecutionRootDirectory()).resolve(channelFilePath);
                    }
                    getLog().info("Reading channel file " + channelFilePath);
                    channels.add(ChannelMapper.from(channelFilePath.toUri().toURL()));
                }
            } else if (StringUtils.isNotBlank(channelGAV)) {
                String[] gavs = channelGAV.split(",");
                for (String gav: gavs) {
                    channels.addAll(resolveChannelsFromGav(gav));
                }
            } else if (StringUtils.isNotBlank(manifestFile)) {
                String[] paths = manifestFile.split(",");
                for (String path: paths) {
                    URL manifestUrl = Path.of(path).toUri().toURL();
                    ChannelManifestCoordinate coordinate = new ChannelManifestCoordinate(manifestUrl);
                    channels.add(new Channel("a-channel", null, null, null, coordinate, null, null));
                }
            } else if (StringUtils.isNotBlank(manifestGAV)) {
                String[] gavs = manifestGAV.split(",");
                for (String gav: gavs) {
                    ChannelManifestCoordinate coordinate = toManifestCoordinate(gav);
                    // Compose list of repositories to look for the manifest as a union of the remoteRepositories property
                    // and repositories from the project pom.xml.
                    List<Repository> repositories = mavenProject.getRemoteProjectRepositories().stream()
                            .map(rr -> new Repository(rr.getId(), rr.getUrl()))
                            .collect(Collectors.toList());
                    repositories.addAll(createRepositories(remoteRepositories));
                    channels.add(new Channel("a-channel", null, null, repositories, coordinate, null, null));
                }
            } else {
                throw new MojoExecutionException("No channel or manifest specified.");
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Can't parse the channel or manifest file path", e);
        }

        if (!remoteRepositories.isEmpty()) {
            // TODO: Include remote repositories defined in the project pom.xml?
            channels = overrideRemoteRepositories(channels, remoteRepositories);
        }

        if (StringUtils.isBlank(localRepositoryPath)) {
            try {
                localRepositoryPath = IOUtils.createTemporaryCache();
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot create local maven cache", e);
            }
        }

        final DefaultRepositorySystemSession repositorySystemSession = MavenRepositorySystemUtils.newSession();
        final LocalRepository localRepository = new LocalRepository(localRepositoryPath);
        final LocalRepositoryManager localRepoManager = repositorySystem.newLocalRepositoryManager(repositorySystemSession,
                localRepository);
        repositorySystemSession.setLocalRepositoryManager(localRepoManager);

        channelSession = new ChannelSession(channels, new VersionResolverFactory(repositorySystem, repositorySystemSession));
    }

    protected List<Channel> resolveChannelsFromGav(String gavString) {
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

    protected static ChannelCoordinate toChannelCoordinate(String gavString) {
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

    protected static ChannelManifestCoordinate toManifestCoordinate(String gavString) {
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

    protected static List<Channel> overrideRemoteRepositories(List<Channel> channels, List<String> repositories) {
        List<Channel> updatedChannels = new ArrayList<>(channels.size());
        for (Channel channel: channels) {
            updatedChannels.add(new Channel(channel.getName(), channel.getDescription(), channel.getVendor(), createRepositories(repositories),
                    channel.getManifestCoordinate(), channel.getBlocklistCoordinate(), channel.getNoStreamStrategy()));
        }
        return updatedChannels;
    }

    protected static List<Repository> createRepositories(List<String> userRepositories) {
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

}
