package org.wildfly.channelplugin;

import hu.vissy.texttable.TableFormatter;
import hu.vissy.texttable.column.ColumnDefinition;
import hu.vissy.texttable.contentformatter.CellContentFormatter;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.wildfly.channel.NoStreamFoundException;
import org.wildfly.channel.VersionResult;
import org.wildfly.channeltools.util.ConversionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static hu.vissy.texttable.BorderFormatter.Builder;
import static hu.vissy.texttable.BorderFormatter.DefaultFormatters;

/**
 * Verifies that project dependency versions match versions specified in a manifest.
 * <p>
 * This goal only executes in the execution root project and provides aggregated results for all nested projects.
 */
@Mojo(name = "verify-dependencies",
        defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
        aggregator = true,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        inheritByDefault = false)
public class VerifyDependenciesMojo extends AbstractChannelMojo {

    private static final String PLUGIN_DIRECTORY = "wildfly-channel-reports";
    private static final String UNALIGNED_REPORT_FILE = "unaligned-dependencies.txt";
    private static final String STREAM_NOT_FOUND_REPORT_FILE = "dependencies-missing-from-channels.txt";

    /**
     * Should the build fail when unaligned dependencies are found? If false, only warnings are printed and a report
     * file is generated.
     */
    @Parameter(property = "failBuild", defaultValue = "true")
    boolean failBuild;

    /**
     * Fail the build when project contains a dependency not represented in specified channels?
     */
    @Parameter(property = "failWhenStreamNotFound", defaultValue = "false")
    boolean failWhenStreamNotFound;

    /**
     * List of "G:A" strings representing dependencies that should be ignored (don't need to match versions in specified
     * channels).
     */
    @Parameter(property = "ignoreStreams")
    List<String> ignoreStreams;

    /**
     * List of maven scopes that should not be included in the verification. Dependencies with these scopes will be
     * ignored.
     */
    @Parameter(property = "ignoreScopes", defaultValue = "test")
    List<String> ignoreScopes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        initChannelSession();
        final List<ProjectRef> ignoredStreams = ignoreStreams.stream().map(SimpleProjectRef::parse).collect(Collectors.toList());

        List<Pair<ArtifactRef, String>> unalignedDependencies = new ArrayList<>();
        List<ArtifactRef> streamNotFoundDependencies = new ArrayList<>();

        List<MavenProject> projects = new ArrayList<>();
        projects.add(mavenProject);
        projects.addAll(mavenProject.getCollectedProjects());

        for (MavenProject project: projects) {

            Model model = project.getModel();
            List<ArtifactRef> dependencies = model.getDependencies().stream()
                    .filter(d -> !ignoreScopes.contains(d.getScope()))
                    .map(ConversionUtils::toArtifactRef)
                    .collect(Collectors.toList());

            for (ArtifactRef d : dependencies) {
                try {
                    VersionResult result = channelSession.findLatestMavenArtifactVersion(d.getGroupId(), d.getArtifactId(),
                            d.getType(), d.getClassifier(), d.getVersionString());
                    String expectedVersion = result.getVersion();
                    if (!d.getVersionString().equals(expectedVersion)) {
                        if (ignoredStreams.contains(new SimpleProjectRef(d.getGroupId(), d.getArtifactId()))) {
                            getLog().info(String.format("Ignoring dependency %s:%s:%s not matching %s",
                                    d.getGroupId(), d.getArtifactId(), d.getVersionString(), expectedVersion));
                        } else {
                            unalignedDependencies.add(Pair.of(d, expectedVersion));
                        }
                    }
                } catch (NoStreamFoundException e) {
                    String message = String.format("Artifact %s:%s:%s not present in configured channels.",
                            d.getGroupId(), d.getArtifactId(), d.getVersionString());
                    streamNotFoundDependencies.add(d);
                    if (failWhenStreamNotFound) {
                        throw new MojoFailureException(message, e);
                    } else {
                        getLog().warn(message);
                    }
                }
            }
        }

        printReportFiles(unalignedDependencies, streamNotFoundDependencies);
        if (!unalignedDependencies.isEmpty()) {
            unalignedDependencies.forEach(pair -> {
                ArtifactRef dep = pair.getLeft();
                String v = pair.getRight();
                getLog().warn(String.format("Dependency %s:%s:%s doesn't match expected version %s",
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersionString(), v));
            });
            if (failBuild) {
                throw new MojoFailureException("Project dependencies are not aligned according to specified channels.");
            }
        }
    }

    private void printReportFiles(List<Pair<ArtifactRef, String>> unalignedDependencies, List<ArtifactRef> streamNotFoundDependencies)
            throws MojoExecutionException {
        final Path buildDirectory = Path.of(mavenProject.getBuild().getDirectory());
        final Path pluginDirectory = buildDirectory.resolve(PLUGIN_DIRECTORY);
        final Path unalignedReportFile = pluginDirectory.resolve(UNALIGNED_REPORT_FILE);
        final Path streamNotFoundReportFile = pluginDirectory.resolve(STREAM_NOT_FOUND_REPORT_FILE);
        try {
            if (!Files.exists(buildDirectory)) {
                Files.createDirectory(buildDirectory);
            }
            if (!Files.exists(pluginDirectory)) {
                Files.createDirectory(pluginDirectory);
            }

            if (!unalignedDependencies.isEmpty()) {
                Files.writeString(unalignedReportFile, unalignedDependenciesTable(unalignedDependencies));
            }
            if (!streamNotFoundDependencies.isEmpty()) {
                Files.writeString(streamNotFoundReportFile, streamNotFoundDependenciesTable(streamNotFoundDependencies));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write report file " + unalignedReportFile, e);
        }
    }

    static String unalignedDependenciesTable(List<Pair<ArtifactRef, String>> data) {
        TableFormatter<Pair<ArtifactRef, String>> formatter = new TableFormatter.Builder<Pair<ArtifactRef, String>>()
                .withHeading("Following dependencies are not aligned with specified channels")
                .withSeparateDataWithLines(true)
                .withBorderFormatter(new Builder(DefaultFormatters.NO_VERTICAL)
                        .build())
                .withColumn(new ColumnDefinition.StatelessBuilder<Pair<ArtifactRef, String>, String>()
                        .withTitle("Dependency")
                        .withDataExtractor(d -> String.format("%s:%s:%s",
                                        d.getLeft().getGroupId(), d.getLeft().getArtifactId(), d.getLeft().getVersionString()))
                        .withCellContentFormatter(
                                new CellContentFormatter.Builder().withMinWidth(8).build())
                        .build())
                .withColumn(new ColumnDefinition.StatelessBuilder<Pair<ArtifactRef, String>, String>()
                        .withTitle("Expected Version")
                        .withDataExtractor(Pair::getRight)
                        .build())
                .build();
        return formatter.apply(data);
    }


    static String streamNotFoundDependenciesTable(List<ArtifactRef> data) {
        TableFormatter<ArtifactRef> formatter = new TableFormatter.Builder<ArtifactRef>()
                .withHeading("Following dependencies are not represented in specified channels")
                .withSeparateDataWithLines(true)
                .withBorderFormatter(new Builder(DefaultFormatters.NO_VERTICAL)
                        .build())
                .withColumn(new ColumnDefinition.StatelessBuilder<ArtifactRef, String>()
                        .withTitle("Dependency")
                        .withDataExtractor(d -> String.format("%s:%s:%s",
                                d.getGroupId(), d.getArtifactId(), d.getVersionString()))
                        .withCellContentFormatter(
                                new CellContentFormatter.Builder().withMinWidth(8).build())
                        .build())
                .build();
        return formatter.apply(data);
    }
}
