package org.wildfly.channelplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.io.PomIO;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;

public abstract class AbstractChannelMojo extends AbstractMojo {

    @Inject
    MavenProject mavenProject;

    @Inject
    PomIO pomIO;

    /**
     * Returns PME representation of current project module and its submodules.
     */
    protected List<Project> parsePmeProjects() throws ManipulationException {
        return pomIO.parseProject(mavenProject.getModel().getPomFile());
    }

    protected Project findRootProject(List<Project> projects) throws MojoExecutionException {
        Optional<Project> rootProjectOptional = projects.stream().filter(Project::isExecutionRoot).findFirst();
        if (rootProjectOptional.isEmpty()) {
            throw new MojoExecutionException("Can't identify root project.");
        }
        return rootProjectOptional.get();
    }

}
