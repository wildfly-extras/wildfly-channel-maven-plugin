package org.wildfly.channelplugin.it;

import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenPredefinedRepository;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.extension.SystemProperty;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.apache.maven.model.Model;
import org.assertj.core.api.Assertions;
import org.wildfly.channel.version.VersionMatcher;
import org.wildfly.channelplugin.utils.DependencyModel;

import static com.soebes.itf.extension.assertj.MavenExecutionResultAssert.assertThat;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@MavenJupiterExtension
@MavenPredefinedRepository("maven-repo")
public class UpgradeComponentsMojoIT {

    @SystemProperty(value = "injectMissingDependencies", content = "true")
    @MavenTest
    void basic_project_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overriden
        Assertions.assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isGreaterThan("2.2.5.Final-redhat-00001");

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
                    Assertions.assertThat(o.get().getVersion()).isGreaterThan("2.0.6.Final-redhat-00001");
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
        Assertions.assertThat(model.getProperties().get("commons2.version")).isEqualTo("2.10.0.redhat-00001");
    }

}
