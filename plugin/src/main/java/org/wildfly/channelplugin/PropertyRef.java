package org.wildfly.channelplugin;

import org.commonjava.maven.ext.common.model.Project;

import java.util.Objects;

/**
 * Reference to a maven property. Holds reference to a project module where the property is defined and a property name.
 */
class PropertyRef {
    private final Project module;
    private final String propertyName;

    public PropertyRef(Project module, String propertyName) {
        this.module = module;
        this.propertyName = propertyName;
    }

    public Project getModule() {
        return module;
    }

    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PropertyRef that = (PropertyRef) o;
        return Objects.equals(module, that.module) && Objects.equals(propertyName, that.propertyName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, propertyName);
    }
}
