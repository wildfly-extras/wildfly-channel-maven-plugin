package org.wildfly.channelplugin;

import java.util.Properties;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.ext.common.model.Project;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UpgradeComponentsMojoTestCase {

    @Test
    public void testFollowProperties() throws Exception {
        final Model model = new Model();
        model.setVersion("version");
        model.setProperties(sampleProperties());
        final Project project = new Project(model);

        assertThat(UpgradeComponentsMojo.followProperties(project, "version.a")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isSameAs(project);
            assertThat(pair.getRight()).isEqualTo("version.a");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.b")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isSameAs(project);
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.c")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isSameAs(project);
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.d")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isSameAs(project);
            assertThat(pair.getRight()).isEqualTo("version.d");
        });
    }

    @Test
    public void testResolveExternalProperty() {
        final Model parentModel = new Model();
        parentModel.setVersion("version");
        parentModel.setProperties(sampleProperties());
        final MavenProject parentProject = new MavenProject(parentModel);

        final MavenProject project = new MavenProject();
        project.setParent(parentProject);

        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.a")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isEqualTo("version.a");
            assertThat(pair.getRight()).isEqualTo("1.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.b")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isEqualTo("version.d");
            assertThat(pair.getRight()).isEqualTo("2.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.c")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isEqualTo("version.d");
            assertThat(pair.getRight()).isEqualTo("2.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.d")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getLeft()).isEqualTo("version.d");
            assertThat(pair.getRight()).isEqualTo("2.0");
        });
    }

    private Properties sampleProperties() {
        Properties properties = new Properties();
        properties.put("version.a", "1.0");
        properties.put("version.b", "${version.c}");
        properties.put("version.c", "${version.d}");
        properties.put("version.d", "2.0");
        return properties;
    }

}
