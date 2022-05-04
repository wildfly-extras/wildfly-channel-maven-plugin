package org.wildfly.channeltools.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.Stream;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channeltools.resolver.ChannelBuilder;
import org.wildfly.channeltools.resolver.DefaultMavenVersionsResolverFactory;
import picocli.CommandLine;

/**
 * This command takes a channel containing version patterns, and resolves it into a channel containing only concrete
 * versions.
 */
public class ResolveChannelCommand implements Runnable {

    @CommandLine.ArgGroup
    ChannelInputGroup channelInputGroup = new ChannelInputGroup();

    @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "Output channel file path")
    File outputChannelFile;

    @CommandLine.ArgGroup
    ResolutionArgGroup resolutionArgGroup = new ResolutionArgGroup();

    @Override
    public void run() {
        DefaultMavenVersionsResolverFactory resolverFactory = new DefaultMavenVersionsResolverFactory(
                resolutionArgGroup.remoteRepositories, resolutionArgGroup.localRepository,
                resolutionArgGroup.disableTlsVerification);

        Channel channel = new ChannelBuilder(resolverFactory)
                .setChannelGav(channelInputGroup.gav)
                .setChannelFile(channelInputGroup.file)
                .build();

        try (ChannelSession channelSession = new ChannelSession(Collections.singletonList(channel), resolverFactory)) {
            for (Stream stream : channel.getStreams()) {
                try {
                    MavenArtifact resolvedArtifact = channelSession.resolveMavenArtifact(stream.getGroupId(),
                            stream.getArtifactId(), "pom", null);
                    CliLogger.LOGGER.infof("Stream %s:%s:%s version resolved to %s",
                            stream.getGroupId(), stream.getArtifactId(),
                            stream.getVersionPattern() != null ? stream.getVersionPattern() : stream.getVersion(),
                            resolvedArtifact.getVersion());
                } catch (UnresolvedMavenArtifactException e) {
                    CliLogger.LOGGER.errorf(e, "Couldn't resolve stream version for %s:%s:%s",
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
        @CommandLine.Option(names = {"--channel-file"}, required = true, description = "Channel file path")
        File file;
        @CommandLine.Option(names = {"--channel-gav"}, required = true, description = "Channel file GAV")
        String gav;
    }
}
