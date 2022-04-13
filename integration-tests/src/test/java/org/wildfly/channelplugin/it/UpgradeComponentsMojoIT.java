package org.wildfly.channelplugin.it;

import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.apache.maven.model.Model;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.wildfly.channelplugin.utils.DependencyModel;
import org.wildfly.channel.version.VersionMatcher;

import static com.soebes.itf.extension.assertj.MavenExecutionResultAssert.assertThat;

@MavenJupiterExtension
public class UpgradeComponentsMojoIT {

    @MavenTest
    @Test
    void basic_project_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overriden
        Assertions.assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isGreaterThan("2.2.5.Final-redhat-00001");

        // verify dependency versions are still set to properties
        Assertions.assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null)
                .get().getVersion()).isEqualTo("${undertow.version}");
        Assertions.assertThat(dependencyModel.getDependency("io.undertow", "undertow-servlet", "jar", null)
                .get().getVersion()).isEqualTo("${undertow.version}");

        // verify dependency version has been overriden
        Assertions.assertThat(dependencyModel.getDependency("org.jboss.marshalling", "jboss-marshalling", "jar", null)
                        .get().getVersion())
                .usingComparator(VersionMatcher.COMPARATOR).isGreaterThan("2.0.6.Final-redhat-00001");
    }


}
