package org.wildfly.channelplugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.Stream;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * This goal creates a manifest for a given Maven project. The generated manifest contains the output artifact and
 * all it's dependencies (including transitive ones). If executed on a parent pom, the generated manifest includes artifacts
 * \and dependencies of children projects.
 */
@Mojo(name = "create-manifest", requiresProject = true, requiresDirectInvocation = true,
        requiresDependencyResolution = ResolutionScope.TEST, aggregator = true, defaultPhase = LifecyclePhase.PACKAGE
)
public class CreateManifestMojo extends AbstractMojo {

    @Inject
    MavenProject mavenProject;

    /**
     * The scopes to exclude. By default, excludes "test" and "provided" scopes.
     */
    @Parameter(name="excludedScopes", property = "excludedScopes", defaultValue = "test,provided")
    private String excludedScopes;

    /**
     * Optional name of the generated manifest.
     */
    @Parameter(name="manifestName", property = "manifestName")
    private String manifestName;

    /**
     * Optional ID of the generated manifest.
     */
    @Parameter(name="manifestId", property = "manifestId")
    private String manifestId;

    /**
     * Optional description of the generated manifest.
     */
    @Parameter(name="manifestDescription", property = "manifestDescription")
    private String manifestDescription;

    /**
     * Optional logicalVersion of the generated manifest.
     */
    @Parameter(name="manifestLogicalVersion", property = "manifestLogicalVersion")
    private String manifestLogicalVersion;

    /**
     * Comma separated list of module G:As that should not be processed.
     */
    @Parameter(property = "ignoreModules")
    List<String> ignoreModules;

    @Inject
    private MavenProjectHelper projectHelper;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Set<Artifact> artifacts = new HashSet<>();
        if (!isIgnoredModule(mavenProject.getGroupId(), mavenProject.getArtifactId())) {
            artifacts.addAll(mavenProject.getArtifacts());
            artifacts.add(mavenProject.getArtifact());
        }

        // include children modules
        for (MavenProject project : mavenProject.getCollectedProjects()) {
            if (isIgnoredModule(project.getGroupId(), project.getArtifactId())) {
                getLog().info(String.format("Skipping module %s:%s", project.getGroupId(), project.getArtifact()));
                continue;
            }
            if (getLog().isDebugEnabled()) {
                getLog().debug("Including child module: " + project.getId());
            }
            artifacts.addAll(project.getArtifacts());
            artifacts.add(project.getArtifact());
        }

        Set<Stream> streams = new TreeSet<>();

        final Set<String> excludedScopesSet;
        if (excludedScopes != null) {
            excludedScopesSet = Arrays.stream(excludedScopes.split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());
        } else {
            excludedScopesSet = Set.of("test", "provided");
        }
        if (getLog().isDebugEnabled()) {
            getLog().debug("Excluded scopes: " + String.join(",", excludedScopesSet));
        }

        for (Artifact artifact : artifacts) {
            if (artifact == null) {
                throw new RuntimeException("Artifact cannot be null.");
            }

            // exclude poms - they are not runtime artifacts
            if (artifact.getType() != null && artifact.getType().equals("pom")) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Ignoring pom artifact " + artifact);
                }
                continue;
            }

            // filter artifacts based on scope
            if (artifact.getScope() != null && (excludedScopesSet.contains(artifact.getScope()))) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Ignoring artifact in ignored scope: " + artifact);
                }
                continue;
            }

            streams.add(new Stream(artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion()));
        }


        final ChannelManifest channelManifest = new ChannelManifest(manifestName, manifestId, manifestLogicalVersion, manifestDescription, Collections.emptyList(), streams);

        try {
            final String yaml = ChannelManifestMapper.toYaml(channelManifest);
            final String manifestFileName = String.format("%s-%s-%s.%s",
                    mavenProject.getArtifactId(), mavenProject.getVersion(),
                    ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION);
            final Path outputDirectory = Path.of(mavenProject.getBuild().getDirectory());
            if (!Files.exists(outputDirectory)) {
                Files.createDirectory(outputDirectory);
            }
            final Path outputPath = Path.of(mavenProject.getBuild().getDirectory(), manifestFileName);
            Files.writeString(outputPath, yaml, StandardCharsets.UTF_8);
            projectHelper.attachArtifact(mavenProject, ChannelManifest.EXTENSION, ChannelManifest.CLASSIFIER, outputPath.toFile());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to serialize manifest", e);
        }
    }

    private boolean isIgnoredModule(String groupId, String artifactId) {
        return ignoreModules.contains(groupId + ":" + artifactId)
                || (groupId.equals(mavenProject.getGroupId()) && ignoreModules.contains(":" + artifactId));
    }

}
