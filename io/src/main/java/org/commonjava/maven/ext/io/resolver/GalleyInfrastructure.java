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

import java.io.File;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.repository.MirrorSelector;
import org.apache.maven.settings.Settings;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.auth.MemoryPasswordManager;
import org.commonjava.maven.galley.cache.FileCacheProvider;
import org.commonjava.maven.galley.config.TransportManagerConfig;
import org.commonjava.maven.galley.event.NoOpFileEventManager;
import org.commonjava.maven.galley.filearc.FileTransport;
import org.commonjava.maven.galley.filearc.ZipJarTransport;
import org.commonjava.maven.galley.internal.TransferManagerImpl;
import org.commonjava.maven.galley.internal.xfer.DownloadHandler;
import org.commonjava.maven.galley.internal.xfer.ExistenceHandler;
import org.commonjava.maven.galley.internal.xfer.ListingHandler;
import org.commonjava.maven.galley.internal.xfer.UploadHandler;
import org.commonjava.maven.galley.io.HashedLocationPathGenerator;
import org.commonjava.maven.galley.io.NoOpTransferDecorator;
import org.commonjava.maven.galley.io.SpecialPathManagerImpl;
import org.commonjava.maven.galley.io.TransferDecoratorManager;
import org.commonjava.maven.galley.maven.ArtifactManager;
import org.commonjava.maven.galley.maven.ArtifactMetadataManager;
import org.commonjava.maven.galley.maven.internal.ArtifactManagerImpl;
import org.commonjava.maven.galley.maven.internal.ArtifactMetadataManagerImpl;
import org.commonjava.maven.galley.maven.internal.defaults.StandardMaven350PluginDefaults;
import org.commonjava.maven.galley.maven.internal.defaults.StandardMavenPluginImplications;
import org.commonjava.maven.galley.maven.internal.type.StandardTypeMapper;
import org.commonjava.maven.galley.maven.internal.version.VersionResolverImpl;
import org.commonjava.maven.galley.maven.model.view.XPathManager;
import org.commonjava.maven.galley.maven.parse.MavenMetadataReader;
import org.commonjava.maven.galley.maven.parse.MavenPomReader;
import org.commonjava.maven.galley.maven.parse.XMLInfrastructure;
import org.commonjava.maven.galley.maven.spi.defaults.MavenPluginDefaults;
import org.commonjava.maven.galley.maven.spi.defaults.MavenPluginImplications;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.maven.spi.version.VersionResolver;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.nfc.MemoryNotFoundCache;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.maven.galley.spi.transport.Transport;
import org.commonjava.maven.galley.spi.transport.TransportManager;
import org.commonjava.maven.galley.transport.TransportManagerImpl;
import org.commonjava.maven.galley.transport.htcli.HttpClientTransport;
import org.commonjava.maven.galley.transport.htcli.HttpImpl;

import io.vavr.control.Try;

/**
 * Manager component responsible for setting up and managing the Galley API instances used to resolve POMs and metadata.
 * 
 * @author jdcasey
 */
