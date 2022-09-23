package org.wildfly.channeltools.util;

import org.apache.maven.model.Dependency;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;

public final class ConversionUtils {

    private ConversionUtils() {}

    public static ArtifactRef toArtifactRef(Dependency a) {
        return new SimpleArtifactRef(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getType(),
                a.getClassifier());
    }
}
