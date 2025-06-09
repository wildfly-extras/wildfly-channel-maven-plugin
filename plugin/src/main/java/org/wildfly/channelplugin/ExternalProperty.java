package org.wildfly.channelplugin;

import java.util.Objects;

/**
 * DTO representing external property (meaning a property defined outside of a Maven project, e.g. in some parent
 * pom.xml.
 */
class ExternalProperty {

    String name, value;

    public ExternalProperty(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExternalProperty that = (ExternalProperty) o;
        return Objects.equals(name, that.name) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }
}
