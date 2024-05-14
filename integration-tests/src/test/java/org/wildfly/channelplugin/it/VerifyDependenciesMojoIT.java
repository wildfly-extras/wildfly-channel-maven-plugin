package org.wildfly.channelplugin.it;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenProject;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.assertj.core.api.Assertions;

import java.nio.file.Files;

import static com.soebes.itf.extension.assertj.MavenExecutionResultAssert.assertThat;

@MavenJupiterExtension
@MavenProject
public class VerifyDependenciesMojoIT {

    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:verify-dependencies")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @SystemProperty(value = "ignoreStreams", content = "io.undertow:undertow-servlet")
    @MavenTest
    void unaligned_test_case(MavenExecutionResult result) throws Exception {
        assertThat(result).isFailure();
        assertThat(result).out().warn().contains(
                "Dependency org.jboss.marshalling:jboss-marshalling:2.0.6.Final doesn't match expected version 2.0.9.Final-redhat-00001",
                "Dependency commons-io:commons-io:2.10.0 doesn't match expected version 2.10.1.redhat-00001",
                "Dependency io.undertow:undertow-core:2.2.5.Final doesn't match expected version 2.2.6.Final"
        );
        assertThat(result).out().warn().doesNotContain(
                "Dependency io.undertow:undertow-servlet:2.2.5.Final doesn't match expected version 2.2.6.Final"
        );
        Assertions.assertThat(Files.readString(result.getMavenLog().getStdout()))
                .contains("Project dependencies are not aligned according to specified channels.");
    }

    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:verify-dependencies")
    @SystemProperty(value = "manifestFile", content = "manifest-aligned.yaml")
    @MavenTest
    void aligned_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();
    }
}
