/*
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver-maven-plugin] (matthieu@brouillard.fr)
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

import java.util.Objects;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.Mojo;
import org.commonjava.maven.ext.common.json.PME;
import org.commonjava.maven.ext.common.util.JSONUtils;
import org.commonjava.maven.ext.core.ManipulationManager;

import  org.commonjava.maven.ext.core.AttachManipulatedPOMsMavenBridge;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Works in conjunction with ManipulatingLifeCycleParticipant. */
@Mojo(name = AttachManipulatedPOMsMavenBridge.GOAL_ATTACH_MODIFIED_POMS, instantiationStrategy = InstantiationStrategy.SINGLETON, threadSafe = true)
public class AttachManipulatedPOMs extends AbstractMojo {

  final private MavenSession mavenSession;

  @Inject
  AttachManipulatedPOMs(MavenSession session) {
    mavenSession = session;
  }

  private final PME report = new PME();

  private final JSONUtils.InternalObjectMapper reportMapper = new JSONUtils.InternalObjectMapper(new ObjectMapper());

  @Override
  public void execute() throws MojoExecutionException {
    String body = mavenSession.getUserProperties().getProperty(ManipulationManager.REPORT_USER_PROPERTY_KEY);
    if (Objects.isNull(body)) {
      getLog()
          .warn(
              AttachManipulatedPOMsMavenBridge.GOAL_ATTACH_MODIFIED_POMS
                  + "shouldn't be executed alone. The Mojo "
                  + "is a part of the plugin and executed automatically.");
      return;
    }

    if ("{}".equalsIgnoreCase(body)) {
      // We don't need to attach modified poms anymore.
      return;
    }

    mavenSession.getUserProperties().setProperty(ManipulationManager.REPORT_USER_PROPERTY_KEY, "{}");


    try {
      throw new MojoExecutionException("unsupported report unmarshalling");
      // PME report = reportMapper.readValue(json, PME.class);
      /*
       * JGitverUtils.attachModifiedPomFilesToTheProject(
       * mavenSession.getAllProjects(),
       * report.getModules().
       * jgitverSession.getVersion(),
       * resolveProjectVersion,
       * new ConsoleLogger());
       */
    } catch (Exception ex) {
      throw new MojoExecutionException(
          "Unable to execute goal: " + AttachManipulatedPOMsMavenBridge.GOAL_ATTACH_MODIFIED_POMS, ex);
    }
  }
}
