/*
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver-maven-plugin] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.manip;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.ext.common.json.GAV;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.core.ManipulatingExtensionBridge;
import org.commonjava.maven.ext.core.ManipulationManager;

/** Works in conjunction with ManipulatingLifeCycleParticipant. */
@Mojo(name = ManipulatingExtensionBridge.GOAL_ATTACH_MODIFIED_POMS, instantiationStrategy = InstantiationStrategy.SINGLETON, threadSafe = true)
public class AttachManipulatedPOMs extends AbstractMojo {

    final private MavenSession mavenSession;

    final private ManipulatingExtensionBridge mavenBridge;

    @Parameter(property = "dummy", required = false, defaultValue = "")
    private Boolean dummy;

    @Inject
    AttachManipulatedPOMs(MavenSession session, ManipulatingExtensionBridge bridge) {
        mavenSession = session;
        mavenBridge = bridge;
    }

    @Override
    public void execute() throws MojoExecutionException {
        Optional<PME> optReport = mavenBridge.readReport(mavenSession);
        if (optReport.isEmpty()) {
            getLog().warn(ManipulatingExtensionBridge.GOAL_ATTACH_MODIFIED_POMS
                    + "shouldn't be executed alone. The Mojo " + "is a part of the plugin and executed automatically.");
            return;
        }

        mavenSession.getUserProperties().setProperty(ManipulationManager.REPORT_USER_PROPERTY_KEY, "{}");

        try {

            attachModifiedPomFilesToTheProject(mavenSession.getAllProjects(),
                    optReport.get().getModules().stream().map(m -> m.getGav()).collect(Collectors.toSet()), null, false,
                    new ConsoleLogger());

        } catch (Exception ex) {
            throw new MojoExecutionException(
                    "Unable to execute goal: " + ManipulatingExtensionBridge.GOAL_ATTACH_MODIFIED_POMS, ex);
        }
    }

    static class GAVBuilder {

        GAV underConstruction = new GAV();

        GAVBuilder with(ProjectVersionRef pvr) {
            underConstruction.setPVR(pvr);

            return this;
        }

        GAV build() {
            return underConstruction;
        }

        GAVBuilder with(Parent parent) {
            underConstruction.setGroupId(parent.getGroupId());
            underConstruction.setGroupId(parent.getArtifactId());
            underConstruction.setVersion(parent.getVersion());

            return this;
        }

        GAVBuilder with(Model model) {
            Optional.ofNullable(model.getParent()).ifPresent(parent -> {
                underConstruction.setGroupId(parent.getGroupId());
                underConstruction.setVersion(parent.getVersion());
            });
            Optional.ofNullable(model.getGroupId()).ifPresent(underConstruction::setGroupId);
            Optional.ofNullable(model.getVersion()).ifPresent(underConstruction::setVersion);
            underConstruction.setArtifactId(model.getArtifactId());

            return this;
        }

        GAVBuilder with(MavenProject project) {
            return with(project.getModel());
        }

        static GAV from(Model model) {
            return new GAVBuilder().with(model).build();
        }

        static GAV from(MavenProject project) {
            return new GAVBuilder().with(project).build();
        }

        static GAV from(Parent parent) {
            return new GAVBuilder().with(parent).build();
        }
    }

    void attachModifiedPomFilesToTheProject(List<MavenProject> projects, Set<GAV> gavs, String version,
            Boolean resolveProjectVersion, Logger logger) throws IOException, XmlPullParserException {
        for (MavenProject project : projects) {
            Model model = loadInitialModel(project.getFile());
            GAV initalProjectGAV = GAVBuilder.from(model);

            logger.debug("about to change file pom for: " + initalProjectGAV);

            if (gavs.contains(initalProjectGAV)) {
                model.setVersion(version);

                if (model.getScm() != null && project.getModel().getScm() != null) {
                    model.getScm().setTag(project.getModel().getScm().getTag());
                }
            }

            if (model.getParent() != null) {
                GAV parentGAV = GAVBuilder.from(model.getParent());

                if (gavs.contains(parentGAV)) {
                    // parent has been modified
                    model.getParent().setVersion(version);
                }
            }

            if (resolveProjectVersion) {
                resolveProjectVersionVariable(version, model);
            }

            File newPom = createPomDumpFile();
            writeModelPom(model, newPom);
            logger.debug("    new pom file created for " + initalProjectGAV + " under " + newPom);

            setProjectPomFile(project, newPom, logger);
            logger.debug("    pom file set");
        }
    }

