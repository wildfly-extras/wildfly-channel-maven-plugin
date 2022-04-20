package org.wildfly.channelplugin.manipulation;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.Stack;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import org.apache.maven.model.Dependency;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.stax2.XMLInputFactory2;
import org.commonjava.maven.ext.common.model.Project;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channelplugin.ChannelPluginLogger;
import org.wildfly.channelplugin.utils.VersionUtils;
import org.wildfly.channelplugin.utils.DependencyModel;

/**
 * Provides functionality to override dependencies versions in POM files.
 * <p>
 * This implementation is able to override versions of dependencies (both managed and non-managed) when:
 * <li>the version string is hardcoded in the `dependency.version` element,</li>
 * <li>or the `dependency.version` element references a property, which itself contains the version string
 * (not referencing other properties).</li>
 * <p>
 * This implementation reuses code from the versions-maven-plugin. The advantage of the versions-maven-plugin code
 * is that it allows modifying only specific segments of a POM file, without the need to parse the whole POM into
 * a model and serialize that model back into POM. Thanks to that, the POM file can retain it's original formatting,
 * minimizing number of changes performed in the file -> more readable changes.
 * <p>
 * TODO: Modification of properties in profiles is currently not supported.
 */
public class PomWriter {

    private static final String DEPENDENCY_MANAGEMENT_PATH = "/project/dependencyManagement/dependencies";
    private static final String DEPENDENCIES = "dependencies";

    public static void manipulatePom(Project project, ArrayList<MavenArtifact> dependenciesToUpgrade,
            ArrayList<MavenArtifact> dependenciesToInject) {
        try {
            XMLInputFactory inputFactory = XMLInputFactory2.newInstance();
            inputFactory.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, Boolean.TRUE);
            StringBuilder content = PomHelper.readXmlFile(project.getPom());
            ModifiedPomXMLEventReader eventReader = new ModifiedPomXMLEventReader(content, inputFactory,
                    project.getPom().getPath());

            DependencyModel dependencyModel = new DependencyModel(project.getModel());

            for (MavenArtifact dependencyToUpgrade : dependenciesToUpgrade) {
                Optional<Dependency> locatedDependency = dependencyModel.getDependency(
                        dependencyToUpgrade.getGroupId(),
                        dependencyToUpgrade.getArtifactId(),
                        dependencyToUpgrade.getExtension(),
                        dependencyToUpgrade.getClassifier());
                if (locatedDependency.isEmpty()) {
                    ChannelPluginLogger.LOGGER.severe("Couldn't locate dependency " + dependencyToUpgrade);
                    continue;
                } else if (VersionUtils.isProperty(locatedDependency.get().getVersion())) {
                    String versionPropertyName = VersionUtils.extractPropertyName(
                            locatedDependency.get().getVersion());
                    versionPropertyName = followProperties(project.getModel().getProperties(), versionPropertyName);
                    PomHelper.setPropertyVersion(eventReader, null, versionPropertyName,
                            dependencyToUpgrade.getVersion());
                } else {
                    Dependency d = locatedDependency.get();
                    PomHelper.setDependencyVersion(eventReader, d.getGroupId(), d.getArtifactId(), d.getVersion(),
                            dependencyToUpgrade.getVersion(), project.getModel());
                }
            }

            for (MavenArtifact dep: dependenciesToInject) {
                injectManagedDependency(eventReader, dep);
            }

            writeFile(project.getPom(), content);
        } catch (XMLStreamException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This method attempts to inject new depenendency into at the end of the dependencyManagement section.
     *
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

    static void writeFile(File outFile, StringBuilder content)
            throws IOException {
        try (Writer writer = WriterFactory.newXmlWriter(outFile)) {
            IOUtil.copy(content.toString(), writer);
        }
    }

    /**
     * If a property references another property (possibly recursively), this method returns the final referenced
     * property name.
     *
     * This doesn't support cases when a property value is a composition of multiple properties, or a composition
     * of a property and a string.
     */
    static String followProperties(Properties properties, String propertyName) {
        String propertyValue = (String) properties.get(propertyName);
        if (propertyValue == null) {
            // couldn't track referenced property, return the last known property name
            return propertyName;
        }
        if (VersionUtils.isProperty(propertyValue)) {
            // the property value is also a property reference -> follow the chain
            String newPropertyName = VersionUtils.extractPropertyName(propertyValue);
            return followProperties(properties, newPropertyName);
        } else {
            return propertyName;
        }
    }
}
