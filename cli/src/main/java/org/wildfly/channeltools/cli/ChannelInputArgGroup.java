package org.wildfly.channeltools.cli;

import java.io.File;

import org.apache.commons.lang.StringUtils;
import picocli.CommandLine;

public class ChannelInputArgGroup {

    @CommandLine.Option(names = {"--channel-file"},
            description = "Channel file path on local filesystem")
    File file;

    @CommandLine.Option(names = {"--channel-gav"},
            description = "Channel GAV, will be downloaded from maven repository")
    String gav;

    public boolean isEmpty() {
        return file == null && StringUtils.isBlank(gav);
    }

}
