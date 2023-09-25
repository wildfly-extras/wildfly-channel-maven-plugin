package org.wildfly.channelplugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MojoConfiguratorTestCase {

    @TempDir
    File folder;

    private File config;

    @BeforeEach
    public void setup() {
        config = new File(folder, "config");

    }

    @Test
    public void test() throws IOException {
        UpgradeComponentsMojo mojo = new UpgradeComponentsMojo();
        mojo.overrideDependencies = Collections.emptyList(); // By default, collection parameters are initialized with an empty collection if no value is set.

        Files.writeString(config.toPath(), "-DlocalRepository=some/path\n"
                + "-DinjectTransitiveDependencies=false\n"
                + "-DoverrideDependencies=a:b:c,d:e:f\n");

        MojoConfigurator configurator = new MojoConfigurator(config);
        configurator.configureProperties(mojo);

        assertThat(mojo.injectTransitiveDependencies).isEqualTo(false);
        assertThat(mojo.localRepositoryPath).isEqualTo("some/path");
        assertThat(mojo.overrideDependencies).containsExactly("a:b:c", "d:e:f");
    }

    @Test
    public void testDefaultOverrides() throws Exception {
        UpgradeComponentsMojo mojo = new UpgradeComponentsMojo();
        // Set to non-default value:
        mojo.inlineVersionOnConflict = false;
        mojo.overrideDependencies = List.of("a:b:c");
        mojo.localRepositoryPath = "some/path";

        // Create config file that tries to change above settings:
        Files.writeString(config.toPath(), "-DinlineVersionOnConflict=true\n"
                + "-DoverrideDependencies=d:e:f\n"
                + "-DlocalRepository=other/path");

        MojoConfigurator configurator = new MojoConfigurator(config);
        configurator.configureProperties(mojo);

        // Check that original values were preserved:
        assertThat(mojo.inlineVersionOnConflict).isEqualTo(false);
        assertThat(mojo.overrideDependencies).containsExactly("a:b:c");
        assertThat(mojo.localRepositoryPath).isEqualTo("some/path");
    }
}
