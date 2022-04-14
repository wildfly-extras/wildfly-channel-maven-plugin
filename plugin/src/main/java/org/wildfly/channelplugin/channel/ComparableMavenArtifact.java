package org.wildfly.channelplugin.channel;

import org.wildfly.channel.MavenArtifact;

/**
 * Extension of the MavenArtifact class which defines the `equals()` method.
 */
public class ComparableMavenArtifact extends MavenArtifact {

    public ComparableMavenArtifact(MavenArtifact a) {
        super(a.getGroupId(), a.getArtifactId(), a.getExtension(), a.getClassifier(), a.getVersion(), a.getFile());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MavenArtifact) {
            MavenArtifact a = (MavenArtifact) obj;

            if (this.getClassifier() == null) {
                if (a.getClassifier() != null) {
                    return false;
                }
            } else if (!this.getClassifier().equals(a.getClassifier())) {
                return false;
            }

            return this.getGroupId().equals(a.getGroupId())
                    && this.getArtifactId().equals(a.getArtifactId())
                    && this.getExtension().equals(a.getExtension())
                    && this.getVersion().equals(a.getVersion());
        }
        return false;
    }
}
