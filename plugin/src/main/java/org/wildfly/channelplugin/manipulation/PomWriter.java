package org.wildfly.channelplugin.manipulation;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Optional;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

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
 *
 * This implementation is able to override versions of dependencies (both managed and non-managed) when:
 * <li>the version string is hardcoded in the `dependency.version` element,</li>
 * <li>or the `dependency.version` element references a property, which itself contains the version string
 * (not referencing other properties).</li>
 *
 * This implementation reuses code from the versions-maven-plugin. The advantage of the versions-maven-plugin code
 * is that it allows modifying only specific segments of a POM file, without the need to parse the whole POM into
 * a model and serialize that model back into POM. Thanks to that, the POM file can retain it's original formatting,
 * minimizing number of changes performed in the file -> more readable changes.
 *
 * TODO: Modification of properties in profiles is currently not supported.
 */
public class PomWriter {

    public static void manipulatePom(Project project, ArrayList<MavenArtifact> dependenciesToUpgrade) {
        try {
            XMLInputFactory inputFactory = XMLInputFactory2.newInstance();
            inputFactory.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, Boolean.TRUE);
            StringBuilder content = PomHelper.readXmlFile(project.getPom());
            ModifiedPomXMLEventReader eventReader = new ModifiedPomXMLEventReader(content, inputFactory,
                    project.getPom().getPath());

            DependencyModel dependencyModel = new DependencyModel(project.getModel());

            for (MavenArtifact dependencyToUpgrade: dependenciesToUpgrade) {
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
                    PomHelper.setPropertyVersion(eventReader, null, versionPropertyName,
                            dependencyToUpgrade.getVersion());
                } else {
                    Dependency d = locatedDependency.get();
                    PomHelper.setDependencyVersion(eventReader, d.getGroupId(), d.getArtifactId(), d.getVersion(),
                            dependencyToUpgrade.getVersion(), project.getModel());
                }
            }

            writeFile(project.getPom(), content);
        } catch (XMLStreamException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeFile(File outFile, StringBuilder content)
            throws IOException {
        try (Writer writer = WriterFactory.newXmlWriter(outFile)) {
            IOUtil.copy(content.toString(), writer);
        }
    }
}
