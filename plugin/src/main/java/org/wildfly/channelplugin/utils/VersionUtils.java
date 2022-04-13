package org.wildfly.channelplugin.utils;

public final class VersionUtils {

    private VersionUtils() {
    }

    public static boolean isProperty(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }

    public static String extractPropertyName(String value) {
        if (!isProperty(value)) {
            throw new IllegalArgumentException("Not a property: " + value);
        }
        return value.substring(2, value.length() - 1);
    }

}