@Named("galley")
@Singleton
@ApplicationScoped
public class GalleyInfrastructure
    implements ExtensionInfrastructure
{
    private final MirrorSelector mirrorSelector;

    private final MavenSessionHandler sessionHandler;

    private MavenPomReader pomReader;

    private ArtifactManager artifactManager;

    private MavenMetadataReader metadataReader;

    private XMLInfrastructure xml;

    private XPathManager xpaths;

    private ExecutorService executor;
    
    private HttpClientTransport http;

    private Optional<CacheProvider> cacheProvider;
    
    private File cacheDir;
    
    public File getCacheDir()
    {
        return cacheDir;
    }

    @Inject
    public GalleyInfrastructure( MavenSessionHandler session, MirrorSelector mirrorSelector)
    {
        this.mirrorSelector = mirrorSelector;
        this.sessionHandler = session;
    }


    @Override
    public GalleyInfrastructure init()
                    throws ManipulationException
    {
        if ( sessionHandler == null )
        {
            return init( null, null, null, null, null,
                         null, null, null );
        }
        else
        {
            return init( sessionHandler.getTargetDir(), sessionHandler.getRemoteRepositories(), sessionHandler.getLocalRepository(),
                         sessionHandler.getSettings(), sessionHandler.getActiveProfiles(), null, null, null );
        }
    }

    @Override
    public GalleyInfrastructure init(final Location customLocation, final Transport customTransport, File cacheDir )
                    throws ManipulationException
    {
        if ( sessionHandler == null )
        {
            return init( null, null, null, null, null,
                         customLocation, customTransport, cacheDir );
        }
        else
        {
            return init( sessionHandler.getTargetDir(), sessionHandler.getRemoteRepositories(), sessionHandler.getLocalRepository(),
                         sessionHandler.getSettings(), sessionHandler.getActiveProfiles(), customLocation, customTransport, cacheDir );
        }
    }

    /**
     * Allow for a 'shell' infrastructure with majority not implemented but just the cache directory for FileIO. This
     * is useful for both tests and usage of FileIO within GME.
     *
     * @param targetDirectory the directory to create the cache in
     * @return this instance
     * @throws ManipulationException if an error occurs.
     */
    @Override
    public GalleyInfrastructure init(File targetDirectory) throws ManipulationException
    {
        if ( sessionHandler == null )
        {
            if ( cacheDir == null )
            {
                cacheDir = new File( targetDirectory, "manipulator-cache" );
            }
            return this;
        }
        else
        {
            throw new ManipulationException("Partial infrastructure creation");
        }
    }


    private GalleyInfrastructure init( final File targetDirectory, final List<ArtifactRepository> remoteRepositories, final ArtifactRepository localRepository,
                      final Settings settings, final List<String> activeProfiles, final Location customLocation,
                       final Transport customTransport, File cacheDir_ )
        throws ManipulationException
    {
        LocationExpander locationExpander;
        try
        {
            final List<Location> custom =
                customLocation == null ? Collections.emptyList()
                                : Collections.singletonList( customLocation );

            locationExpander =
                new GalleyMavenLocationExpander( custom, remoteRepositories, localRepository,
                                           mirrorSelector, settings, activeProfiles );
        }
        catch ( final MalformedURLException e )
        {
            throw new ManipulationException( "Failed to setup Maven-specific LocationExpander: {}", e.getMessage(), e );
        }

        xml = new XMLInfrastructure();
        xpaths = new XPathManager();

        final TransportManager transports = new TransportManagerImpl(transportsOf(customTransport));

        cacheDir = cacheDir_;
        if ( cacheDir == null )
        {
            cacheDir = new File( targetDirectory, "manipulator-cache" );
        }

        final FileEventManager fileEvents = new NoOpFileEventManager();

        final TransferDecoratorManager transportDecorators = new TransferDecoratorManager( Collections.singletonList( new NoOpTransferDecorator()) );
        
        final CacheProvider cache =
            new FileCacheProvider( cacheDir, new HashedLocationPathGenerator(), fileEvents, transportDecorators, false);
        cacheProvider = Optional.of(cache);
        
        final NotFoundCache nfc = new MemoryNotFoundCache();
        executor = Executors.newCachedThreadPool( GalleyInfrastructureModule.threadFactoryOf( 1 ) );

        final TransportManagerConfig config = new TransportManagerConfig(  );

        final TransferManager transfers =
            new TransferManagerImpl( transports, cache, nfc, fileEvents, new DownloadHandler( nfc, config, executor ),
                                     new UploadHandler( nfc, config, executor ), new ListingHandler( nfc ),
                                     new ExistenceHandler( nfc ),
                                     new SpecialPathManagerImpl(),
                                     executor );

        final TypeMapper types = new StandardTypeMapper();
        final ArtifactMetadataManager metadataManager = new ArtifactMetadataManagerImpl( transfers, locationExpander );
        final VersionResolver versionResolver =
            new VersionResolverImpl( new MavenMetadataReader( xml, locationExpander, metadataManager, xpaths ) );

        artifactManager = new ArtifactManagerImpl( transfers, locationExpander, types, versionResolver );

        final MavenPluginDefaults pluginDefaults = new StandardMaven350PluginDefaults();
        final MavenPluginImplications pluginImplications = new StandardMavenPluginImplications( xml );

        pomReader =
            new MavenPomReader( xml, locationExpander, artifactManager, xpaths, pluginDefaults, pluginImplications );

        metadataReader = new MavenMetadataReader( xml, locationExpander, metadataManager, xpaths );

        return this;
    }
    
    Transport[] transportsOf(Transport customTransport) {
        if (customTransport != null) {
            return new Transport[] { customTransport };
        }
        http = new HttpClientTransport( new HttpImpl(new MemoryPasswordManager()));
        return new Transport[] { http,
                new FileTransport(), new ZipJarTransport() };
    }

    public MavenPomReader getPomReader()
    {
        return pomReader;
    }

    public XMLInfrastructure getXml()
    {
        return xml;
    }

    public MavenMetadataReader getMetadataReader()
    {
        return metadataReader;
    }

    public ArtifactManager getArtifactManager()
    {
        return artifactManager;
    }

    public XPathManager getXPath()
    {
        return xpaths;
    }

    public void finish() {
        Try.run(executor::shutdown)
           .andThen(http::shutdown)
           .andThen(() -> cacheProvider.map(CacheProvider::asAdminView).get().shutdown());
    }
}

