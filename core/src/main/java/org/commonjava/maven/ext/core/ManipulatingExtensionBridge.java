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

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.common.util.JSONUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is responsible for reporting the manipulated Maven project's POM file and ensuring it is attached to the project's artifact.
 * It sets the manipulated POM as the source and injects a JSON report, which includes the new POM file paths,
 * into the user properties for later retrieval by the MOJO.
 */
@Named
@Singleton
public class ManipulatingExtensionBridge {

    public static final String GOAL_ATTACH_MODIFIED_POMS = "attach-manipulated-poms";
    public static final String EXTENSION_ARTIFACT_ID = "pom-manipulation-ext";
    public static final String EXTENSION_GROUP_ID = "org.commonjava.maven.ext";

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    /**
     * Set the manipulate POM as source and inject the JSON report including the new POM file paths
     * in the user properties for later
     * retrieve by the MOJO.
     * 
     * @param model
     * @param report
     * @throws ManipulationException 
     */
    public void addReport(MavenSession session, Model model, PME report) throws ManipulationException {
        session.getRequest().setPom(model.getPomFile());
        try {
            session.getUserProperties().put(ManipulationManager.REPORT_USER_PROPERTY_KEY, JSONUtils.jsonToString(report));
        } catch (IOException e) {
            throw new ManipulationException("Adding JSON report in user properties failed", e);
        }
    }

    /**
     * Read back the saved report in user properties.
     * 
     * @param mavenSession the running session
     */
    public Optional<PME> readReport(MavenSession mavenSession) {
        final JSONUtils.InternalObjectMapper reportMapper = new JSONUtils.InternalObjectMapper(new ObjectMapper());

        return Optional.ofNullable(mavenSession.getUserProperties()
                .getProperty(ManipulationManager.REPORT_USER_PROPERTY_KEY))
                .map(body -> reportMapper.readValue(body, PME.class));
    }
    
    /**
     * Inject the JSON report in maven user properties and
     * configure dynamically the MOJO in the project
     * for attaching the POM files in project builds.
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
                    //pluginExecution2.set
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

        if (Objects.isNull(pluginExecution.getConfiguration())) {
        	final Xpp3Dom dummyParameter = new Xpp3Dom("dummy");
            final Xpp3Dom configuration = new Xpp3Dom("configuration");
            configuration.addChild(dummyParameter);
			pluginExecution.setConfiguration(configuration);
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
