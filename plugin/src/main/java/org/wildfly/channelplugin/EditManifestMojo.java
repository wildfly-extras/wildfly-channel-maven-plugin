package org.wildfly.channelplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ManifestRequirement;
import org.wildfly.channel.MavenCoordinate;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This goal allows overwriting metadata of a manifest.
 */
@Mojo(name = "edit-manifest", requiresProject = false, requiresDirectInvocation = true,
        requiresDependencyResolution = ResolutionScope.NONE, defaultPhase = LifecyclePhase.PACKAGE
)
public class EditManifestMojo extends AbstractMojo {
    /**
     * The scopes to exclude. By default, excludes "test" and "provided" scopes.
     */
    @Parameter(name="manifestPath", property = "manifestPath", required = true)
    private String manifestPath;

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

    @Parameter(name="manifestRequirements")
    private List<Requirement> manifestRequirements;

    @Override
    public void execute() throws MojoExecutionException {
        final Path streamsManifestFile = Path.of(manifestPath);

        verifyFileExists(streamsManifestFile);

        final ChannelManifest source;
        try {
            source = ChannelManifestMapper.from(streamsManifestFile.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Unable to read the source manifest", e);
        }

        final List<ManifestRequirement> requirements;
        if (manifestRequirements == null) {
            requirements = Collections.emptyList();
        } else {
            requirements = manifestRequirements.stream()
                    .map(r -> {
                        final MavenCoordinate mavenCoordinate;
                        if (r.getGroupId() != null && r.getArtifactId() != null) {
                            mavenCoordinate = new MavenCoordinate(r.getGroupId(), r.getArtifactId(), r.getVersion());
                        } else if (r.getGroupId() != null || r.getArtifactId() != null || r.getVersion() != null) {
                            throw new IllegalArgumentException("When using a requirement maven coordinate both groupId and artifactId needs to be set");
                        } else {
                            mavenCoordinate = null;
                        }
                        return new ManifestRequirement(r.getId(), mavenCoordinate);
                    })
                    .collect(Collectors.toList());
        }

        final ChannelManifest combinedManifest = new ChannelManifest(source.getSchemaVersion(),
                manifestName,
                manifestId,
                manifestDescription,
                requirements,
                source.getStreams());

        try {
            Files.writeString(streamsManifestFile, ChannelManifestMapper.toYaml(combinedManifest));
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to write to a manifest file", e);
        }
    }

    private static void verifyFileExists(Path metadataTemplate) {
        if (!metadataTemplate.toFile().exists()) {
            throw new IllegalArgumentException(String.format("The manifest file [%s] cannot be found.", metadataTemplate));
        }
    }

    public static class Requirement {
        @Parameter(name="id")
        private String id;
        @Parameter(name="groupId")
        private String groupId;
        @Parameter(name="artifactId")
        private String artifactId;
        @Parameter(name="version")
        private String version;

        public String getId() {
            return id;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }
    }
}
