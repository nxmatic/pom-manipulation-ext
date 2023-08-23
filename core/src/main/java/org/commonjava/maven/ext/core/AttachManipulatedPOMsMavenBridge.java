/*
 * Copyright (C) 2012 Red Hat, Inc.
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
package org.commonjava.maven.ext.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AttachManipulatedPOMsMavenBridge {

    public static final String GOAL_ATTACH_MODIFIED_POMS = "attach-manipulated-poms";
    public static final String EXTENSION_ARTIFACT_ID = "pom-manipulation-ext";
    public static final String EXTENSION_GROUP_ID = "org.commonjava.maven.ext";
    protected final MavenSession session;
    public static final String REPORT_KEY = "manipulation-report";

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    public AttachManipulatedPOMsMavenBridge(MavenSession session) {
        this.session = session;
    }

    /**
     * Inject the JSON report including the new POM file paths
     * in maven user properties for later
     * retreive by the MOJO.
     * 
     * @param model
     * @param json
     */
    public void addReport(final Model model, String json) {
        session.getRequest().setPom(model.getPomFile());
        session.getUserProperties().put(REPORT_KEY, json);
    }

    /**
     * Inject the json report in maven user properties and
     * configure dynammically the MOJO in the project
     *  for attaching the POM files in project builds.
     * 
     * @param rootModel
     */
    public void addMojo(final Model rootModel ) {
        ensureBuildWithPluginsExistInModel(rootModel);

        Optional<Plugin> pluginOptional = rootModel.getBuild().getPlugins().stream()
                .filter(
                        x -> EXTENSION_GROUP_ID.equalsIgnoreCase(x.getGroupId())
                                && EXTENSION_ARTIFACT_ID.equalsIgnoreCase(x.getArtifactId()))
                .findFirst();

        StringBuilder pluginVersion = new StringBuilder();

        try (InputStream inputStream = getClass()
                .getResourceAsStream(
                        "/META-INF/maven/"
                                + EXTENSION_GROUP_ID
                                + "/"
                                + EXTENSION_ARTIFACT_ID
                                + "/pom"
                                + ".properties")) {
            Properties properties = new Properties();
            properties.load(inputStream);
            pluginVersion.append(properties.getProperty("version"));
        } catch (IOException ignored) {
            // TODO we should not ignore in case we have to reuse it
            logger.warn(ignored.getMessage(), ignored);
        }

        Plugin plugin = pluginOptional.orElseGet(
                () -> {
                    Plugin plugin2 = new Plugin();
                    plugin2.setGroupId(EXTENSION_GROUP_ID);
                    plugin2.setArtifactId(EXTENSION_ARTIFACT_ID);
                    plugin2.setVersion(pluginVersion.toString());

                    rootModel.getBuild().getPlugins().add(0, plugin2);
                    return plugin2;
                });

        if (Objects.isNull(plugin.getExecutions())) {
            plugin.setExecutions(new ArrayList<>());
        }

        String pluginRunPhase = System.getProperty("pom-manipulation-replacement-phase", "initialize");
        Optional<PluginExecution> pluginExecutionOptional = plugin.getExecutions().stream()
                .filter(x -> pluginRunPhase.equalsIgnoreCase(x.getPhase()))
                .findFirst();

        PluginExecution pluginExecution = pluginExecutionOptional.orElseGet(
                () -> {
                    PluginExecution pluginExecution2 = new PluginExecution();
                    pluginExecution2.setPhase(pluginRunPhase);

                    plugin.getExecutions().add(pluginExecution2);
                    return pluginExecution2;
                });

        if (Objects.isNull(pluginExecution.getGoals())) {
            pluginExecution.setGoals(new ArrayList<>());
        }

        if (!pluginExecution
                .getGoals()
                .contains(GOAL_ATTACH_MODIFIED_POMS)) {
            pluginExecution.getGoals().add(GOAL_ATTACH_MODIFIED_POMS);
        }

        if (Objects.isNull(plugin.getDependencies())) {
            plugin.setDependencies(new ArrayList<>());
        }

        Optional<Dependency> dependencyOptional = plugin.getDependencies().stream()
                .filter(
                        x -> EXTENSION_GROUP_ID.equalsIgnoreCase(x.getGroupId())
                                && EXTENSION_ARTIFACT_ID.equalsIgnoreCase(x.getArtifactId()))
                .findFirst();

        dependencyOptional.orElseGet(
                () -> {
                    Dependency dependency = new Dependency();
                    dependency.setGroupId(EXTENSION_GROUP_ID);
                    dependency.setArtifactId(EXTENSION_ARTIFACT_ID);
                    dependency.setVersion(pluginVersion.toString());

                    plugin.getDependencies().add(dependency);
                    return dependency;
                });
    }

    public void ensureBuildWithPluginsExistInModel(Model model) {
        if (Objects.isNull(model.getBuild())) {
            model.setBuild(new Build());
        }
        if (Objects.isNull(model.getBuild().getPlugins())) {
            model.getBuild().setPlugins(new ArrayList<>());
        }
    }
}
