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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.core.ManipulationManager;

import noname.maven.devenv.spi.plexus.ForeignExtensionPackages;
import noname.maven.devenv.spi.plexus.MultiModuleProjectLifecycleParticipant;
import noname.maven.devenv.spi.plexus.MultiModuleProjectLifecycleParticipant.Actions;
import noname.maven.devenv.spi.plexus.MultiModuleProjectLifecycleParticipant.Listener;

/**
 * Implements hooks necessary to apply modifications in the Maven bootstrap, before the build starts.
 * 
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@Singleton
@ForeignExtensionPackages.Import(groupId = "noname.maven.extensions.devenv", artifactId = "maven-devenv-extension", packages = "noname.maven.devenv.spi.plexus")
public class ManipulatingMultiModuleLifecycleParticipant
        implements ManipulationComponent, MultiModuleProjectLifecycleParticipant.Registration,
        MultiModuleProjectLifecycleParticipant.Actions, MultiModuleProjectLifecycleParticipant.Listener {

    @Inject
    private ManipulationManager manager;

    @Override
    public void init(MavenSession mavenSession) throws Exception {
        manager.scanAndApply(mavenSession);
    }

    @Override
    public void finish(MavenSession mavenSsession) throws Exception {

    }

    @Override
    public Actions actions() {
        return this;
    }

    @Override
    public Listener listener() {
        return this;
    }

}