class GalleyInfrastructureModule {


    static ThreadFactory threadFactoryOf( int callerDepth ) {
        return new WorkerThreadFactory(callerDepth + 1);
    }
    
    static ThreadCaller threadCallerOf( int callerDepth ) {
        return new ThreadCaller(callerDepth + 1);
    }
    
    /*
     * Licensed to the Apache Software Foundation (ASF) under one
     * or more contributor license agreements.  See the NOTICE file
     * distributed with this work for additional information
     * regarding copyright ownership.  The ASF licenses this file
     * to you under the Apache License, Version 2.0 (the
     * "License"); you may not use this file except in compliance
     * with the License.  You may obtain a copy of the License at
     *
     *   http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing,
     * software distributed under the License is distributed on an
     * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
     * KIND, either express or implied.  See the License for the
     * specific language governing permissions and limitations
     * under the License.
     */
    
    /**
     * A factory to create worker threads with a given name prefix.
     */
    static class WorkerThreadFactory implements ThreadFactory {

        final ThreadFactory factory;

        final ThreadCaller caller;

        final AtomicInteger threadIndex;

        static final AtomicInteger POOL_INDEX = new AtomicInteger();

        /**
         * Creates a new thread factory whose threads will have names using the specified prefix.
         *
         * @param namePrefix The prefix for the thread names, may be {@code null} or empty to derive the prefix from the
         *            caller's simple class name.
         */
        WorkerThreadFactory(int callerDepth) {
            this.caller = new ThreadCaller(callerDepth+1);
            this.factory = Executors.defaultThreadFactory();
            threadIndex = new AtomicInteger();
        }

        public Thread newThread(Runnable r) {
            Thread thread = factory.newThread(r);
            thread.setName(caller.simpleName() + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
    
    static class ThreadCaller {
        final StackTraceElement[] stack;
        final int index;
        
        ThreadCaller(int thatIndex) {
            stack = new Throwable().getStackTrace();
            index = thatIndex;
        }
        
        StackTraceElement entry() {
            return stack[index];
        }
        
        String name() {
            return entry().getClassName();
        }
        
        String simpleName() {
            String className = name();
            return className.substring(className.lastIndexOf('.'));
        }
    }

}