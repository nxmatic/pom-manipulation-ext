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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.logging.Logger;
import org.commonjava.maven.ext.annotation.ConfigValue;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.ext.core.bridge.ManipulatingExtensionBridge;
import org.commonjava.maven.ext.core.impl.Manipulator;
import org.commonjava.maven.ext.core.state.CommonState;
import org.commonjava.maven.ext.core.state.DependencyState;
import org.commonjava.maven.ext.core.state.State;
import org.commonjava.maven.ext.core.state.VersioningState;
import org.commonjava.maven.ext.core.util.ManipulatorPriorityComparator;
import org.commonjava.maven.ext.io.ConfigIO;

import io.vavr.control.Try;
import lombok.Getter;

/**
 * Repository for components that help manipulate POMs as needed, and state related to each {@link Manipulator} (which
 * contains configuration and changes to be applied). This is basically a clearing house for state required by the
 * different parts of the manipulator extension.
 *
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@Singleton
@SessionScoped
public class ManipulationSession implements MavenSessionHandler, ManipulationComponent {
    @ConfigValue(docIndex = "../index.html#disabling-the-extension")
    private static final String MANIPULATIONS_DISABLED_PROP = "manipulation.disable";

    private final Map<Class<?>, State> states = new HashMap<>();

    @Inject
    private ManipulatingExtensionBridge mojoBridge;

    @Inject
    private Logger logger;

    @Inject
    private MavenSession mavenSession;

    @Inject
    private ConfigIO configIO;

    public ManipulationSession() { // should exist for guice instrumentation
        super();
    }

    /**
     * @return Returns the current MavenSession
     */
    public MavenSession getMavenSession() {
        return mavenSession;
    }

    ManipulationSession inject(MavenSession mavenSession) { // m2e, don't have the invoked session injected
        this.mavenSession = mavenSession;
        return this;
    }

    /**
     * Determined from {@link Manipulator#getExecutionIndex()} comparisons during
     * {@link #injectManipulators(Set<Manipulator)}.
     *
     * @return the ordered manipulators
     */
    @Getter
    List<Manipulator> manipulators = Collections.emptyList();

    void init(Set<Manipulator> manipulators) throws ManipulationException {
        injectManipulatorsAndComputeStates(manipulators);
        computeCommonState();
        readPreviousReport();
    }

    private void injectManipulatorsAndComputeStates(Set<Manipulator> manipulators) throws ManipulationException {
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

    private Optional<PME> previousReport;

    void readPreviousReport() {
        previousReport = mojoBridge.readReport(mavenSession);
    }

    /**
     * @return the previous manipulation report if anyx
     */
    Optional<PME> getPreviousReport() {
        return previousReport;
    }

    /**
     * List of <code>Project</code> instances.
     */
    private List<Project> projects;

    private ManipulationException error;

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

    public void setMavenSession(final MavenSession mavenSession) {
        this.mavenSession = mavenSession;
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
            return mavenSession.getRequest().getUserProperties();
        }

        Properties activeProfilesProperties() {
            List<String> activeProfileIds = mavenSession.getSettings().getActiveProfiles();

            return mavenSession.getSettings()
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
                                              .map(pomFile -> Try.success(pomFile)
                                                                 .mapTry(manipulationSession.configIO::parse)
                                                                 .get())
                                              .get();
        }

    };

    final UserProperties userProperties = new UserProperties();

    public void setProjects(final List<Project> projects) {
        this.projects = projects;
    }

    public List<Project> getProjects() {
        return projects;
    }

    @Override
    public List<ArtifactRepository> getRemoteRepositories() {
        return mavenSession == null ? null : mavenSession.getRequest().getRemoteRepositories();
    }

    @Override
    public File getPom() throws ManipulationException {
        if (mavenSession == null) {
            throw new ManipulationException("Invalid session");
        }

        return mavenSession.getRequest().getPom();
    }

    @Override
    public File getTargetDir() {
        if (mavenSession == null) {
            return new File("target");
        }

        final File pom = mavenSession.getRequest().getPom();
        if (pom == null) {
            return new File("target");
        }

        return new File(pom.getParentFile(), "target");
    }

    @Override
    public ArtifactRepository getLocalRepository() {
        return mavenSession == null ? null : mavenSession.getRequest().getLocalRepository();
    }

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
        return mavenSession == null || mavenSession.getRequest() == null ? Collections.emptyList()
                : mavenSession.getRequest().getActiveProfiles();
    }

    @Override
    public Settings getSettings() {
        return mavenSession == null ? null : mavenSession.getSettings();
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

}
