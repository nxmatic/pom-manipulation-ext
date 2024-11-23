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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

//import org.apache.maven.buildcache.bridge.CacheBridge;
import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.core.ManipulationManager;
import org.commonjava.maven.ext.core.ManipulationSession;

import noname.devenv.maven.MultiModuleProjectLifecycleParticipant;
import noname.devenv.plexus.ForeignExtensionPackages;

/*
 * Implements hooks necessary to apply modifications in the Maven bootstrap, before the build starts.
 * 
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@Singleton
@ForeignExtensionPackages.Import(groupId = "noname.maven.extensions.devenv", artifactId = "maven-devenv-extension", packages = {
        "noname.devenv.maven" })
public class ManipulationMultiModuleLifecycleParticipant implements MultiModuleProjectLifecycleParticipant.SPI {

    final Map<File, ManipulationSession> sessionByDirectory = new ConcurrentHashMap<>();

    @Inject
    Provider<ManipulationSession> sessionProvider;

    @Inject
    ManipulationManager manager;

    public void process(MavenSession mavenSession) throws Exception {
        sessionByDirectory.computeIfAbsent(mavenSession.getRequest().getMultiModuleProjectDirectory(),
                (multiModuleProjectDirectory) -> {
                    ManipulationSession session = sessionProvider.get();
                    try {
                        manager.init(session);
                        manager.scanAndApply(session);
                    } catch (ManipulationException cause) {
                        throw new IllegalStateException("Cannot manipulate project " + multiModuleProjectDirectory,
                                cause);
                    }
                    return session;
                });

    }

    ManipulationMultiModuleLifecycleParticipant() {
        // for Guice
    }
}
