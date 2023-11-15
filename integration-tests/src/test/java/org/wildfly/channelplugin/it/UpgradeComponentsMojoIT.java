package org.wildfly.channelplugin.it;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenPredefinedRepository;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.Stream;
import org.wildfly.channel.version.VersionMatcher;
import org.wildfly.channelplugin.utils.DependencyModel;
import org.wildfly.channeltools.util.VersionUtils;

import static com.soebes.itf.extension.assertj.MavenExecutionResultAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

@MavenJupiterExtension
@MavenPredefinedRepository("maven-repo")
public class UpgradeComponentsMojoIT {

    private static final Logger LOGGER = Logger.getLogger(UpgradeComponentsMojoIT.class.getName());

    /**
     * Basic functionality check.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @SystemProperty(value = "localRepository", content = "${maven.repo.local}")
    @SystemProperty(value = "remoteRepositories", content = "file://${maven.repo.local}")
    @SystemProperty(value = "ignoreStreams", content = "org.jboss:ignored-dep")
    @MavenTest
    void basic_project_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overriden
        assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isEqualTo("2.2.17.SP1-redhat-00001");

        // verify dependency versions are still set to properties
        assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });
        assertThat(dependencyModel.getDependency("io.undertow", "undertow-servlet", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });

        // verify dependency version has been overriden
        assertThat(dependencyModel.getDependency("org.jboss.marshalling", "jboss-marshalling", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("2.0.9.Final-redhat-00001");
                });

        // verify version specified by recursive property reference
        assertThat(dependencyModel.getDependency("commons-io", "commons-io", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("${commons.version}");
                });
        assertThat(model.getProperties().get("commons.version")).isEqualTo("${commons2.version}");
        assertThat(model.getProperties().get("commons2.version")).isEqualTo("2.10.1.redhat-00001");

        // verify that ignored stream were not upgraded
        assertThat(dependencyModel.getDependency("org.jboss", "ignored-dep", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("1.0.0.Final");
                });
    }

    /**
     * This test tales a jboss-eap-jakartaee8:7.4.0.GA BOM and aligns it to a channel with more recent component
     * versions. It is verified that all BOM dependencies that are listed in the channel, are upgraded accordingly.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @MavenTest
    void eap_bom_test_case(MavenExecutionResult result) throws MalformedURLException {
        assertThat(result).isSuccessful();

        Path manifestFile = result.getMavenProjectResult().getTargetProjectDirectory().resolve("manifest.yaml");
        ChannelManifest channel = ChannelManifestMapper.from(manifestFile.toUri().toURL());
        Model model = result.getMavenProjectResult().getModel();

        for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
            Optional<Stream> streamOptional = channel.findStreamFor(dependency.getGroupId(),
                    dependency.getArtifactId());

            if (streamOptional.isPresent()) {
                assertThat(streamOptional).isPresent();
                assertThat(streamOptional.get().getVersion()).isNotNull();

                String propertyExpression = dependency.getVersion();
                assertThat(propertyExpression).satisfies(e -> {
                    assertThat(e).startsWith("${");
                    assertThat(e).endsWith("}");
                });
                String propertyName = VersionUtils.extractPropertyName(propertyExpression);
                String versionString = model.getProperties().getProperty(propertyName);
                assertThat(versionString).isNotNull();
                assertThat(versionString)
                        .as("dependency version for %s:%s", dependency.getGroupId(), dependency.getArtifactId())
                        .isEqualTo(streamOptional.get().getVersion());
            } else {
                LOGGER.warning("Can't find stream for " + dependency);
            }
        }
    }

    /**
     * Test `overrideProperties` parameter.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @SystemProperty(value = "overrideProperties", content = "undertow.version=2.2.5.Final-Overridden")
    @MavenTest
    void override_property_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overriden
        assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isEqualTo("2.2.5.Final-Overridden");
        // dependency still referencing the property
        assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });
    }

    /**
     * Test `overrideDependencies` parameter.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @SystemProperty(value = "overrideDependencies", content = "io.undertow:undertow-core:2.2.5.Final-Overridden")
    @MavenTest
    void override_dependency_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify no change in property
        assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isEqualTo("2.2.5.Final");
        // verify dependency version element has been overridden
        assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null))
                .satisfies(o -> {
                    assertThat(o).isPresent();
                    assertThat(o.get().getVersion()).isEqualTo("2.2.5.Final-Overridden");
                });
    }
}
