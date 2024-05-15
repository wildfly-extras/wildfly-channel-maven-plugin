package org.wildfly.channelplugin.manipulation;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Stack;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import org.apache.http.util.Asserts;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.ext.common.model.Project;

/**
 * Provides functionality to manipulate properties and dependencies in a POM file.
 */
public class PomManipulator {

    private static final String DEPENDENCY_MANAGEMENT_PATH = "/project/dependencyManagement";
    private static final String DEPENDENCY_MANAGEMENT_DEPENDENCIES_PATH = "/project/dependencyManagement/dependencies";
    private static final String REPOSITORIES_PATH = "/project/repositories";
    private static final String PLUGIN_REPOSITORIES_PATH = "/project/pluginRepositories";
    private static final String PROJECT = "project";
    private static final String DEPENDENCY_MANAGEMENT = "dependencyManagement";
    private static final String DEPENDENCIES = "dependencies";
    private static final String REPOSITORIES = "repositories";
    private static final String PLUGIN_REPOSITORIES = "pluginRepositories";

    private final Project project;
    private final ModifiedPomXMLEventReader eventReader;
    private final StringBuilder content;
    private boolean closed = false;

    /**
     * @param project Project instance
     */
    public PomManipulator(Project project) {
        try {
            this.project = project;
            XMLInputFactory inputFactory = XMLInputFactory2.newInstance();
            inputFactory.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, Boolean.TRUE);
            this.content = PomHelper.readXmlFile(project.getPom());
            this.eventReader = new ModifiedPomXMLEventReader(content, inputFactory, project.getPom().getPath());
        } catch (IOException | XMLStreamException e) {
            throw new RuntimeException("Couldn't initialize PomWriter instance", e);
        }
    }

    public void overrideDependencyVersion(ArtifactRef d, String newVersion) throws XMLStreamException {
        PomHelper.setDependencyVersion(eventReader, d.getGroupId(), d.getArtifactId(), d.getVersionString(),
                newVersion, project.getModel());
    }

    public void overrideDependencyVersion(String groupId, String artifactId, String oldVersionString, String newVersion) throws XMLStreamException {
        PomHelper.setDependencyVersion(eventReader, groupId, artifactId, oldVersionString, newVersion,
                project.getModel());
    }

    public boolean overrideProperty(String propertyName, String propertyValue) throws XMLStreamException {
        return PomHelper.setPropertyVersion(eventReader, null, propertyName, propertyValue);
    }

    public void injectManagedDependency(ArtifactRef dependency, Collection<ProjectRef> exclusions, String oldVersion)
            throws XMLStreamException {
        injectDependencyManagementSection(eventReader);
        injectDependencyManagementDependenciesSection(eventReader);
        injectManagedDependency(eventReader, dependency, exclusions, oldVersion);
    }

    public void injectRepository(String id, String url) throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<String>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                // replaces "</repositories>" end element with new repository + the end element
                String elementName = event.asEndElement().getName().getLocalPart();
                if (elementName.equals(REPOSITORIES) && path.equals(REPOSITORIES_PATH)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, composeRepositoryElementString(id, url)
                            + "    </repositories>"
                    );
                    eventReader.clearMark(0);
                    break;
                }

                path = stack.pop();
            }
        }
    }

    public void injectPluginRepository(String id, String url) throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<String>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                // replaces "</repositories>" end element with new repository + the end element
                String elementName = event.asEndElement().getName().getLocalPart();
                if (elementName.equals(PLUGIN_REPOSITORIES) && path.equals(PLUGIN_REPOSITORIES_PATH)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, composePluginRepositoryElementString(id, url)
                            + "    </pluginRepositories>"
                    );
                    eventReader.clearMark(0);
                    break;
                }

                path = stack.pop();
            }
        }
    }

    public void injectProperty(String key, String version) throws XMLStreamException {
        injectProperty(eventReader, key, version);
    }

    /**
     * Writes the updated POM file.
     */
    public void writePom() {
        assertOpen();
        try (Writer writer = WriterFactory.newXmlWriter(project.getPom())) {
            closed = true;
            IOUtil.copy(content.toString(), writer);
            eventReader.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to the pom file", e);
        } catch (XMLStreamException e) {
            throw new RuntimeException("Couldn't close event reader", e);
        }
    }

    private void assertOpen() {
        Asserts.check(!closed, "This instance cannot be used repeatedly.");
    }

    /**
     * This method attempts to inject new dependency into at the end of the dependencyManagement section.
     * <p>
     * The dependencyManagement section must be already present in the POM.
     */
    static void injectManagedDependency(ModifiedPomXMLEventReader eventReader, ArtifactRef dependency,
            Collection<ProjectRef> exclusions, String oldVersion) throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                if (event.asEndElement().getName().getLocalPart().equals(DEPENDENCIES)
                        && path.equals(DEPENDENCY_MANAGEMENT_DEPENDENCIES_PATH)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, composeDependencyElementString(dependency, exclusions, oldVersion)
                                    + "        </dependencies>"
                    );
                    eventReader.clearMark(0);
                    return;
                }

                path = stack.pop();
            }
        }
    }

    /**
     * Inserts dependencyManagement section if it's not already present.
     */
    static void injectDependencyManagementSection(ModifiedPomXMLEventReader eventReader) throws XMLStreamException {
        eventReader.rewind();

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                if (event.asStartElement().getName().getLocalPart().equals(DEPENDENCY_MANAGEMENT)) {
                    // dependency management is already present
                    return;
                }
            } else if (event.isEndElement()) {
                if (event.asEndElement().getName().getLocalPart().equals(PROJECT)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, composeDependencyManagementElementString()
                            + "</project>"
                    );
                    eventReader.clearMark(0);
                    return;
                }
            }
        }
    }

    static void injectDependencyManagementDependenciesSection(ModifiedPomXMLEventReader eventReader)
            throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                if (event.asStartElement().getName().getLocalPart().equals(DEPENDENCIES)
                        && path.equals(DEPENDENCY_MANAGEMENT_PATH)) {
                    // dependencies section is already present
                    return;
                }

                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                if (event.asEndElement().getName().getLocalPart().equals(DEPENDENCY_MANAGEMENT)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, composeDependenciesElementString()
                            + "    </dependencyManagement>"
                    );
                    eventReader.clearMark(0);
                    return;
                }

                path = stack.pop();
            }
        }
    }

    /**
     * This method attempts to inject new property at the end of the properties section.
     * <p>
     * The properties section must be already present in the POM.
     */
    static void injectProperty(ModifiedPomXMLEventReader eventReader, String key, String version) throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                if (event.asEndElement().getName().getLocalPart().equals("properties")
                        && path.equals("/project/properties")) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, String.format("    <%s>%s</%s>\n", key, version, key)
                            + "    </properties>"
                    );
                    eventReader.clearMark(0);
                    break;
                }

                path = stack.pop();
            }
        }
    }

    private static String composeDependencyManagementElementString() {
        return "    <dependencyManagement>\n"
                + "    </dependencyManagement>\n";
    }

    private static String composeDependenciesElementString() {
        return "    <dependencies>\n"
                + "        </dependencies>\n";
    }

    private static String composeDependencyElementString(ArtifactRef artifact, Collection<ProjectRef> exclusions, String oldVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <dependency>\n");
        sb.append(String.format("                <groupId>%s</groupId>\n", artifact.getGroupId()));
        sb.append(String.format("                <artifactId>%s</artifactId>\n", artifact.getArtifactId()));
        sb.append(String.format("                <version>%s</version> <!-- originally %s -->\n", artifact.getVersionString(), oldVersion));
        if (artifact.getClassifier() != null) {
            sb.append(String.format("                <classifier>%s</classifier>\n", artifact.getClassifier()));
        }
        if (!"jar".equals(artifact.getType())) {
            sb.append(String.format("                <type>%s</type>\n", artifact.getType()));
        }
        if (exclusions != null && !exclusions.isEmpty()) {
            sb.append("                <exclusions>\n");
            for (ProjectRef e: exclusions) {
                sb.append("                    <exclusion>\n");
                sb.append("                        <groupId>" + e.getGroupId() + "</groupId>\n");
                sb.append("                        <artifactId>" + e.getArtifactId() + "</artifactId>\n");
                sb.append("                    </exclusion>\n");
            }
            sb.append("                </exclusions>\n");
        }
        sb.append("            </dependency>\n");
        return sb.toString();
    }

    private static String composeRepositoryElementString(String id, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <repository>\n");
        sb.append(String.format("            <id>%s</id>\n", id));
        sb.append(String.format("            <url>%s</url>\n", url));
        sb.append("            <releases>\n");
        sb.append("                <enabled>true</enabled>\n");
        sb.append("                <updatePolicy>always</updatePolicy>\n");
        sb.append("            </releases>\n");
        sb.append("            <snapshots>\n");
        sb.append("                <enabled>true</enabled>\n");
        sb.append("                <updatePolicy>always</updatePolicy>\n");
        sb.append("            </snapshots>\n");
        sb.append("        </repository>\n");
        return sb.toString();
    }

    private static String composePluginRepositoryElementString(String id, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <pluginRepository>\n");
        sb.append(String.format("            <id>%s</id>\n", id));
        sb.append(String.format("            <url>%s</url>\n", url));
        sb.append("            <releases>\n");
        sb.append("                <enabled>true</enabled>\n");
        sb.append("                <updatePolicy>always</updatePolicy>\n");
        sb.append("            </releases>\n");
        sb.append("            <snapshots>\n");
        sb.append("                <enabled>true</enabled>\n");
        sb.append("                <updatePolicy>always</updatePolicy>\n");
        sb.append("            </snapshots>\n");
        sb.append("        </pluginRepository>\n");
        return sb.toString();
    }

}
