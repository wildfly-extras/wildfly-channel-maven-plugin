package org.wildfly.channelplugin;

import java.util.Properties;

import org.apache.maven.model.Model;
import org.commonjava.maven.ext.common.model.Project;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UpgradeComponentsMojoTestCase {

    @Test
    public void testFollowProperties() throws Exception {
        Properties properties = new Properties();
        properties.put("version.a", "1.0");
        properties.put("version.b", "${version.c}");
        properties.put("version.c", "${version.d}");
        properties.put("version.d", "2.0");

        Model model = new Model();
        model.setVersion("version");
        model.setProperties(properties);
        Project project = new Project(model);

        assertThat(UpgradeComponentsMojo.followProperties(project, "version.a")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getRight()).isEqualTo("version.a");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.b")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.c")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.d")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
    }

}
