package org.wildfly.channelplugin;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.maven.plugin.AbstractMojo;
import org.jboss.jandex.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

/**
 * This provides functionality to read default, project specific, Mojo configuration from a file and apply it on a Mojo.
 * <p>
 * The configuration file would be versioned in a project repository, in a file called
 * <code>.wildfly-channel-maven-plugin</code>.
 */
class MojoConfigurator {

    private static final String INDEX_PATH = "/META-INF/jandex.idx";
    public static final String DEFAULT_CONFIGURATION_FILE = ".wildfly-channel-maven-plugin";
    private static final DotName PROPERTY_ANNOTATION_NAME =
            DotName.createSimple("org.apache.maven.plugins.annotations.Parameter");

    private final Index index;
    private final Map<String, String> preconfiguredParameters;


    public MojoConfigurator() throws IOException {
        this(new File(DEFAULT_CONFIGURATION_FILE));
    }

    public MojoConfigurator(File configFile) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(INDEX_PATH)) {
            IndexReader reader = new IndexReader(input);
            index = reader.read();
        }

        if (configFile.isFile()) {
            try {
                preconfiguredParameters = readConfiguration(configFile);
            } catch (IOException e) {
                throw new IllegalArgumentException("Can't read configuration file", e);
            }
        } else {
            preconfiguredParameters = Collections.emptyMap();
        }
    }

    /**
     * Reads default system properties configuration from a file and applies it to a Mojo instance.
     * @param mojo a Mojo instance to set the parameters to
     */
    public void configureProperties(AbstractMojo mojo) {
        if (preconfiguredParameters.isEmpty()) {
            return; // Nothing to do
        }

        ClassInfo classInfo = index.getClassByName(mojo.getClass());

        for (FieldInfo fieldInfo: classInfo.fields()) {
            try {
                AnnotationInstance annotation = fieldInfo.annotation(PROPERTY_ANNOTATION_NAME);
                if (annotation != null) {
                    Field field = mojo.getClass().getDeclaredField(fieldInfo.name());
                    Object currentValue = field.get(mojo);
                    String preconfiguredValue = preconfiguredParameters.get(annotation.value("property").asString());


                    if (preconfiguredValue != null && isDefaultValue(currentValue, annotation)) {
                        if (field.getType().equals(String.class)) {
                            field.set(mojo, preconfiguredValue);
                        } else if (field.getType().equals(boolean.class)) {
                            field.set(mojo, Boolean.valueOf(preconfiguredValue));
                        } else if (field.getType().equals(List.class)) {
                            field.set(mojo, Arrays.asList(preconfiguredValue.split(",")));
                        } else {
                            throw new NotImplementedException("Don't know how to handle this type: " + field.getType());
                        }
                    }
                }
            } catch (IllegalAccessException e) {
                mojo.getLog().error(e.getMessage(), e);
            } catch (NoSuchFieldException e) {
                mojo.getLog().error("Field not found: " + fieldInfo.name(), e);
            }
        }
    }

    private static boolean isDefaultValue(Object currentValue, AnnotationInstance annotation) {
        DotName fieldType = ((FieldInfo) annotation.target()).type().name();
        String defaultValue = annotation.value("defaultValue") != null ?
                annotation.value("defaultValue").asString() : null;

        switch (fieldType.toString()) {
            case "java.util.List":
                // Only return true if no default was set and the current value is empty list or null.
                return defaultValue == null && (currentValue == null || ((List<?>) currentValue).isEmpty());
            case "java.lang.String":
            case "java.lang.Boolean":
                return currentValue == null || (defaultValue != null && defaultValue.equals(currentValue));
            default:
                return false;
        }
    }

    private static Map<String, String> readConfiguration(File file) throws IOException {
        HashMap<String, String> properties = new HashMap<>();
        List<String> lines = Files.readAllLines(file.toPath());
        for (String line: lines) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("-D")) {
                line = line.substring(2);
            }
            String[] segments = line.split("=", 2);
            if (segments.length == 1) {
                properties.put(segments[0], "true");
            } else if (segments.length == 2) {
                properties.put(segments[0], segments[1]);
            } else {
                throw new IllegalArgumentException("Can't read configuration, expected lines in format `-Dprop=value`, but got: " + line);
            }
        }
        return properties;
    }

}
