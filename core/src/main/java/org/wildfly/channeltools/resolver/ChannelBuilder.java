package org.wildfly.channeltools.resolver;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.lang.StringUtils;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.spi.MavenVersionsResolver;

public class ChannelBuilder {

    private static final String CHANNEL_CLASSIFIER = "channel";
    private static final String CHANNEL_EXTENSION = "yaml";

    private final DefaultMavenVersionsResolverFactory resolverFactory;
    private String inputGav;
    private File inputFile;

    public ChannelBuilder(DefaultMavenVersionsResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    public ChannelBuilder setChannelGav(String inputGav) {
        this.inputGav = inputGav;
        return this;
    }

    public ChannelBuilder setChannelFile(File inputFile) {
        this.inputFile = inputFile;
        return this;
    }

    public Channel build() {
        URL channelUrl;
        try {
            // the channel file is either available locally or need to be obtained from maven repo
            File channelFile;
            if (inputFile != null && StringUtils.isNotBlank(inputGav)) {
                throw new IllegalStateException("Either input file or input GAV must be given, not both.");
            } else if (inputFile != null) {
                channelFile = inputFile;
            } else if (StringUtils.isNotBlank(inputGav)) {
                ProjectVersionRef gav = SimpleProjectVersionRef.parse(inputGav);
                MavenVersionsResolver resolver = resolverFactory.create();
                channelFile = resolver.resolveArtifact(gav.getGroupId(), gav.getArtifactId(),
                        CHANNEL_EXTENSION, CHANNEL_CLASSIFIER, gav.getVersionString());
            } else {
                throw new RuntimeException("Neither channel file nor channel GAV was specified.");
            }

            channelUrl = channelFile.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Couldn't convert the channel file to a URL", e);
        } catch (UnresolvedMavenArtifactException e) {
            throw new RuntimeException("Couldn't obtain the channel file from a Maven repository", e);
        }

        return ChannelMapper.from(channelUrl);
    }
}
