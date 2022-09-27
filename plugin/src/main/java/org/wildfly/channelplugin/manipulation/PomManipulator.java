package org.wildfly.channelplugin.manipulation;

import java.io.IOException;
import java.io.Writer;
import java.util.Stack;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import org.apache.http.util.Asserts;
import org.apache.maven.model.Dependency;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.commonjava.maven.ext.common.model.Project;
import org.wildfly.channel.MavenArtifact;

/**
 * Provides functionality to manipulate properties and dependencies in a POM file.
 */
public class PomManipulator {

    private static final String DEPENDENCY_MANAGEMENT_PATH = "/project/dependencyManagement/dependencies";
    private static final String DEPENDENCIES = "dependencies";

    private final Project project;
    private final ModifiedPomXMLEventReader eventReader;
    private final StringBuilder content;
    private boolean closed = false;

    /**
     * @param project Project instance
     * @param executionRootDirectory the execution directory path (the top level module)
     * @param produceEffectiveChannel upon writing the pom, should the manipulator also produce and effective channel
     *                               file?
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

    public void overrideDependencyVersion(Dependency d, String newVersion) throws XMLStreamException {
        PomHelper.setDependencyVersion(eventReader, d.getGroupId(), d.getArtifactId(), d.getVersion(),
                newVersion, project.getModel());
    }

    public void overrideDependencyVersion(Dependency d, String oldVersionString, String newVersion) throws XMLStreamException {
        PomHelper.setDependencyVersion(eventReader, d.getGroupId(), d.getArtifactId(), oldVersionString,
                newVersion, project.getModel());
    }

    public boolean overrideProperty(String propertyName, String propertyValue) throws XMLStreamException {
        return PomHelper.setPropertyVersion(eventReader, null, propertyName, propertyValue);
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
     * This method attempts to inject new depenendency into at the end of the dependencyManagement section.
     * <p>
     * TODO: The dependencyManagement section must be already present in the POM, it's not created if it's missing.
     */
    static void injectManagedDependency(ModifiedPomXMLEventReader eventReader, MavenArtifact dep)
            throws XMLStreamException {
        eventReader.rewind();

        Stack<String> stack = new Stack<String>();
        String path = "";

        while (eventReader.hasNext()) {
            XMLEvent event = eventReader.nextEvent();
            if (event.isStartElement()) {
                stack.push(path);
                path = path + "/" + event.asStartElement().getName().getLocalPart();
            } else if (event.isEndElement()) {
                if (event.asEndElement().getName().getLocalPart().equals(DEPENDENCIES)
                        && path.equals(DEPENDENCY_MANAGEMENT_PATH)) {
                    eventReader.mark(0);
                    eventReader.replaceMark(0, String.format(
                            "    <!-- dependency injected by wildfly-channel-maven-plugin -->\n" +
                                    "            <dependency>\n" +
                                    "                <groupId>%s</groupId>\n" +
                                    "                <artifactId>%s</artifactId>\n" +
                                    "                <version>%s</version>\n" +
                                    "                <type>%s</type>\n" +
                                    "            </dependency>\n" +
                                    "        </dependencies>",
                            dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getExtension()
                    ));
                    eventReader.clearMark(0);
                    break;
                }

                path = stack.pop();
            }
        }
    }

}
