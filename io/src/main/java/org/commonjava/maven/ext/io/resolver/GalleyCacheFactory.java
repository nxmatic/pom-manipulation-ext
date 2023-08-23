package org.commonjava.maven.ext.io.resolver;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.SessionScoped;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.repository.MirrorSelector;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.galley.cache.FileCacheProvider;
import org.commonjava.maven.galley.cache.SimpleLockingSupport;
import org.commonjava.maven.galley.io.TransferDecoratorManager;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.commonjava.maven.galley.spi.transport.LocationExpander;

import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.multibindings.OptionalBinder;

import io.vavr.CheckedFunction0;

/**
 * Allow for a 'shell' infrastructure with majority not implemented but just the cache directory for FileIO. This is
 * useful for both tests and usage of FileIO within GME.
 *
 * @param targetDirectory the directory to create the cache in
 * @return this instance
 * @throws ManipulationException if an error occurs.T
 */
@Named("galley")
public class GalleyCacheFactory extends AbstractModule {

    GalleyCacheFactory() {
        super();
        // for guice
    }

    protected void configure() {
        binder().bind(Builder.class).in(SessionScoped.class);
        OptionalBinder.newOptionalBinder(binder(), Builder.class);
    }

    @Provides
    CacheProvider cacheProvider(Injector injector, MavenSession session) {
        Builder builder = injector.getInstance(Builder.class);
        return CheckedFunction0.of(builder::build).unchecked().apply();
    }

    static class Builder {

        @Inject
        MavenSessionHandler sessionHandler;

        Builder withMavenSessionHandler(MavenSessionHandler thatHandler) {
            sessionHandler = thatHandler;
            return this;
        }

        @Inject
        MirrorSelector mirrorSelector;

        @Inject
        Builder withMirrorSelector(MirrorSelector thatSelector) {
            mirrorSelector = thatSelector;
            return this;
        }

        @Inject
        PathGenerator pathGenerator;

        @Inject
        FileEventManager fileEvents;

        @Inject
        TransferDecoratorManager transportDecorators;

        @Inject
        SimpleLockingSupport lockingSupport;

        CacheProvider build() throws ManipulationException {
            return new FileCacheProvider(cacheDir(), pathGenerator, fileEvents, transportDecorators, false,
                    lockingSupport);
        }

        LocationExpander locationExpander() throws ManipulationException {
            try {
                return new GalleyMavenLocationExpander(Collections.emptyList(), sessionHandler.getRemoteRepositories(),
                        sessionHandler.getLocalRepository(), mirrorSelector, sessionHandler.getSettings(),
                        sessionHandler.getActiveProfiles());
            } catch (final MalformedURLException e) {
                throw new ManipulationException("Failed to setup Maven-specific LocationExpander: {}", e.getMessage(),
                        e);
            }
        }

        File cacheDir() {
            return new File(sessionHandler.getTargetDir(), "pom-manipulator");
        }

    }
}
