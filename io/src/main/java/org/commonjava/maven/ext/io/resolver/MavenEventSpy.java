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
package org.commonjava.maven.ext.io.resolver;

import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.sisu.Priority;

import io.vavr.CheckedConsumer;
import io.vavr.control.Try;

@Named("galley")
@Priority(999)
@Singleton
public class MavenEventSpy extends AbstractEventSpy {
    
    @Inject private PlexusContainer container;

    public interface MavenSessionInjector {
        void inject(MavenSession session);
    }
    
    @Override
    public void onEvent(Object event) throws Exception {
        if (!(event instanceof ExecutionEvent)) {
            return;
        }
        final ExecutionEvent ee = (ExecutionEvent) event;
        final ExecutionEvent.Type type = ee.getType();

        switch (type) {
            case ProjectDiscoveryStarted:
            case ProjectStarted:
                initInfrastrutures(ee.getSession());
                break;
            case ProjectSucceeded:
            case ProjectFailed:
                finishInfrastructures();
                break;
            default:
                break;
        }

        super.onEvent(event);
    }

    @Inject
    private Set<ExtensionInfrastructure> infrastructures = Collections.emptySet();

    void initInfrastrutures(MavenSession session) throws ComponentLookupException {
        container.lookup(MavenSessionInjector.class).inject(session);
        CheckedConsumer<ExtensionInfrastructure> checked = CheckedConsumer.of(ExtensionInfrastructure::init);
        infrastructures.stream().forEach(checked.unchecked());
    }

    void finishInfrastructures() {
        CheckedConsumer<ExtensionInfrastructure> checked = CheckedConsumer.of(ExtensionInfrastructure::finish);
        infrastructures.stream().forEach(checked.unchecked());
    }

}
