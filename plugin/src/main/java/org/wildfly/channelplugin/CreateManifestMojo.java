package org.wildfly.channelplugin;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.Stream;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * This goal creates a manifest for a given Maven project. The generated manifest contains the output artifact and
 * all it's dependencies (including transitive ones). If executed on a parent pom, the generated manifest includes artifacts
 * \and dependencies of children projects.
 */
@Mojo(name = "create-manifest", requiresProject = true, requiresDirectInvocation = true,
        requiresDependencyResolution = ResolutionScope.TEST,
        aggregator = true
)
public class CreateManifestMojo extends AbstractMojo {

    @Inject
    MavenProject mavenProject;

    /**
     * The file to write the generated manifest to. If not specified the manifest is printed to the output
     */
    @Parameter(name="outputFile", property = "outputFile")
    private File outputFile;

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


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Set<Artifact> artifacts = new HashSet<>();
        artifacts.addAll(mavenProject.getArtifacts());
        artifacts.add(mavenProject.getArtifact());

        // include children modules
        for (MavenProject project : mavenProject.getCollectedProjects()) {
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


        final ChannelManifest channelManifest = new ChannelManifest(manifestName, manifestId, manifestDescription, streams);

        try {
            final String yaml = ChannelManifestMapper.toYaml(channelManifest);
            if (outputFile != null) {
                FileUtils.writeStringToFile(outputFile, yaml, StandardCharsets.UTF_8);
            } else {
                System.out.println(yaml);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to serialize manifest", e);
        }
    }
}
