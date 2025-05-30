package org.wildfly.channelplugin.utils;

import org.wildfly.channel.version.VersionMatcher;

import java.util.Comparator;

public class VersionComparator implements Comparator<String> {

    @Override
    public int compare(String v1, String v2) {
        return VersionMatcher.COMPARATOR.compare(v1, v2);
    }
}
