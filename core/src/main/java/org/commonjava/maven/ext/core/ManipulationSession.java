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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.maven.ext.annotation.ConfigValue;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.ext.common.util.JSONUtils;
import org.commonjava.maven.ext.common.util.ManifestUtils;
import org.commonjava.maven.ext.common.util.ProjectComparator;
import org.commonjava.maven.ext.common.util.WildcardMap;
import org.commonjava.maven.ext.core.bridge.ManipulationExtensionBridge;
import org.commonjava.maven.ext.core.impl.Manipulator;
import org.commonjava.maven.ext.core.state.CommonState;
import org.commonjava.maven.ext.core.state.DependencyState;
import org.commonjava.maven.ext.core.state.RelocationState;
import org.commonjava.maven.ext.core.state.State;
import org.commonjava.maven.ext.core.state.VersioningState;
import org.commonjava.maven.ext.core.util.ManipulatorPriorityComparator;
import org.commonjava.maven.ext.io.ConfigIO;
import org.commonjava.maven.ext.io.PomIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vavr.CheckedFunction1;
import io.vavr.CheckedRunnable;
import io.vavr.control.Try;
import lombok.Getter;
import noname.devenv.maven.MultiModuleProjectLifecycleParticipant;

/**
 * Repository for components that help manipulate POMs as needed, and state related to each {@link Manipulator} (which
 * contains configuration and changes to be applied). This is basically a clearing house for state required by the
 * different parts of the manipulator extension.
 *
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@Singleton
public class ManipulationSession implements MavenSessionHandler {
    @ConfigValue(docIndex = "../index.html#disabling-the-extension")
    private static final String MANIPULATIONS_DISABLED_PROP = "manipulation.disable";

    private final Map<Class<?>, State> states = new HashMap<>();

    @Inject
    ManipulationManager manager;
    
    @Inject
    @Named(ManipulationComponent.HINT)
    Provider<MultiModuleProjectLifecycleParticipant> lifecycle;

    @Inject
    private Provider<MavenSession> mavenSessionProvider = () -> {
        throw new IllegalStateException("no maven session provider injected");
    };

    @Inject
    private ConfigIO configIO;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public ManipulationSession() { // should exist for guice instrumentation
    }
    
    @PostConstruct
    void checker() {
        ;
    }

    /**
     * @return Returns the current MavenSession
     */
    public MavenSession getMavenSession() {
        return mavenSessionProvider.get();
    }

    public void setMavenSession(MavenSession mavenSession) {
        this.mavenSessionProvider = () -> mavenSession;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

    /**
     * Determined from {@link Manipulator#getExecutionIndex()} comparisons during
     * {@link #injectManipulators(Set<Manipulator)}.
     *
     * @return the ordered manipulators
     */
    @Getter
    List<Manipulator> manipulators = Collections.emptyList();

    void init(Collection<Manipulator> manipulators) throws ManipulationException {
        injectManipulatorsAndComputeStates(manipulators);
        computeCommonState();
        readPreviousReport();
    }

    private void injectManipulatorsAndComputeStates(Collection<Manipulator> manipulators) throws ManipulationException {
        // inject ordered manipulators and compute states
        List<Manipulator> orderedManipulators = new ArrayList<>(manipulators);

        // The RESTState depends upon the VersionState being initialised. Therefore
        // initialise in reverse order
        // and do a final sort to run in the correct order. See the Manipulator
        // interface for detailed discussion
        // on ordered.
        orderedManipulators.sort(Collections.reverseOrder(new ManipulatorPriorityComparator()));

        for (final Manipulator manipulator : orderedManipulators) {
            logger.debug("Initialising manipulator " + manipulator.getClass().getSimpleName());
            manipulator.init(this);
        }
        orderedManipulators.sort(new ManipulatorPriorityComparator());

        this.manipulators = orderedManipulators;
    }

    private void computeCommonState() throws ManipulationException {
        CommonState cState = new CommonState(getUserProperties());
        DependencyState dState = getState(DependencyState.class);
        if (!dState.getDependencyOverrides().isEmpty() && cState.getStrictDependencyPluginPropertyValidation() != 0) {
            logger.warn("Disabling strictPropertyValidation as dependencyOverrides are enabled");
            cState.setStrictDependencyPluginPropertyValidation(0);
        }
        setState(cState);
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

    /**
     * List of manipulated <code>Project</code> instances.
     */
    public record Manipulations(TreeSet<Project> originalProjects, TreeSet<Project> manipulatedProjects) {

        Manipulations() {
            this(emptySetSupplier.get(), emptySetSupplier.get());
        }

        Stream<Project> executionRoots() {
            return manipulatedProjects.stream().filter(Project::isExecutionRoot);
        }

        Manipulations extendsWith(TreeSet<Project> originalProjects, Stream<Project> manipulatedProjectsStream) {
            return new Manipulations(merge(this.originalProjects, originalProjects.stream()),
                    merge(this.manipulatedProjects, manipulatedProjectsStream));
        }

        TreeSet<Project> merge(TreeSet<Project> projects, Stream<Project> others) {
            final Object clonedObject = projects.clone();
            @SuppressWarnings({ "unchecked" })
            TreeSet<Project> clonedProjects = projects.getClass().cast(clonedObject);
            others.forEach(entry -> clonedProjects.add(entry));
            return clonedProjects;
        }

        Manipulations applyManipulators(ManipulationSession session, List<Project> projects)
                throws ManipulationException {
            // check if manipulators are already applied
            if (originalProjects().containsAll(projects)) {
                session.logger.debug("Skipping, already applied for {}", projects.get(0));
                return this;
            }
            // fetch original projects references from parameters
            TreeSet<Project> originalProjects = emptySetSupplier.get();
            projects.stream().map(Project::new).forEach(originalProjects::add);
            Project executionRoot = projects.get(0);
            // apply manipulators and update collected map with manipulated projects
            session.logger.info("Running Maven Manipulation Extension (PME) {} [{}]",
                    ManifestUtils.getManifestInformation(ManipulationManager.class), session.getPomFile());
            session.logger.debug("Maven-Manipulation-Extension: applying changes for {}", session.getMavenSession());
            Function<Manipulator, Set<Project>> applyChanges = CheckedFunction1.<Manipulator, Set<Project>> of(
                    manipulator -> manipulator.applyChanges(projects)).unchecked();
            Stream<Project> manipulatedProjectsStream = session.manipulators.stream()
                                                                            .peek(manipulator -> session.logger.info(
                                                                                    "Running manipulator {}",
                                                                                    manipulator.getClass().getName()))
                                                                            .flatMap(applyChanges.andThen(Set::stream));
            Manipulations manipulations = extendsWith(originalProjects, manipulatedProjectsStream);
            session.saveReport(executionRoot, manipulations);
            return manipulations;
        }

        static final Comparator<Project> comparator = Comparator.comparing(Manipulations::hierarchyDepthOf)
                                                                .thenComparing(Project::getKey);

        static final Supplier<TreeSet<Project>> emptySetSupplier = () -> new TreeSet<Project>(comparator);

        static int hierarchyDepthOf(Project project) {
            if (project.isInheritanceRoot()) {
                return 0;
            }
            Project projectParent = project.getProjectParent();
            if (Objects.isNull(projectParent)) {
                return 0;
            }
            return hierarchyDepthOf(projectParent) + 1;
        }

    }

    protected Manipulations manipulations = new Manipulations();

    public Set<Project> getManipulatedProjects() {
        return manipulations.manipulatedProjects();
    }

    protected ExecutorService manipulationsMerger = Executors.newSingleThreadExecutor();

    protected ConcurrentLinkedQueue<Manipulations> manipulationsQueue = new ConcurrentLinkedQueue<>();

    Manipulations manipulate(List<Project> projects) throws ManipulationException {
        return this.manipulations.applyManipulators(this, projects);
    }

    final PME jsonReport = new PME();

    @Inject
    ManipulationExtensionBridge mavenBridge;

    @Inject
    PomIO pomIO;

    @Inject
    Provider<ManipulationSession> manipulationSessionProvider;

    Supplier<Boolean> isRewritingChanges = () -> Boolean.parseBoolean(
            this.getUserProperties().getProperty(ManipulationManager.REWRITE_CHANGED, "true"));

    void saveReport(Project executionRoot, Manipulations manipulated) throws ManipulationException {
        ManipulationSession mergingSession = manipulationSessionProvider.get();
        mergingSession.mavenSessionProvider = this::getMavenSession;
        manipulationsQueue.add(manipulated);
        manipulationsMerger.submit(
                CheckedRunnable.of(() -> mergingSession.mergeAndReport(executionRoot, manipulated)).unchecked());
    }

    void mergeAndReport(Project executionRoot, Manipulations manipulated) throws ManipulationException {
        manipulations = manipulationsQueue.remove();
        while (!manipulationsQueue.isEmpty()) {
            Manipulations other = manipulationsQueue.remove();
            manipulations = this.manipulations.extendsWith(other.originalProjects(),
                    other.manipulatedProjects().stream());
        }
        // post process modified projects
        if (!isRewritingChanges.get()) {
            mavenBridge.addMojo(executionRoot.getModel());
            pomIO.writeTemporaryPOMs(manipulations.manipulatedProjects);
        } else {
            logger.debug("Maven-Manipulation-Extension: Rewriting changed");
            pomIO.writePOMs(manipulations.manipulatedProjects);
        }

        jsonReport.getGav().setPVR(executionRoot.getKey());
        jsonReport.getGav()
                  .setOriginalGAV(getPreviousReport().map(report -> report.getGav().getOriginalGAV())
                                                     .orElseGet(() -> executionRoot.getKey().toString()));
        WildcardMap<ProjectVersionRef> map = getState(RelocationState.class) == null ? new WildcardMap<>()
                : getState(RelocationState.class).getDependencyRelocations();
        String report = ProjectComparator.compareProjects(this, jsonReport, map, manipulations.originalProjects,
                manipulations.manipulatedProjects);
        logger.info("{}{}", System.lineSeparator(), report);
        String reportTxtOutputFile = getMavenSession().getUserProperties().getProperty("reportTxtOutputFile", "");

        try {
            getTargetDir().mkdir();
            if (StringUtils.isNotEmpty(reportTxtOutputFile)) {
                File reportFile = new File(reportTxtOutputFile);
                FileUtils.writeStringToFile(reportFile, report, StandardCharsets.UTF_8);
            }

            String reportJsonOutputFile = getUserProperties().getProperty("reportJSONOutputFile",
                    getTargetDir() + File.separator + "alignmentReport.json");

            try (FileWriter writer = new FileWriter(reportJsonOutputFile)) {
                writer.write(JSONUtils.jsonToString(jsonReport));
            }
        } catch (IOException cause) {
            logger.error("Unable to create result file", cause);
            throw new ManipulationException("Marker/result file creation failed", cause);
        }

        if (isRewritingChanges.get()) {
            mavenBridge.addReport(getMavenSession(), jsonReport);
        }

        logger.info("Maven-Manipulation-Extension: Finished.");
    }

    private Optional<PME> previousReport;

    void readPreviousReport() {
        previousReport = mavenBridge.readReport(mavenSessionProvider.get());
    }

    /**
     * @return the previous manipulation report if anyx
     */
    Optional<PME> getPreviousReport() {
        return previousReport;
    }

    /**
     * True (enabled) by default, this is the kill switch for all manipulations. Manipulator implementations MAY also be
     * enabled/disabled individually.
     *
     * @see #MANIPULATIONS_DISABLED_PROP
     * @see VersioningState#isEnabled()
     * @return whether the PME subsystem is enabled.
     */
    public boolean isEnabled() {
        return !Boolean.parseBoolean(getUserProperties().getProperty(MANIPULATIONS_DISABLED_PROP, "false"));
    }

    public void setState(final State state) {
        states.put(state.getClass(), state);
    }

    /**
     * This will re-initialise any state linked to this session. This is useful if the control properties have been
     * updated.
     *
     * @throws ManipulationException if an error occurs
     */
    public void reinitialiseStates() throws ManipulationException {
        for (State s : states.values()) {
            s.initialise(getUserProperties());
        }
    }

    public <T extends State> T getState(final Class<T> stateType) {
        return stateType.cast(states.get(stateType));
    }

    @Override
    public Properties getUserProperties() {
        return userProperties;
    }

    class UserProperties extends Properties {

        static final long serialVersionUID = 1L;

        @Override
        public String getProperty(String key) {
            return Optional.ofNullable(userProperties().getProperty(key))
                           .orElseGet(() -> activeProfilesProperties().getProperty(key));
        }

        Properties userProperties() {
            return mavenSessionProvider.get().getRequest().getUserProperties();
        }

        Properties activeProfilesProperties() {
            List<String> activeProfileIds = mavenSessionProvider.get().getSettings().getActiveProfiles();

            return mavenSessionProvider.get()
                                       .getSettings()
                                       .getProfiles()
                                       .stream()
                                       .filter(profile -> activeProfileIds.contains(profile.getId()))
                                       .map(Profile::getProperties)
                                       .collect(Properties::new, (props1, props2) -> props1.putAll(props2),
                                               (props1, props2) -> {
                                               });
        }

        Properties loadParentProperties() {
            ManipulationSession manipulationSession = ManipulationSession.this;
            return Optional.of(manipulationSession.getMavenSession())
                           .map(MavenSession::getRequest)
                           .map(MavenExecutionRequest::getPom)
                           .map(File::getParentFile)
                           .map(pomFile -> Try.success(pomFile).mapTry(manipulationSession.configIO::parse).get())
                           .get();
        }

    };

    final UserProperties userProperties = new UserProperties();

    @Override
    public List<ArtifactRepository> getRemoteRepositories() {
        return mavenSessionProvider.get().getRequest().getRemoteRepositories();
    }

    @Override
    public File getTargetDir() {
        File parentDirectory = mavenSessionProvider.get().getRequest().getMultiModuleProjectDirectory();
        return new File(parentDirectory, "target");
    }

    @Override
    public ArtifactRepository getLocalRepository() {
        return mavenSessionProvider.get().getLocalRepository();
    }

    private ManipulationException error;

    /**
     * Used by extension ManipulatingEventSpy to store any errors during project construction and manipulation
     * 
     * @param error record any exception that occurred.
     */
    public void setError(final ManipulationException error) {
        this.error = error;
    }

    /**
     * Used by extension ManipulatinglifeCycleParticipant to retrieve any errors stored by ManipulatingEventSpy
     * 
     * @return ManipulationException
     */
    public ManipulationException getError() {
        return error;
    }

    @Override
    public List<String> getActiveProfiles() {
        return Optional.ofNullable(mavenSessionProvider.get().getRequest())
                       .map(MavenExecutionRequest::getActiveProfiles)
                       .orElse(Collections.emptyList());
    }

    @Override
    public Settings getSettings() {
        return mavenSessionProvider.get().getSettings();
    }

    /**
     * Checks all known states to determine whether any are enabled. Will ignore any states within the supplied list.
     * 
     * @param ignoreList the list of States that should be ignored when checking if any are enabled.
     * @return whether any of the States are enabled.
     */
    public boolean anyStateEnabled(List<Class<? extends State>> ignoreList) {
        boolean result = false;

        for (final Entry<Class<?>, State> entry : states.entrySet()) {
            final Class<?> c = entry.getKey();
            final State state = entry.getValue();

            if (!ignoreList.contains(c) && state.isEnabled()) {
                result = true;
                break;
            }
        }
        return result;
    }

    @Override
    public List<String> getExcludedScopes() {
        // In some tests, CommonState is not available so check for it first.
        if (states.containsKey(CommonState.class)) {
            return getState(CommonState.class).getExcludedScopes();
        }
        return Collections.emptyList();
    }

    Optional<File> pomFileHolder = Optional.empty();

    public Optional<File> setPomFile(File pomFile) {
        try {
            return pomFileHolder;
        } finally {
            pomFileHolder = Optional.ofNullable(pomFile);
        }
    }

    @Override
    public File getPomFile() {
        return pomFileHolder.orElseGet(
                () -> Optional.of(mavenSessionProvider.get().getRequest()).map(MavenExecutionRequest::getPom).get());
    }

    public File getMultiModuleProjectDirectory() {
        return mavenSessionProvider.get().getRequest().getMultiModuleProjectDirectory();
    }

    @Override
    public <T> T lookup(Class<T> claxz) throws ComponentLookupException {
        return mavenSessionProvider.get().getContainer().lookup(claxz);
    }
}
