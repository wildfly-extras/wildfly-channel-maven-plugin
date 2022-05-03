package org.wildfly.channeltools.cli;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.Stream;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.spi.MavenVersionsResolver;
import org.wildfly.channeltools.resolver.DefaultMavenVersionsResolverFactory;
import picocli.CommandLine;

/**
 * This command takes a channel containing version patterns, and resolves it into a channel containing only concrete
 * versions.
 */
public class ResolveChannelCommand implements Runnable {

    private static final String CHANNEL_CLASSIFIER = "channel";
    private static final String CHANNEL_EXTENSION = "yaml";
    private static final String LOCAL_MAVEN_REPO = System.getProperty("user.home") + "/.m2/repository";

    @CommandLine.ArgGroup
    ChannelInputGroup channelInputGroup;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "Output channel file path")
    File outputChannelFile;

    @CommandLine.Option(names = {"-r", "--remote-repo"},
            description = "Remote maven repository to use when resolving available versions")
    List<String> remoteRepositories = new ArrayList<>();

    @CommandLine.Option(names = {"--local-repo"}, description = "Local maven repository")
    String localRepository = LOCAL_MAVEN_REPO;

    @CommandLine.Option(names = {"--disable-tls-verification"}, description = "Disable TLS certificate verification")
    boolean disableTlsVerification;

    @Override
    public void run() {
        DefaultMavenVersionsResolverFactory resolverFactory = new DefaultMavenVersionsResolverFactory(
                remoteRepositories, localRepository, disableTlsVerification);

        URL channelUrl;
        try {
            // the channel file is either available locally or need to be obtained from maven repo
            File channelFile;
            if (channelInputGroup != null && channelInputGroup.file != null) {
                channelFile = channelInputGroup.file;
            } else if (channelInputGroup != null && StringUtils.isNotBlank(channelInputGroup.gav)) {
                ProjectVersionRef gav = SimpleProjectVersionRef.parse(channelInputGroup.gav);
                MavenVersionsResolver resolver = resolverFactory.create();
                channelFile = resolver.resolveArtifact(gav.getGroupId(), gav.getArtifactId(),
                        CHANNEL_EXTENSION, CHANNEL_CLASSIFIER, gav.getVersionString());
            } else {
                throw new RuntimeException("Either --channel-file or --channel-gav must be set.");
            }

            channelUrl = channelFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Couldn't convert the channel file to a URL", e);
        } catch (UnresolvedMavenArtifactException e) {
            throw new RuntimeException("Couldn't obtain the channel file from a Maven repository", e);
        }

        Channel channel = ChannelMapper.from(channelUrl);
        try (ChannelSession channelSession = new ChannelSession(Collections.singletonList(channel), resolverFactory)) {
            for (Stream stream : channel.getStreams()) {
                try {
                    MavenArtifact resolvedArtifact = channelSession.resolveMavenArtifact(stream.getGroupId(),
                            stream.getArtifactId(), "pom", null);
                    ChannelLogger.LOGGER.infof("Stream %s:%s:%s version resolved to %s",
                            stream.getGroupId(), stream.getArtifactId(),
                            stream.getVersionPattern() != null ? stream.getVersionPattern() : stream.getVersion(),
                            resolvedArtifact.getVersion());
                } catch (UnresolvedMavenArtifactException e) {
                    ChannelLogger.LOGGER.errorf(e, "Couldn't resolve stream version for %s:%s:%s",
                            stream.getGroupId(), stream.getArtifactId(),
                            stream.getVersionPattern() != null ? stream.getVersionPattern() : stream.getVersion());
                }
            }

            Channel recordedChannel = channelSession.getRecordedChannel();
            Channel channelToWrite = new Channel(channel.getName(), channel.getDescription(),
                    channel.getVendor(), channel.getChannelRequirements(), recordedChannel.getStreams());
            try {
                String yamlContent = ChannelMapper.toYaml(channelToWrite);
                Files.write(outputChannelFile.toPath(), yamlContent.getBytes());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write effective channel file", e);
            }
        }
    }

    static class ChannelInputGroup {
        @CommandLine.Option(names = {"-f", "--channel-file"}, required = true, description = "Channel file path")
        File file;
        @CommandLine.Option(names = {"-g", "--channel-gav"}, required = true, description = "Channel file GAV")
        String gav;
    }
}
