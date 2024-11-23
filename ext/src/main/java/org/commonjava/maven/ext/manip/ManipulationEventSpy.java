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

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.codehaus.plexus.PlexusContainer;
import org.commonjava.maven.ext.core.ManipulationManager;
import org.commonjava.maven.ext.core.ManipulationSession;

import io.vavr.control.Try;

/**
 * Implements hooks necessary to apply modifications in the Maven bootstrap,
 * before the build starts.
 * 
 * @author jdcasey
 */
@Named
@Singleton
public class ManipulationEventSpy implements EventSpy {

    @Inject
    ManipulationManager manager;
    
    @Inject
    ManipulationMultiModuleLifecycleParticipant lifeCycleParticipant;
    
    @Inject
    Provider<ManipulationSession> sessionProvider;

    @Inject
    PlexusContainer container;

    boolean isEnabled = true;

    @PostConstruct
    public void enableIfRequired() {
        isEnabled = Try.success(ManipulationEventSpy.class.getPackageName())
                .map(name -> name.concat(".ManipulationLifeCycleParticipant"))
                .mapTry(name -> !container.hasComponent(name))
                .map(Boolean::valueOf)
                .recover(__ -> Boolean.FALSE)
                .get()
                .booleanValue();
    }
    
    @Override
    public void init(Context context) throws Exception {}

    @Override
    public void close() throws Exception {}

    /**
     * This is the entry point for the extension. This is called by Maven and we
     * pass control to the
     * ManipulationManager.
     */
    @Override
    public void onEvent(final Object event) throws Exception {
        if (!isEnabled) {
            return;
        }
        if (!(event instanceof ExecutionEvent)) {
            return;
        }

        final ExecutionEvent ee = (ExecutionEvent) event;
        final ExecutionEvent.Type type = ee.getType();
        switch (type) {
            case ProjectDiscoveryStarted:
                lifeCycleParticipant.process(ee.getSession());
                // manager.scanAndApply(sessionProvider.get());
                break;
            case SessionEnded:
                break;
            default:
                break;
        }
    }


}
