package org.wildfly.channelplugin.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class IOUtils {
    private IOUtils() {
    }

    public static String createTemporaryCache() throws IOException {
        Path tempDirectory = Files.createTempDirectory("wildfly-channel-maven-plugin-cache-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.walk(tempDirectory)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete temporary maven cache: " + tempDirectory, e);
            }
        }));
        return tempDirectory.toString();
    }
}
