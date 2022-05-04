package org.wildfly.channeltools.cli;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine;

public class ResolutionArgGroup {

    @CommandLine.Option(names = {"-r", "--remote-repo"},
            description = "Remote maven repositories for artifact resolution")
    List<String> remoteRepositories = new ArrayList<>();

    @CommandLine.Option(names = {"--local-repo"}, description = "Local maven repository for artifact resolution")
    String localRepository;

    @CommandLine.Option(names = {"--disable-tls-verification"}, description = "Disable TLS certificate verification of maven repositories")
    boolean disableTlsVerification;

}
