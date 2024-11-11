package org.wildfly.channelplugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.assertj.core.api.Assertions;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class AbstractChannelMojoTestCase {

    @TempDir
    File tempDir;

    private final RepositorySystem repositorySystem = Mockito.mock(RepositorySystem.class);

    private AbstractChannelMojo mojo;

    @BeforeEach
    public void before() throws Exception {
        // Prepare test resources as local files
        Path channelFile = prepareResource("multiple-channels.yaml");
        Path manifestFile = prepareResource("manifest.yaml");

        // Mock RepositorySystem, the resolveArtifact() method must return a valid manifest file because this is needed
        // for ChannelSession init.
        ArtifactResult artifactResult = new ArtifactResult(new ArtifactRequest());
        artifactResult.setArtifact(new DefaultArtifact(null, null, null, null, null, Collections.emptyMap(),
                manifestFile.toFile()));
        Mockito.when(repositorySystem.resolveArtifact(Mockito.any(), Mockito.any()))
                .thenReturn(artifactResult);

        // Initialize a concrete instance of the AbstractChannelMojo class
        mojo = new AbstractChannelMojo() {
            @Override
            public void execute() throws MojoExecutionException, MojoFailureException {

            }
        };
        mojo.channelFile = channelFile.toString();
        mojo.remoteRepositories = Collections.emptyList();
        mojo.repositorySystem = repositorySystem;
    }

    @Test
    public void testMultipleChannelsInFile() throws Exception {
        mojo.initChannelSession();

        Assertions.assertThat(mojo.channels.size()).isEqualTo(2);
        Assertions.assertThat(mojo.channels).extracting("manifestCoordinate.artifactId").containsAll(
                List.of("eap-8.0", "eap-xp-5.0"));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private Path prepareResource(String resourceName) throws IOException {
        tempDir.mkdir();
        Path targetFile = tempDir.toPath().resolve(resourceName);
        InputStream resource = getClass().getResourceAsStream(resourceName);
        Assertions.assertThat(resource).isNotNull().withFailMessage("Resource not found: " + resourceName);
        Files.copy(resource, targetFile);
        return targetFile;
    }
}
