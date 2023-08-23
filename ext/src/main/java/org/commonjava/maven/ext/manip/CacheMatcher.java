package org.commonjava.maven.ext.manip;

import java.io.IOException;
import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.buildcache.bridge.CacheBridge;
import org.apache.maven.buildcache.xml.diff.Diff;
import org.apache.maven.buildcache.xml.diff.Mismatch;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.commonjava.maven.ext.common.ManipulationComponent;

import io.vavr.CheckedFunction0;
import noname.devenv.plexus.ForeignExtensionPackages;

@Named(ManipulationComponent.HINT)
@Singleton
@ForeignExtensionPackages.Import(groupId = "org.apache.maven.extensions", artifactId = "maven-build-cache-extension", packages = {
        "org.apache.maven.buildcache.bridge", "org.apache.maven.buildcache.xml.diff" })
public class CacheMatcher {

    @Inject
    CacheBridge bridge;

    boolean isMatching(MavenSession mavenSession) throws IOException, ComponentLookupException {
        return CheckedFunction0.<Boolean>of(() -> bridge.computeDiff(mavenSession)
                .map(Diff::getMismatches)
                .orElse(Collections.emptyList())
                .stream()
                .map(Mismatch::getItem)
                .noneMatch(item -> item.endsWith("pom.xml")))
                .unchecked()
                .apply();
    }

}
