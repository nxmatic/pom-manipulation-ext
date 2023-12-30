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
package org.commonjava.maven.ext.manip;

import java.io.File;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Semaphore;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.util.ManifestUtils;
import org.commonjava.maven.ext.core.ManipulationManager;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.core.util.PropertiesUtils;
import org.commonjava.maven.ext.io.ConfigIO;
import org.eclipse.sisu.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements hooks necessary to apply modifications in the Maven bootstrap, before the build starts.
 * 
 * @author jdcasey
 */
@SuppressWarnings("unused")
@Named
@Singleton
public class ManipulatingEventSpy extends AbstractEventSpy {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ManipulationManager manipulationManager;

    private final ManipulationSession session;

    private final Semaphore semaphore = new Semaphore(1);

    private final ConfigIO configIO;

    @Inject
    public ManipulatingEventSpy(ManipulationManager manipulationManager, ManipulationSession session,
            ConfigIO configIO) {
        this.manipulationManager = manipulationManager;
        this.session = session;
        this.configIO = configIO;
    }

    @Override
    public void onEvent(final Object event) throws Exception {
        if (!(event instanceof ExecutionEvent)) {
            return;
        }
        final ExecutionEvent ee = (ExecutionEvent) event;
        final ExecutionEvent.Type type = ee.getType();

        if (type != Type.ProjectDiscoveryStarted) {
            return;
        }

        if (!session.isEnabled()) {
            logger.info("Manipulation engine disabled via command-line option");
            return;
        }

        final MavenSession mavenSession = ee.getSession();

        final File pomFile = mavenSession.getRequest().getPom();
        if (pomFile == null) {
            logger.error("Manipulation cannot locate request POM file");
            return;
        }

        try {
            Properties config = configIO.parse(pomFile.getParentFile());
            PropertiesUtils.handleConfigPrecedence(session.getUserProperties(), config);

            if (new File(pomFile.getParentFile(), ManipulationManager.MARKER_FILE).exists()) {
                logger.info("Skipping manipulation as previous execution found.");
                return;
            }
            logger.info("Running Maven Manipulation Extension (PME) "
                    + ManifestUtils.getManifestInformation(ManipulatingEventSpy.class));
            manipulationManager.init(session);
            manipulationManager.scanAndApply(session);
        } // Catch manipulation error and fail the build
        catch (final ManipulationException e) {
            logger.error("Extension failure", e);
            session.setError(e);
        }
        // Catch any runtime exceptions and mark them to fail the build as well.
        catch (final RuntimeException e) {
            logger.error("Extension failure", e);
            session.setError(new ManipulationException("Caught runtime exception", e));
        } finally {
            super.onEvent(event);
        }
    }
}
