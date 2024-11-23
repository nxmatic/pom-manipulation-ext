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

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.SessionScoped;
import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.core.ManipulationSession;

@Named(ManipulationComponent.HINT)
@SessionScoped
public class ManipulationLifeCycleParticipant
    extends AbstractMavenLifecycleParticipant 
{
    private ManipulationSession manipulatingSession;

    @Inject
    public ManipulationLifeCycleParticipant(ManipulationSession manipulatingSession)
    {
        this.manipulatingSession = manipulatingSession;
    }

    @Override
    public void afterProjectsRead( final MavenSession mavenSession )
        throws MavenExecutionException
    {
        final ManipulationException error = manipulatingSession.getError();
        if ( error != null )
        {
            throw new MavenExecutionException( "POM Manipulation failed: " + error.getMessage(), error );
        }

        super.afterProjectsRead( mavenSession );
    }

}
