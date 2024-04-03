package org.wildfly.channelplugin.utils;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.io.PomIO;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for POM Manipulation Extension functionalities.
 */
public final class PMEUtils {

    private PMEUtils() {
    }

    /**
     * Returns PME representation of current project module and its submodules.
     */
    public static List<Project> parsePmeProjects(PomIO pomIO, MavenProject mavenProject) throws ManipulationException {
        return pomIO.parseProject(mavenProject.getModel().getPomFile());
    }

    /**
     * Finds the execution root project.
     */
    public static Project findRootProject(List<Project> projects) throws MojoExecutionException {
        Optional<Project> rootProjectOptional = projects.stream().filter(Project::isExecutionRoot).findFirst();
        if (rootProjectOptional.isEmpty()) {
            throw new MojoExecutionException("Can't identify root project.");
        }
        return rootProjectOptional.get();
    }

}
