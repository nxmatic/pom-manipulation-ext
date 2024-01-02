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
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.codehaus.plexus.PlexusContainer;
import org.commonjava.maven.ext.core.ManipulationManager;

import io.vavr.control.Try;

/**
 * Implements hooks necessary to apply modifications in the Maven bootstrap, before the build starts.
 * 
 * @author jdcasey
 */
@Named
@Singleton
public class ManipulatingEventSpy extends AbstractEventSpy {

    @Inject
    // Collections are injected dynamically supporting the session scoped beans
    private ManipulationManager manager;
    
    @Inject
    PlexusContainer container;

    boolean isEnabled = true;
    
    @PostConstruct
    public void init() {        
        isEnabled = Try.success(ManipulatingEventSpy.class.getPackageName())
                  .map(name -> name.concat(".ManipulatingLifeCycleParticipant"))
                  .mapTry(container::hasComponent)
                  .map(Boolean.FALSE::equals)
                  .recover(__ -> Boolean.TRUE)
                  .get()
                  .booleanValue();
    }

    /**
     * This is the entry point for the extension. This is called by Maven and we pass control to the
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
            scanAndApply();
            break;
        case SessionEnded:
            break;
        default:
            break;
        }
    }

    void scanAndApply() {
            manager.scanAndApply();
    }

}
