package org.wildfly.channeltools.cli;

import picocli.CommandLine;

public class Cli {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MainCommand())
                .addSubcommand("resolve-channel", new ResolveChannelCommand())
                .addSubcommand("generate-bom", new GenerateBomCommand())
                .execute(args);
        System.exit(exitCode);
    }
}
