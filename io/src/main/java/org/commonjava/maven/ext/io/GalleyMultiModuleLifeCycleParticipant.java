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
package org.commonjava.maven.ext.io;

import java.util.Collections;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.io.resolver.ExtensionInfrastructure;

import io.vavr.CheckedConsumer;
import noname.maven.devenv.spi.plexus.ForeignExtensionPackages;
import noname.maven.devenv.spi.plexus.MultiModuleProjectLifecycleParticipant;

@Named("galley")
@Singleton
@ForeignExtensionPackages.Import(groupId = "noname.maven.extensions.devenv", artifactId = "maven-devenv-extension", packages = "noname.maven.devenv.spi.plexus")
public class GalleyMultiModuleLifeCycleParticipant implements MultiModuleProjectLifecycleParticipant.Registration,
        MultiModuleProjectLifecycleParticipant.Actions, MultiModuleProjectLifecycleParticipant.Listener {

    @Inject
    private Set<ExtensionInfrastructure> infrastructures = Collections.emptySet();

    @Override
    public void init(MavenSession session) throws Exception {
        run(ExtensionInfrastructure::init);
    }

    @Override
    public void finish(MavenSession session) throws Exception {
        run(ExtensionInfrastructure::finish);
    }

    @Override
    public MultiModuleProjectLifecycleParticipant.Actions actions() {
        return this;
    }

    @Override
    public MultiModuleProjectLifecycleParticipant.Listener listener() {
        return this;
    }

    void run(CheckedConsumer<ExtensionInfrastructure> checked) {
        infrastructures.stream().forEach(infra -> checked.unchecked().accept(infra));
    }
}
