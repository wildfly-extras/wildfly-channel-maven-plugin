package org.wildfly.channelplugin.it;


import com.soebes.itf.jupiter.extension.MavenGoal;
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

@MavenJupiterExtension
public class SetPropertyMojoIT {

    @MavenGoal("${project.groupId}:wildfly-channel-maven-plugin:${project.version}:set-property")
    @SystemProperty(value = "manifestFile", content = "manifest.yaml")
    @SystemProperty(value = "property", content = "undertow.version")
    @SystemProperty(value = "stream", content = "io.undertow:undertow-core")
    @MavenTest
    void set_version_test_case(MavenExecutionResult result) {
        assertThat(result).isSuccessful();

        Model model = result.getMavenProjectResult().getModel();
        DependencyModel dependencyModel = new DependencyModel(model);

        // verify version property has been overridden
        Assertions.assertThat(model.getProperties().getProperty("undertow.version"))
                .usingComparator(VersionMatcher.COMPARATOR).isEqualTo("2.2.5.Final-redhat-00001");
        // dependency still referencing the property
        Assertions.assertThat(dependencyModel.getDependency("io.undertow", "undertow-core", "jar", null))
                .satisfies(o -> {
                    Assertions.assertThat(o).isPresent();
                    Assertions.assertThat(o.get().getVersion()).isEqualTo("${undertow.version}");
                });
    }

}
