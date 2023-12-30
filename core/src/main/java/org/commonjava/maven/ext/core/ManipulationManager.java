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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.profiles.DefaultProfileManager;
import org.apache.maven.profiles.activation.ProfileActivationException;
import org.apache.maven.project.ProjectBuilder;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.ext.annotation.ConfigValue;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.util.JSONUtils;
import org.commonjava.maven.ext.common.util.ProjectComparator;
import org.commonjava.maven.ext.common.util.WildcardMap;
import org.commonjava.maven.ext.core.impl.Manipulator;
import org.commonjava.maven.ext.core.impl.PreparseGroovyManipulator;
import org.commonjava.maven.ext.core.state.CommonState;
import org.commonjava.maven.ext.core.state.DependencyState;
import org.commonjava.maven.ext.core.state.RelocationState;
import org.commonjava.maven.ext.io.PomIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Named
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

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Map<String, Manipulator> manipulators;

    private final PomIO pomIO;

    private final ManipulatingExtensionBridge mavenBridge;

    private final PreparseGroovyManipulator preparseGroovyManipulator;

    private final PME jsonReport = new PME();

    @Inject
    public ManipulationManager(Map<String, Manipulator> manipulators, PomIO pomIO,
            ManipulatingExtensionBridge mavenBridge, PreparseGroovyManipulator preparseGroovyManipulator) {
        this.manipulators = manipulators;
        this.pomIO = pomIO;
        this.mavenBridge = mavenBridge;
        this.preparseGroovyManipulator = preparseGroovyManipulator;
    }

    public void init(ManipulationSession session) throws ManipulationException {
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
            deprecatedUsage.forEach((k, v) -> logger.warn(
                    "Located deprecated property {} in user properties (with matcher of {})", k, v));
            if (deprecatedDisabled) {
                throw new ManipulationException(
                        "Deprecated properties are being used. Either remove them or set enabledDeprecatedProperties=true",
                        new Object[0]);
            }
        }

        session.initManipulators(manipulators.values());
        CommonState cState = new CommonState(session.getUserProperties());
        DependencyState dState = session.getState(DependencyState.class);
        if (!dState.getDependencyOverrides().isEmpty() && cState.getStrictDependencyPluginPropertyValidation() != 0) {
            logger.warn("Disabling strictPropertyValidation as dependencyOverrides are enabled");
            cState.setStrictDependencyPluginPropertyValidation(0);
        }

        session.setState(cState);
    }

    /**
     * Encapsulates {@link #applyManipulations(List)}
     *
     * @param session the container session for manipulation.
     * @throws ManipulationException if an error occurs.
     */
    public void scanAndApply(ManipulationSession session) throws ManipulationException {
        preparseGroovyManipulator.applyChanges(session);
        if (!session.getPom().exists()) {
            throw new ManipulationException("Manipulation engine disabled. Project {} cannot be found.",
                    session.getPom());
        }
        List<Project> currentProjects = pomIO.parseProject(session.getPom());
        session.getPreviousReport().ifPresent(reportx -> currentProjects.stream().forEach(project -> project.getKey()));
        List<Project> originalProjects = new ArrayList<>();
        currentProjects.forEach(p -> originalProjects.add(new Project(p)));
        Project originalExecutionRoot = (Project) originalProjects.get(0);
        if (!originalExecutionRoot.isExecutionRoot()) {
            throw new ManipulationException("First project is not execution root : {}", originalProjects);
        }
        session.getActiveProfiles().addAll(parseActiveProfiles(session, currentProjects));
        session.setProjects(currentProjects);
        Set<Project> changed = applyManipulations(session.manipulators, currentProjects);
        if (changed.isEmpty()) {
            return;
        }
        logger.info("Maven-Manipulation-Extension: Completed with changed: {}", currentProjects);
        Project newExecutionRoot = (Project) changed.stream()
                                                    .filter(Project::isExecutionRoot)
                                                    .findFirst()
                                                    .orElseGet(
                                                            () -> (Project) currentProjects.stream()
                                                                                           .filter(Project::isExecutionRoot)
                                                                                           .findFirst()
                                                                                           .get());
        if (!Boolean.parseBoolean(session.getUserProperties().getProperty("manipulationWriteChanged", "true"))) {
            pomIO.writeTemporaryPOMs(changed);
            mavenBridge.addMojo(newExecutionRoot.getModel());
        } else {
            logger.debug("Maven-Manipulation-Extension: Rewrite changed");
            pomIO.writePOMs(changed);

            try {
                new File(session.getTargetDir().getParentFile(), MARKER_FILE).createNewFile();
            } catch (IOException error) {
                logger.error("Unable to create marker file", error);
                throw new ManipulationException("Marker file creation failed", error);
            }
        }

        jsonReport.getGav().setPVR(newExecutionRoot.getKey());
        jsonReport.getGav()
                       .setOriginalGAV(session.getPreviousReport()
                                              .map(reportx -> reportx.getGav().getOriginalGAV())
                                              .orElseGet(() -> originalExecutionRoot.getKey().toString()));
        WildcardMap<ProjectVersionRef> map = session.getState(RelocationState.class) == null ? new WildcardMap<>()
                : session.getState(RelocationState.class).getDependencyRelocations();
        String report = ProjectComparator.compareProjects(session, jsonReport, map, originalProjects,
                currentProjects);
        logger.info("{}{}", System.lineSeparator(), report);
        String reportTxtOutputFile = session.getUserProperties().getProperty("reportTxtOutputFile", "");

        try {
            session.getTargetDir().mkdir();
            if (StringUtils.isNotEmpty(reportTxtOutputFile)) {
                File reportFile = new File(reportTxtOutputFile);
                FileUtils.writeStringToFile(reportFile, report, StandardCharsets.UTF_8);
            }

            String reportJsonOutputFile = session.getUserProperties()
                                                 .getProperty("reportJSONOutputFile", session.getTargetDir()
                                                         + File.separator + "alignmentReport.json");

            try (FileWriter writer = new FileWriter(reportJsonOutputFile)) {
                writer.write(JSONUtils.jsonToString(jsonReport));
            }
        } catch (IOException cause) {
            logger.error("Unable to create result file", cause);
            throw new ManipulationException("Marker/result file creation failed", cause);
        }

        if (!Boolean.parseBoolean(session.getUserProperties().getProperty("manipulationWriteChanged", "true"))) {
            mavenBridge.addReport(session.getSession(), newExecutionRoot.getModel(), jsonReport);
        }

        logger.info("Maven-Manipulation-Extension: Finished.");
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    private Set<String> parseActiveProfiles(ManipulationSession session, List<Project> projects)
            throws ManipulationException {
        final Set<String> activeProfiles = new HashSet<>();
        final DefaultProfileManager dpm = new DefaultProfileManager(session.getSession().getContainer(),
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

    /**
     * After projects are scanned for modifications, apply any modifications and rewrite POMs as needed. This method
     * performs the following:
     * <ul>
     * <li>read the raw models (uninherited, with only a bare minimum interpolation) from disk to escape any
     * interpretation happening during project-building</li>
     * <li>apply any manipulations
     * <li>rewrite any POMs that were changed</li>
     * </ul>
     * 
     * @param manipulators the ordered list of Manipulators from the session
     * @param projects the list of Projects to apply the changes to.
     * @return collection of the changed projects.
     * @throws ManipulationException if an error occurs.
     */
    private Set<Project> applyManipulations(List<Manipulator> manipulators, List<Project> projects)
            throws ManipulationException {
        final Set<Project> changed = new HashSet<>();
        for (final Manipulator manipulator : manipulators) {
            logger.info("Running manipulator {}", manipulator.getClass().getName());
            final Set<Project> mChanged = manipulator.applyChanges(projects);

            if (mChanged != null) {
                changed.addAll(mChanged);
            }
        }

        if (changed.isEmpty()) {
            logger.info("Maven-Manipulation-Extension: No changes.");
        }

        return changed;
    }

}
