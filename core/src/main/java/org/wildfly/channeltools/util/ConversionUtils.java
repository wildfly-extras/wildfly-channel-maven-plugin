package org.wildfly.channeltools.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;

public final class ConversionUtils {

    private ConversionUtils() {}

    public static ArtifactRef toArtifactRef(Dependency a) {
        return new SimpleArtifactRef(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getType(),
                a.getClassifier());
    }

    public static ArtifactRef toArtifactRef(Artifact a) {
        return new SimpleArtifactRef(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getType(),
                a.getClassifier());
    }

    public static List<ProjectRef> toProjectRefs(List<Exclusion> exclusions) {
        if (exclusions == null) {
            return new ArrayList<>();
        }

        ArrayList<ProjectRef> refs = new ArrayList<>();
        for (Exclusion e: exclusions) {
            refs.add(new SimpleProjectRef(e.getGroupId(), e.getArtifactId()));
        }
        return refs;
    }
}
