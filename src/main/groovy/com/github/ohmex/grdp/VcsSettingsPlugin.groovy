package com.github.ohmex.grdp

import com.github.ohmex.grdp.dependency.GitDependency
import com.github.ohmex.grdp.dependency.SvnDependency
import com.github.ohmex.grdp.dependency.VcsDependency
import com.github.ohmex.grdp.util.CredentialsHelper
import com.github.ohmex.grdp.util.DependenciesHelper
import com.github.ohmex.grdp.util.GitHelper
import com.github.ohmex.grdp.util.SvnHelper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.initialization.DefaultProjectDescriptor

class VcsSettingsPlugin implements Plugin<Settings> {

    private DependenciesHelper dependencies = new DependenciesHelper()
    private GroovyShell shell = new GroovyShell()

    void apply(Settings settings) {
        // Adding configuration method
        settings.metaClass.vcs = VcsProperties.instance.&apply

        CredentialsHelper.init(settings.gradle)

        settings.gradle.settingsEvaluated { Settings sett ->
            VcsProperties.instance.resolve(sett)
            resolveDependenciesRecursively(sett, sett.projectDescriptorRegistry.allProjects)
            cleanup()
        }

        // Adding created vcs projects dependencies for each project
        settings.gradle.afterProject { Project project ->
            dependencies.get(project.name).each { VcsDependency dep ->
                if (dep.includeProject && dep.addDependency) {
                    project.dependencies.add(dep.configName, project.project(projectName(dep)))
                }
            }
        }
    }

    void resolveDependenciesRecursively(Settings settings, Set<DefaultProjectDescriptor> projects) {
        Set<DefaultProjectDescriptor> newProjects = new HashSet<>()

        // Building vcs dependencies list by invoking build.gradle#vcs() method for each project
        projects.each { DefaultProjectDescriptor project ->

            if (project.buildFile.exists()) {
                Script script = shell.parse(project.buildFile)

                // Checks if there is a vcs dependencies configuration method
                if (script.metaClass.respondsTo(script, 'vcs')) {
                    // Adding git method
                    script.binding.setVariable('git', { Map params ->
                        dependencies.add(project.name, new GitDependency(params))
                    })

                    // Adding svn method
                    script.binding.setVariable('svn', { Map params ->
                        dependencies.add(project.name, new SvnDependency(params))
                    })

                    // Executing configuration method
                    script.vcs()

                    // Initializing vcs repositories. Including vcs project dependencies.
                    dependencies.get(project.name).each { VcsDependency d ->
                        if (d instanceof SvnDependency) {
                            SvnHelper.init((SvnDependency) d)
                        }
                        if (d instanceof GitDependency) {
                            GitHelper.init((GitDependency) d)
                        }

                        if (d.includeProject) {
                            settings.include(projectName(d))
                            DefaultProjectDescriptor newProject = settings.project(projectName(d))
                            newProject.projectDir = d.projectDir
                            newProjects.add(newProject)
                        }
                    }
                }
            }
        }

        // If we have new projects we should process them too
        if (newProjects) resolveDependenciesRecursively(settings, newProjects)
    }

    private void cleanup() {
        if (VcsProperties.instance.cleanup) {
            // Cleaning up unused directories and files
            File libsDir = VcsProperties.instance.dir
            libsDir.listFiles().each { File dir ->
                boolean found = false
                dependencies.all().each { VcsDependency d ->
                    if (dir == d.repoDir) found = true
                }
                if (!found) dir.deleteDir()
            }
            if (!libsDir.list()) libsDir.deleteDir()
        }
    }

    private static String projectName(VcsDependency dep) {
        return ":${dep.name}"
    }
}
