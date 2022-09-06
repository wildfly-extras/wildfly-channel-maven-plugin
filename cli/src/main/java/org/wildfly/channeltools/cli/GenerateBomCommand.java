package org.wildfly.channeltools.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.Stream;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channeltools.resolver.ChannelBuilder;
import org.wildfly.channeltools.resolver.DefaultMavenVersionsResolverFactory;
import org.wildfly.channeltools.util.PMEUtils;
import picocli.CommandLine;

/**
 * This command generates a Maven BOM from given source POM file. It effectively takes all dependencies directly defined
 * in a source POM (i.e. all deps where a version is defined), and put them into the generated BOM.
 */
@CommandLine.Command(description = {"Generates a Maven BOM for given project POM file.",
        "If a channel is configured, it will be used to upgrade detected dependencies in the generated BOM."})
public class GenerateBomCommand implements Runnable {

    private static final String VERSION_PREFIX = "version.";

    @CommandLine.Option(names = {"-i", "--input-pom"}, required = true, description = "Source POM file")
    File fromFile;

    @CommandLine.Option(names = {"-o", "--output-pom"}, required = true, description = "Output BOM file")
    File outputFile;

    @CommandLine.Option(names = {"--gav"}, required = true, description = "GAV of the generated BOM file")
    String gavString;

    @CommandLine.ArgGroup
    ChannelInputArgGroup channelInputGroup = new ChannelInputArgGroup();

    @CommandLine.ArgGroup
    ResolutionArgGroup resolutionArgGroup = new ResolutionArgGroup();

    @Override
    public void run() {
        // load the channel, if any was specified
        ChannelSession channelSession = null;
        Channel channel = null;
        if (!channelInputGroup.isEmpty()) {
            DefaultMavenVersionsResolverFactory resolverFactory = new DefaultMavenVersionsResolverFactory(
                    resolutionArgGroup.remoteRepositories, resolutionArgGroup.localRepository,
                    resolutionArgGroup.disableTlsVerification);
            channel = new ChannelBuilder(resolverFactory)
                    .setChannelGav(channelInputGroup.gav)
                    .setChannelFile(channelInputGroup.file)
                    .build();
            if (channel != null) {
                channelSession = new ChannelSession(Collections.singletonList(channel), resolverFactory);
            }
        }

        ManipulationSession manipulationSession = new ManipulationSession();
        PomIO pomIO = new PomIO(manipulationSession);
        Project project;

        try {
            project = PMEUtils.parseProject(pomIO, fromFile);
        } catch (ManipulationException e) {
            throw new RuntimeException("Couldn't parse input POM", e);
        }

        ProjectVersionRef gav = SimpleProjectVersionRef.parse(gavString);
        Model model = new Model();
        model.setGroupId(gav.getGroupId());
        model.setArtifactId(gav.getArtifactId());
        model.setVersion(gav.getVersionString());
        model.setDependencyManagement(new DependencyManagement());
        model.setProperties(new SortedProperties());

        // collect dependencies from the source POM
        HashSet<Map.Entry<ArtifactRef, Dependency>> projectDependencies = new HashSet<>();
        try {
            projectDependencies.addAll(project.getResolvedManagedDependencies(manipulationSession).entrySet());
            projectDependencies.addAll(project.getResolvedDependencies(manipulationSession).entrySet());
        } catch (ManipulationException e) {
            throw new RuntimeException("Couldn't resolve dependencies from input POM", e);
        }
        List<Map.Entry<ArtifactRef, Dependency>> sortedDependencies = projectDependencies.stream()
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                .collect(Collectors.toList());

        for (Map.Entry<ArtifactRef, Dependency> entry : sortedDependencies) {
            // skip test dependencies
            if ("test".equals(entry.getValue().getScope())) {
                continue;
            }
            ArtifactRef artifact = entry.getKey();
            Dependency dependency = entry.getValue();

            // if channel was given, use it to override the dependency version
            String newVersion = artifact.getVersionString();
            if (channelSession != null) {
                Optional<Stream> streamOptional = channel.findStreamFor(artifact.getGroupId(), artifact.getArtifactId());
                if (streamOptional.isPresent()) {
                    try {
                        MavenArtifact resolvedArtifact = channelSession.resolveMavenArtifact(artifact.getGroupId(),
                                artifact.getArtifactId(), artifact.getType(), artifact.getClassifier(),
                                artifact.getVersionString());
                        newVersion = resolvedArtifact.getVersion();
                    } catch (UnresolvedMavenArtifactException e) {
                        CliLogger.LOGGER.errorf(e, "Channel unable to resolve dependency %s", artifact);
                    }
                } else {
                    CliLogger.LOGGER.warnf("No stream found for %s", artifact);
                }
            }

            // determine version property name and record the property
            final String propertyName = determinePropertyName(model.getProperties(), artifact, dependency, newVersion);
            model.getProperties().put(propertyName, newVersion);

            // add a managed dependency
            CliLogger.LOGGER.infof("Adding dependency %s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), newVersion);
            model.getDependencyManagement().getDependencies().add(dependency(artifact, propertyName));
        }

        // write output BOM
        try {
            MavenXpp3Writer pomWriter = new MavenXpp3Writer();
            pomWriter.write(new FileOutputStream(outputFile), model);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't write output POM", e);
        }
    }

    private static String determinePropertyName(Properties existingProperties, ArtifactRef artifact,
            Dependency dependency, String newVersion) {
        /*// if the dependency version was configured with a property, try to use that original property name from the input POM
        if (VersionUtils.isProperty(dependency.getVersion())) {
            String originalPropertyName = VersionUtils.extractPropertyName(dependency.getVersion());
            if (existingProperties.getProperty(originalPropertyName) == null
                    || existingProperties.getProperty(originalPropertyName).equals(newVersion)) {
                return originalPropertyName;
            }
        }*/
        // try to use "version." + groupId
        if (existingProperties.getProperty(VERSION_PREFIX + artifact.getGroupId()) == null
                || existingProperties.getProperty(VERSION_PREFIX + artifact.getGroupId()).equals(newVersion)) {
            return VERSION_PREFIX + artifact.getGroupId();
        }
        // try to use "version." + groupId + artifactId
        if (existingProperties.getProperty(VERSION_PREFIX + artifact.getGroupId() + "." + artifact.getArtifactId()) == null
                || existingProperties.getProperty(VERSION_PREFIX + artifact.getGroupId() + "." + artifact.getArtifactId()).equals(newVersion)) {
            return VERSION_PREFIX + artifact.getGroupId() + "." + artifact.getArtifactId();
        }
        // not able to determine suitable property name
        throw new IllegalStateException(String.format("Detected multiple dependencies for %s:%s with different versions.",
                artifact.getGroupId(), artifact.getArtifactId()));
    }

    private static Dependency dependency(ArtifactRef ref, String versionPropertyName) {
        Dependency d = new Dependency();
        d.setGroupId(ref.getGroupId());
        d.setArtifactId(ref.getArtifactId());
        d.setVersion("${" + versionPropertyName + "}");
        d.setType(ref.getType());
        d.setClassifier(ref.getClassifier());
        return d;
    }

    /**
     * Implementation of the Properties class that returns keys in sorted order. This is to make properties sorted in
     * the generated POM.
     */
    static class SortedProperties extends Properties {
        @Override
        public Set<Object> keySet() {
            return Collections.unmodifiableSet(new TreeSet<Object>(super.keySet()));
        }
    }

}
