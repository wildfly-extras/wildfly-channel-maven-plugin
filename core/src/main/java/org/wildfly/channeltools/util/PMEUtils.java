package org.wildfly.channeltools.util;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.ext.io.PomIO;

public final class PMEUtils {

    private PMEUtils() {
    }

    /**
     * This returns a PME representation of currently processed Maven module.
     *
     * PME (POM Manipulation Extension) is subproject of the PNC (Project Newcastle) productization system. PME does
     * a very similar job to what this Maven module does.
     */
    public static Project parseProject(PomIO pomIO, File pomFile)
            throws ManipulationException {
        List<Project> projects = pomIO.parseProject(pomFile);
        List<Project> roots = projects.stream().filter(Project::isExecutionRoot)
                .collect(Collectors.toList());
        if (roots.size() != 1) {
            throw new IllegalStateException("Couldn't determine the execution root project, candidates are: ["
                    + roots.stream().map(Project::getArtifactId).collect(Collectors.joining(", "))
                    + "]");
        }
        return roots.get(0);
    }
}
