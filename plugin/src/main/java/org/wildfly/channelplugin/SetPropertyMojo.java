package org.wildfly.channelplugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.wildfly.channel.VersionResult;
import org.wildfly.channelplugin.manipulation.PomManipulator;
import org.wildfly.channelplugin.utils.PMEUtils;

import javax.xml.stream.XMLStreamException;
import java.util.List;

/**
 * Sets property value in the pom.xml of the execution root project to a version of specified stream. The property must
 * be already defined in the pom.xml.
 */
@Mojo(name = "set-property", requiresDirectInvocation = true)
public class SetPropertyMojo extends AbstractChannelMojo {

    /**
     * Name of the property to override.
     */
    @Parameter(property = "property", required = true)
    String property;

    /**
     * Stream G:A. The version of this stream will be used as the new property value.
     */
    @Parameter(property = "stream", required = true)
    String stream;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!mavenSession.getCurrentProject().isExecutionRoot()) {
            // do not perform any work in submodules
            return;
        }

        initChannelSession();

        if (!mavenProject.getModel().getProperties().containsKey(property)) {
            throw new MojoFailureException(String.format("Property %s is not present in this project's pom.xml.", property));
        }

        ProjectRef ga = SimpleProjectRef.parse(stream);
        VersionResult result = channelSession.findLatestMavenArtifactVersion(ga.getGroupId(), ga.getArtifactId(), "pom", null, null);
        if (StringUtils.isBlank(result.getVersion())) {
            throw new MojoFailureException(String.format("Given channels contain no version for %s:%s.", ga.getGroupId(), ga.getArtifactId()));
        }

        try {
            List<Project> projects = PMEUtils.parsePmeProjects(pomIO, mavenProject);
            Project rootProject = PMEUtils.findRootProject(projects);
            PomManipulator manipulator = new PomManipulator(rootProject);
            manipulator.overrideProperty(property, result.getVersion());
            manipulator.writePom();
        } catch (ManipulationException e) {
            throw new MojoExecutionException("Project parsing failed", e);
        } catch (XMLStreamException e) {
            throw new MojoExecutionException("Failed to override the version property.", e);
        }
    }
}