    Model loadInitialModel(File pomFile) throws IOException, XmlPullParserException {
        try (FileReader fileReader = new FileReader(pomFile)) {
            return new MavenXpp3Reader().read(fileReader);
        }
    }

    static final String PROJECT_VERSION = "${project.version}";

    void resolveProjectVersionVariable(String version, Model model) {
        // resolve project.version in properties
        if (model.getProperties() != null) {
            for (Map.Entry<Object, Object> entry : model.getProperties().entrySet()) {
                if (PROJECT_VERSION.equals(entry.getValue())) {
                    entry.setValue(version);
                }
            }
        }

        // resolve project.version in dependencies
        if (model.getDependencies() != null) {
            for (Dependency dependency : model.getDependencies()) {
                if (PROJECT_VERSION.equals(dependency.getVersion())) {
                    dependency.setVersion(version);
                }
            }
        }

        // resole project.version in dependencyManagement
        if (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null) {
            for (Dependency dependency : model.getDependencyManagement().getDependencies()) {
                if (PROJECT_VERSION.equals(dependency.getVersion())) {
                    dependency.setVersion(version);
                }
            }
        }

        // resolve project.version in plugins
        if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
            for (Plugin plugin : model.getBuild().getPlugins()) {
                if (plugin.getDependencies() != null) {
                    for (Dependency dependency : plugin.getDependencies()) {
                        if (PROJECT_VERSION.equals(dependency.getVersion())) {
                            dependency.setVersion(version);
                        }
                    }
                }
            }
        }

        // resolve project.version in pluginManagement
        if (model.getBuild() != null && model.getBuild().getPluginManagement() != null
                && model.getBuild().getPluginManagement().getPlugins() != null) {
            for (Plugin plugin : model.getBuild().getPluginManagement().getPlugins()) {
                if (plugin.getDependencies() != null) {
                    for (Dependency dependency : plugin.getDependencies()) {
                        if (PROJECT_VERSION.equals(dependency.getVersion())) {
                            dependency.setVersion(version);
                        }
                    }
                }
            }
        }
    }

    File createPomDumpFile() throws IOException {
        File tmp = File.createTempFile("pom", ".jgitver-maven-plugin.xml");
        tmp.deleteOnExit();
        return tmp;
    }

    void writeModelPom(Model mavenModel, File pomFile) throws IOException {
        try (FileWriter fileWriter = new FileWriter(pomFile)) {
            new MavenXpp3Writer().write(fileWriter, mavenModel);
        }
    }

    void setProjectPomFile(MavenProject project, File newPom, Logger logger) {
        try {
            project.setPomFile(newPom);
        } catch (Throwable unused) {
            logger.warn("maven version might be <= 3.2.4, changing pom file using old mechanism");
            File initialBaseDir = project.getBasedir();
            project.setFile(newPom);
            File newBaseDir = project.getBasedir();
            try {
                if (!initialBaseDir.getCanonicalPath().equals(newBaseDir.getCanonicalPath())) {
                    changeBaseDir(project, initialBaseDir);
                }
            } catch (Exception ex) {
                GAV gav = GAVBuilder.from(project);
                logger.warn("cannot reset basedir of project " + gav.toString(), ex);
            }
        }
    }

    public static void changeBaseDir(MavenProject project, File initialBaseDir)
            throws NoSuchFieldException, IllegalAccessException {
        Field basedirField = project.getClass().getField("basedir");
        basedirField.setAccessible(true);
        basedirField.set(project, initialBaseDir);
    }
}
