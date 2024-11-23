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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.SessionScoped;
import org.apache.maven.repository.MirrorSelector;
import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.session.MavenSessionHandler;
import org.commonjava.maven.galley.maven.ArtifactManager;
import org.commonjava.maven.galley.maven.model.view.XPathManager;
import org.commonjava.maven.galley.maven.parse.MavenMetadataReader;
import org.commonjava.maven.galley.maven.parse.MavenPomReader;
import org.commonjava.maven.galley.maven.parse.XMLInfrastructure;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.maven.galley.spi.transport.Transport;
import org.commonjava.maven.galley.spi.transport.TransportManager;
import org.commonjava.maven.galley.transport.htcli.HttpClientTransport;

import io.vavr.control.Try;

/**
 * Manager component responsible for setting up and managing the Galley API instances used to resolve POMs and metadata.
 * 
 * @author jdcasey
 */
@Named(ManipulationComponent.HINT)
@SessionScoped
public class GalleyInfrastructure implements ExtensionInfrastructure {

    @Inject
    public GalleyInfrastructure() {
        super(); // for guice
    }

    @Inject
    private MavenPomReader pomReader;

    @Inject
    private MavenMetadataReader metadataReader;

    @Inject
    private ArtifactManager artifactManager;

    @Inject
    private XPathManager xpaths;

    // @Inject
    // @ExecutorConfig(named = "galley")
    // private ExecutorService executor;

    @Inject
    private HttpClientTransport http;

    public File getCacheDir() {
        return cacheProvider.asAdminView().getConfig().getCacheBasedir();
    }

    public GalleyInfrastructure(MavenSessionHandler session, MirrorSelector mirrorSelector)
            throws ManipulationException {
        throw new UnsupportedOperationException();
    }

    @PostConstruct
    private void postConstruct() throws ManipulationException {
        init();
    }

    @PreDestroy
    private void preDestroy() throws ManipulationException {
        finish();
    }

    public GalleyInfrastructure init() {
        // executor = Executors.newCachedThreadPool(threadFactory);
        return this;
    }

    public void finish() {
        Try.run(http::shutdown).andThen(() -> cacheProvider.asAdminView().shutdown());

        // Try.run(executor::shutdown).andThen(http::shutdown).andThen(() -> cacheProvider.asAdminView().shutdown());
    }

    @Inject
    XMLInfrastructure xml;

    @Inject
    TransportManager transports;

    @Inject
    LocationExpander locationExpander;

    @Inject
    CacheProvider cacheProvider;

    public XMLInfrastructure getXML() {
        return xml;
    }

    public MavenPomReader getPomReader() {
        return pomReader;
    }

    public MavenMetadataReader getMetadataReader() {
        return metadataReader;
    }

    public ArtifactManager getArtifactManager() {
        return artifactManager;
    }

    public XPathManager getXPath() {
        return xpaths;
    }

    @Override
    public GalleyInfrastructure init(File cacheDir) throws ManipulationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Allow for a 'shell' infrastructure with majority not implemented but just the cache directory for FileIO. This is
     * useful for both tests and usage of FileIO within GME.
     *
     * @param targetDirectory the directory to create the cache in
     * @return this instance
     * @throws ManipulationException if an error occurs.
     */
    @Override
    public GalleyInfrastructure init(final Location customLocation, final Transport customTransport, File cacheDir)
            throws ManipulationException {
        throw new UnsupportedOperationException();
    }
}