package org.wildfly.channelplugin.it;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenPredefinedRepository;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.assertj.core.api.Assertions;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Stream;
import org.wildfly.channel.version.VersionMatcher;
import org.wildfly.channelplugin.utils.DependencyModel;
import org.wildfly.channeltools.util.VersionUtils;

import static com.soebes.itf.extension.assertj.MavenExecutionResultAssert.assertThat;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@MavenJupiterExtension
@MavenPredefinedRepository("maven-repo")
public class UpgradeComponentsMojoIT {

    private static final Logger LOGGER = Logger.getLogger(UpgradeComponentsMojoIT.class.getName());

    /**
     * Basic functionality check.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "channelFile", content = "channel.yaml")
    @SystemProperty(value = "injectMissingDependencies", content = "true")
    @SystemProperty(value = "localRepository", content = "${maven.repo.local}")
    @SystemProperty(value = "ignoreGAs", content = "org.jboss:ignored-dep")
    @MavenTest
    void basic_project_test_case(MavenExecutionResult result) throws MalformedURLException {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overriden
        Assertions.assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isEqualTo("2.2.17.SP1-redhat-00001");

        // verify dependency versions are still set to properties
        Assertions.assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o.isPresent());
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });
        Assertions.assertThat(dependencyModel.getDependency("io.undertow", "undertow-servlet", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o.isPresent());
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });

        // verify dependency version has been overriden
        Assertions.assertThat(dependencyModel.getDependency("org.jboss.marshalling", "jboss-marshalling", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o.isPresent());
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("2.0.9.Final-redhat-00001");
                });

        // verify dependency has been injected
        Assertions.assertThat(dependencyModel.getDependency("org.jboss", "extra-dep", "jar", null)).satisfies(d -> {
            Assertions.assertThat(d).isPresent();
            Assertions.assertThat(d.get().getVersion()).isEqualTo("1.0.0.Final");
        });

        // verify version specified by recursive property reference
        Assertions.assertThat(dependencyModel.getDependency("commons-io", "commons-io", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o.isPresent());
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("${commons.version}");
                });
        Assertions.assertThat(model.getProperties().get("commons.version")).isEqualTo("${commons2.version}");
        Assertions.assertThat(model.getProperties().get("commons2.version")).isEqualTo("2.10.1.redhat-00001");

        // verify that ignored stream were not upgraded
        Assertions.assertThat(dependencyModel.getDependency("org.jboss", "ignored-dep", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o).isPresent();
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("1.0.0.Final");
                });

        // verify that effective channel file has been created
        File effectiveChannelFile = new File(result.getMavenProjectResult().getTargetProjectDirectory(),
                "target/recorded-channel.yaml");
        Assertions.assertThat(effectiveChannelFile).exists();
        Channel effectiveChannel = ChannelMapper.from(effectiveChannelFile.toURI().toURL());
        Assertions.assertThat(effectiveChannel.getStreams().stream()
                        .map(s -> s.getGroupId() + ":" + s.getArtifactId() + ":" + s.getVersion())
                        .collect(Collectors.toList()))
                .contains(
                        "io.undertow:undertow-core:2.2.17.SP1-redhat-00001",
                        "org.jboss.marshalling:jboss-marshalling:2.0.9.Final-redhat-00001",
                        "org.jboss:extra-dep:1.0.0.Final",
                        "commons-io:commons-io:2.10.1.redhat-00001",
                        "org.jboss:ignored-dep:2.0.0.Final"
                );
    }

    /**
     * This test tales a jboss-eap-jakartaee8:7.4.0.GA BOM and aligns it to a channel with more recent component
     * versions. It is verified that all BOM dependencies that are listed in the channel, are upgraded accordingly.
     */
    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:upgrade")
    @SystemProperty(value = "channelFile", content = "channel.yaml")
    @MavenTest
    void eap_bom_test_case(MavenExecutionResult result) throws MalformedURLException {
        assertThat(result).isSuccessful();

        File channelFile = new File(result.getMavenProjectResult().getTargetProjectDirectory(), "channel.yaml");
        Channel channel = ChannelMapper.from(channelFile.toURI().toURL());
        Model model = result.getMavenProjectResult().getModel();

        for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
            Optional<Stream> streamOptional = channel.findStreamFor(dependency.getGroupId(),
                    dependency.getArtifactId());

            if (streamOptional.isPresent()) {
                Assertions.assertThat(streamOptional).isPresent();
                Assertions.assertThat(streamOptional.get().getVersion()).isNotNull();

                String propertyExpression = dependency.getVersion();
                Assertions.assertThat(propertyExpression).satisfies(e -> {
                    Assertions.assertThat(e).startsWith("${");
                    Assertions.assertThat(e).endsWith("}");
                });
                String propertyName = VersionUtils.extractPropertyName(propertyExpression);
                String versionString = model.getProperties().getProperty(propertyName);
                Assertions.assertThat(versionString).isNotNull();
                Assertions.assertThat(versionString)
                        .as("dependency version for %s:%s", dependency.getGroupId(), dependency.getArtifactId())
                        .isEqualTo(streamOptional.get().getVersion());
            } else {
                LOGGER.warning("Can't find stream for " + dependency);
            }
        }

    }

}
