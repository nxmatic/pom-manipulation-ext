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

import static org.commonjava.maven.ext.common.util.ProfileUtils.PROFILE_SCANNING;
import static org.commonjava.maven.ext.common.util.ProfileUtils.PROFILE_SCANNING_DEFAULT;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.profiles.DefaultProfileManager;
import org.apache.maven.profiles.activation.ProfileActivationException;
import org.apache.maven.project.ProjectBuilder;
import org.commonjava.maven.ext.annotation.ConfigValue;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.bridge.ManipulationExtensionBridge;
import org.commonjava.maven.ext.core.impl.Manipulator;
import org.commonjava.maven.ext.core.impl.PreparseGroovyManipulator;
import org.commonjava.maven.ext.core.util.PropertiesUtils;
import org.commonjava.maven.ext.io.ConfigIO;
import org.commonjava.maven.ext.io.PomIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vavr.CheckedConsumer;
import io.vavr.control.Try;

/**
 * Coordinates manipulation of the POMs in a build, by providing methods to read the project set from files ahead of the
 * build proper (using {@link ProjectBuilder}), then other methods to coordinate all potential {@link Manipulator}
 * implementations (along with the {@link PomIO} raw-model reader/rewriter).
 * <p>
 * Sequence of calls:
 * <ol>
 * <li>{@link #init(ManipulationSession)}</li>
 * <li>{@link #applyManipulations(List, List)}</li>
 * </ol>
 *
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@Singleton
public class ManipulationManager {
    private static final String MARKER_PATH = "target";

    public static final String MARKER_FILE = MARKER_PATH + File.separatorChar + "pom-manip-ext-marker.txt";

    public static final String REPORT_JSON_DEFAULT = "alignmentReport.json";

    public static final String REPORT_USER_PROPERTY_KEY = "pom-manipulation-report";

    @ConfigValue(docIndex = "../index.html#summary-logging")
    public static final String REPORT_TXT_OUTPUT_FILE = "reportTxtOutputFile";

    @ConfigValue(docIndex = "../index.html#summary-logging")
    public static final String REPORT_JSON_OUTPUT_FILE = "reportJSONOutputFile";

    @ConfigValue(docIndex = "../index.html#deprecated-and-unknown-properties")
    public static final String DEPRECATED_PROPERTIES = "enabledDeprecatedProperties";

    @ConfigValue(docIndex = "../index.html#write-changed")
    public static final String REWRITE_CHANGED = "manipulationWriteChanged";

    final Logger logger = LoggerFactory.getLogger(getClass());

    private final ConfigIO configIO;

    final PomIO pomIO;

    final ManipulationExtensionBridge mavenBridge;

    private final Set<Manipulator> manipulators;

    private final PreparseGroovyManipulator preparseGroovyManipulator;

    @Inject
    public ManipulationManager(ConfigIO configIO, PomIO pomIO, ManipulationExtensionBridge mavenBridge,
            Set<Manipulator> manipulators, PreparseGroovyManipulator preparseGroovyManipulator) {
        this.configIO = configIO;
        this.pomIO = pomIO;
        this.mavenBridge = mavenBridge;
        this.manipulators = manipulators;
        this.preparseGroovyManipulator = preparseGroovyManipulator;
    }

    final Map<File, ManipulationSession> sessions = new ConcurrentHashMap<>();

    public void init(ManipulationSession session) throws ManipulationException {
        logger.debug(manipulators.toString());

        // check conditions
        File pomFile = session.getPomFile();
        if (!pomFile.exists()) {
            throw new ManipulationException("Manipulation engine disabled. Project {} cannot be found.",
                    session.getPomFile());
        }
        File multiModuleProjectDirectory = session.getMultiModuleProjectDirectory();
        if (sessions.containsKey(multiModuleProjectDirectory)) {
            throw new ManipulationException("Manipulation engine disabled: Directory {} already known",
                    multiModuleProjectDirectory);
        }
        sessions.put(session.getMavenSession().getRequest().getMultiModuleProjectDirectory(), session);
        // process
        logger.debug("Initialising ManipulationManager with user properties {}", session.getUserProperties());
        boolean deprecatedDisabled = !Boolean.parseBoolean(
                session.getUserProperties().getProperty("enabledDeprecatedProperties", "false"));
        HashMap<String, String> deprecatedUsage = new HashMap<>();
        session.getUserProperties().stringPropertyNames().forEach(p -> {
            if (!p.equals("maven.repo.local")
                    && ConfigList.allConfigValues.keySet().stream().noneMatch(p::startsWith)) {
                logger.warn("Unknown configuration value {}", p);
            }

            logger.debug("Examining for deprecated properties for {}", p);
            ConfigList.allConfigValues.entrySet()
                                      .stream()
                                      .filter(e -> p.startsWith(e.getKey()))
                                      .filter(Entry::getValue)
                                      .forEach(up -> deprecatedUsage.put(p, up.getKey()));
        });
        if (deprecatedUsage.size() > 0) {
            deprecatedUsage.forEach((k,
                    v) -> logger.warn("Located deprecated property {} in user properties (with matcher of {})", k, v));
            if (deprecatedDisabled) {
                throw new ManipulationException(
                        "Deprecated properties are being used. Either remove them or set enabledDeprecatedProperties=true",
                        new Object[0]);
            }
        }
        session.init(manipulators);
    }

    class Pipeline {

        final ManipulationSession manipulationSession;

        List<Project> projects;

        Pipeline(ManipulationSession thatSession) {
            manipulationSession = thatSession;
        }

        Try<Project> processSessionIfEligible() {
            if (!this.isEnabled(manipulationSession)) {
                return Try.success(null);
            }
            return Try.success(null)
                      .mapTry((__) -> this.handleConfigPrecedence())
                      .mapTry((__) -> this.scanForProjects())
                      .mapTry((projects) -> this.applyManipulations(projects));
        }

        boolean isEnabled(ManipulationSession session) {
            if (!session.isEnabled()) {
                logger.info("Manipulation engine disabled via command-line option");
                return false;
            }
            return true;
        }

        ManipulationSession handleConfigPrecedence() {
            Properties config = Try.of(() -> configIO.parse(manipulationSession.getPomFile().getParentFile())).get();
            PropertiesUtils.handleConfigPrecedence(manipulationSession.getUserProperties(), config);
            return manipulationSession;
        }

        List<Project> scanForProjects() throws ManipulationException {
            preparseGroovyManipulator.applyChanges(manipulationSession);
            return pomIO.parseProject(manipulationSession.getPomFile());
        }

        Project applyManipulations(List<Project> projects) throws ManipulationException {
            return ManipulationManager.this.applyManipulations(manipulationSession, projects);
        }
    }

    final Consumer<Throwable> thrower = CheckedConsumer.<Throwable> of(cause -> {
        throw cause;
    }).unchecked();

    public Try<Project> scanAndApply(ManipulationSession session) throws ManipulationException {
        File pomFile = Optional.of(session.getMavenSession().getRequest())
                               .map(MavenExecutionRequest::getPom)
                               .orElseThrow();
        return scanAndApply(session, pomFile);
    }

    public Try<Project> scanAndApply(ManipulationSession session, File pomFile) throws ManipulationException {
        Optional<File> originalHolder = session.setPomFile(pomFile);
        try {
            return new Pipeline(session).processSessionIfEligible();
        } finally {
            session.setPomFile(originalHolder.orElse(null));
        }
    }

    /**
     * Encapsulates {@link #applyManipulations(List)}
     *
     * @param session the container session for manipulation.
     * @throws ManipulationException if an error occurs.
     */
    private Project applyManipulations(ManipulationSession session, List<Project> projects)
            throws ManipulationException {
        Project executionRoot = projects.get(0);
        if (!executionRoot.isExecutionRoot()) {
            throw new ManipulationException("First project is not execution root : {}", projects);
        }
        // let's apply the changes
        session.getActiveProfiles().addAll(parseActiveProfiles(session, projects));
        session.manipulate(projects);
        return executionRoot;
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    private Set<String> parseActiveProfiles(ManipulationSession session, List<Project> projects)
            throws ManipulationException {
        final Set<String> activeProfiles = new HashSet<>();
        final DefaultProfileManager dpm = new DefaultProfileManager(session.getMavenSession().getContainer(),
                session.getUserProperties());

        logger.debug("Explicitly activating {}", session.getActiveProfiles());
        dpm.explicitlyActivate(session.getActiveProfiles());

        for (Project p : projects) {
            // We clone the original profile here to prevent the DefaultProfileManager
            // affecting the original list
            // during its activation calculation.
            p.getModel()
             .getProfiles()
             .stream()
             .filter(newProfile -> !dpm.getProfilesById().containsKey(newProfile.getId()))
             .forEach(newProfile -> dpm.addProfile(newProfile.clone()));

            try {
                List<org.apache.maven.model.Profile> ap = dpm.getActiveProfiles();
                activeProfiles.addAll(
                        ap.stream().map(org.apache.maven.model.Profile::getId).collect(Collectors.toList()));
            } catch (ProfileActivationException e) {
                throw new ManipulationException("Activation detection failure", e);
            }
        }

        if (logger.isDebugEnabled()) {
            final String profileScanningProp = session.getUserProperties()
                                                      .getProperty(PROFILE_SCANNING, PROFILE_SCANNING_DEFAULT);
            final boolean profileScanning = Boolean.parseBoolean(profileScanningProp);
            logger.debug("Will {}scan all profiles and returning active profiles of {}", profileScanning ? "not " : "",
                    activeProfiles);
        }

        return activeProfiles;
    }

    public Optional<ManipulationSession> getSession(File pomFile) {
        Path pomPath = pomFile.toPath().toAbsolutePath();
        return sessions.entrySet()
                       .stream()
                       .filter((e) -> pomPath.startsWith(e.getKey().toPath().toAbsolutePath()))
                       .findFirst()
                       .map(Entry::getValue);
    }

}
