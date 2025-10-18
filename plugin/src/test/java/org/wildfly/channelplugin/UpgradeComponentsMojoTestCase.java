package org.wildfly.channelplugin;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
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
            assertThat(pair.getModule()).isSameAs(project);
            assertThat(pair.getPropertyName()).isEqualTo("version.a");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.b")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getModule()).isSameAs(project);
            assertThat(pair.getPropertyName()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.c")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getModule()).isSameAs(project);
            assertThat(pair.getPropertyName()).isEqualTo("version.d");
        });
        assertThat(UpgradeComponentsMojo.followProperties(project, "version.d")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getModule()).isSameAs(project);
            assertThat(pair.getPropertyName()).isEqualTo("version.d");
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
            assertThat(pair.getName()).isEqualTo("version.a");
            assertThat(pair.getValue()).isEqualTo("1.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.b")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getName()).isEqualTo("version.d");
            assertThat(pair.getValue()).isEqualTo("2.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.c")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getName()).isEqualTo("version.d");
            assertThat(pair.getValue()).isEqualTo("2.0");
        });
        assertThat(UpgradeComponentsMojo.resolveExternalProperty(project, "version.d")).satisfies(pair -> {
            assertThat(pair).isNotNull();
            assertThat(pair.getName()).isEqualTo("version.d");
            assertThat(pair.getValue()).isEqualTo("2.0");
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

    @Test
    public void testNullableStringsEqual() {
        // Both null
        assertThat(UpgradeComponentsMojo.nullableStringsEqual(null, null)).isTrue();

        // Both equal non-null
        assertThat(UpgradeComponentsMojo.nullableStringsEqual("test", "test")).isTrue();
        assertThat(UpgradeComponentsMojo.nullableStringsEqual("", "")).isTrue();

        // One null, one not
        assertThat(UpgradeComponentsMojo.nullableStringsEqual(null, "test")).isFalse();
        assertThat(UpgradeComponentsMojo.nullableStringsEqual("test", null)).isFalse();

        // Both non-null but different
        assertThat(UpgradeComponentsMojo.nullableStringsEqual("test1", "test2")).isFalse();
        assertThat(UpgradeComponentsMojo.nullableStringsEqual("", "test")).isFalse();
    }

    @Test
    public void testGaAndClassifierMatches() {
        // Create artifacts with same G:A but different classifiers
        ArtifactRef artifact1 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);
        ArtifactRef artifact2 = new SimpleArtifactRef("org.example", "my-artifact", "2.0.0", "jar", null);
        ArtifactRef artifact3 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", "tests");
        ArtifactRef artifact4 = new SimpleArtifactRef("org.example", "my-artifact", "2.0.0", "jar", "tests");
        ArtifactRef artifact5 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", "sources");
        ArtifactRef artifact6 = new SimpleArtifactRef("org.example", "other-artifact", "1.0.0", "jar", null);
        ArtifactRef artifact7 = new SimpleArtifactRef("org.other", "my-artifact", "1.0.0", "jar", null);

        // Same G:A:C (classifier is null) - should match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact1, artifact2)).isTrue();

        // Same G:A:C (classifier is "tests") - should match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact3, artifact4)).isTrue();

        // Same G:A but different classifier (null vs "tests") - should not match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact1, artifact3)).isFalse();
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact2, artifact4)).isFalse();

        // Same G:A but different classifier ("tests" vs "sources") - should not match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact3, artifact5)).isFalse();

        // Different artifactId - should not match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact1, artifact6)).isFalse();

        // Different groupId - should not match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact1, artifact7)).isFalse();

        // Self comparison - should match
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact1, artifact1)).isTrue();
        assertThat(UpgradeComponentsMojo.gaAndClassifierMatches(artifact3, artifact3)).isTrue();
    }

    @Test
    public void testAddOrUpdateArtifact_newArtifact() {
        Map<ProjectRef, Set<ArtifactRef>> uniqueGaMap = new HashMap<>();
        Map<ArtifactRef, Collection<ProjectRef>> transitiveDependencies = new HashMap<>();

        ArtifactRef artifact = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);
        Collection<ProjectRef> exclusions = Collections.emptyList();

        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact, exclusions);

        // Verify artifact was added
        assertThat(transitiveDependencies).containsKey(artifact);
        assertThat(transitiveDependencies.get(artifact)).isEqualTo(exclusions);
    }

    @Test
    public void testAddOrUpdateArtifact_higherVersionReplacesLower() {
        Map<ProjectRef, Set<ArtifactRef>> uniqueGaMap = new HashMap<>();
        Map<ArtifactRef, Collection<ProjectRef>> transitiveDependencies = new HashMap<>();

        ArtifactRef artifact1 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);
        ArtifactRef artifact2 = new SimpleArtifactRef("org.example", "my-artifact", "2.0.0", "jar", null);
        Collection<ProjectRef> exclusions1 = Collections.emptyList();
        Collection<ProjectRef> exclusions2 = Collections.emptyList();

        // Add version 1.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact1, exclusions1);

        // Add version 2.0.0 - should replace 1.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact2, exclusions2);

        // Verify only version 2.0.0 remains
        assertThat(transitiveDependencies).doesNotContainKey(artifact1);
        assertThat(transitiveDependencies).containsKey(artifact2);
    }

    @Test
    public void testAddOrUpdateArtifact_lowerVersionDoesNotReplace() {
        Map<ProjectRef, Set<ArtifactRef>> uniqueGaMap = new HashMap<>();
        Map<ArtifactRef, Collection<ProjectRef>> transitiveDependencies = new HashMap<>();

        ArtifactRef artifact1 = new SimpleArtifactRef("org.example", "my-artifact", "2.0.0", "jar", null);
        ArtifactRef artifact2 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);
        Collection<ProjectRef> exclusions1 = Collections.emptyList();
        Collection<ProjectRef> exclusions2 = Collections.emptyList();

        // Add version 2.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact1, exclusions1);

        // Try to add version 1.0.0 - should NOT replace 2.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact2, exclusions2);

        // Verify only version 2.0.0 remains
        assertThat(transitiveDependencies).containsKey(artifact1);
        assertThat(transitiveDependencies).doesNotContainKey(artifact2);
    }

    @Test
    public void testAddOrUpdateArtifact_differentClassifiersCoexist() {
        Map<ProjectRef, Set<ArtifactRef>> uniqueGaMap = new HashMap<>();
        Map<ArtifactRef, Collection<ProjectRef>> transitiveDependencies = new HashMap<>();

        ArtifactRef artifact1 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);
        ArtifactRef artifact2 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", "tests");
        ArtifactRef artifact3 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", "sources");
        Collection<ProjectRef> exclusions = Collections.emptyList();

        // Add all three artifacts with different classifiers
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact1, exclusions);
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact2, exclusions);
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, artifact3, exclusions);

        // Verify all three coexist
        assertThat(transitiveDependencies).containsKey(artifact1);
        assertThat(transitiveDependencies).containsKey(artifact2);
        assertThat(transitiveDependencies).containsKey(artifact3);
    }

    @Test
    public void testAddOrUpdateArtifact_classifierVersionUpgrade() {
        Map<ProjectRef, Set<ArtifactRef>> uniqueGaMap = new HashMap<>();
        Map<ArtifactRef, Collection<ProjectRef>> transitiveDependencies = new HashMap<>();

        // Same G:A:C but different versions
        ArtifactRef testsV1 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", "tests");
        ArtifactRef testsV2 = new SimpleArtifactRef("org.example", "my-artifact", "2.0.0", "jar", "tests");
        ArtifactRef mainV1 = new SimpleArtifactRef("org.example", "my-artifact", "1.0.0", "jar", null);

        Collection<ProjectRef> exclusions = Collections.emptyList();

        // Add tests classifier v1.0.0 and main v1.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, testsV1, exclusions);
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, mainV1, exclusions);

        // Upgrade tests classifier to v2.0.0
        UpgradeComponentsMojo.addOrUpdateArtifact(transitiveDependencies, testsV2, exclusions);

        // Verify tests v2.0.0 replaced v1.0.0, but main v1.0.0 remains
        assertThat(transitiveDependencies).doesNotContainKey(testsV1);
        assertThat(transitiveDependencies).containsKey(testsV2);
        assertThat(transitiveDependencies).containsKey(mainV1);
    }

}
