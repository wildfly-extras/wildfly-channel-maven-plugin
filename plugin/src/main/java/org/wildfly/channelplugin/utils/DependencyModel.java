package org.wildfly.channelplugin.utils;

import java.util.HashMap;
import java.util.Optional;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import static java.util.Objects.requireNonNull;

/**
 * Utility class that allows easy access to all dependencies defined in a POM model.
 */
public class DependencyModel {

    private final HashMap<String, Dependency> dependencyMap = new HashMap<>();

    public DependencyModel(Model model) {
        for (Dependency dependency: model.getDependencies()) {
            dependencyMap.put(key(dependency), dependency);
        }
        if (model.getDependencyManagement() != null) {
            for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
                dependencyMap.put(key(dependency), dependency);
            }
        }
    }

    public Optional<Dependency> getDependency(String groupId, String artifactId, String type, String classifier) {
        Dependency dependency = dependencyMap.get(key(groupId, artifactId, type, classifier));
        return dependency == null ? Optional.empty() : Optional.of(dependency);
    }

    private static String key(String g, String a, String t, String c) {
        requireNonNull(g);
        requireNonNull(a);
        return g + ":" + a + ":" + emptyStringIfNull(t) + ":" + emptyStringIfNull(c);
    }

    private static String key(Dependency d) {
        return key(d.getGroupId(), d.getArtifactId(), d.getType(), d.getClassifier());
    }

    private static String emptyStringIfNull(String s) {
        return s == null ? "" : s;
    }
}
