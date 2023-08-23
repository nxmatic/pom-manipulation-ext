package org.commonjava.maven.ext.core.enterprise;

import java.util.Collections;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.commonjava.maven.ext.common.ManipulationComponent;
import org.commonjava.maven.galley.config.TransportManagerConfig;
import org.commonjava.maven.galley.embed.EmbeddableCDIExecutorProducer;
import org.commonjava.maven.galley.embed.EmbeddableCDIProducer;
import org.commonjava.maven.galley.io.TransferDecoratorManager;
import org.commonjava.maven.galley.maven.parse.XMLInfrastructure;
import org.commonjava.maven.galley.maven.spi.defaults.MavenPluginDefaults;
import org.commonjava.maven.galley.maven.spi.defaults.MavenPluginImplications;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import noname.devenv.plexus.ForeignExtensionPackages;

@ForeignExtensionPackages.Import(groupId = "noname.maven.extensions.devenv", artifactId = "maven-devenv-extension", packages = {
        "noname.devenv.enterprise" })
@ManipulationComponent
@Named("galley")
@Singleton
public class GalleyProducerModule extends AbstractModule {
    GalleyProducerModule() {
        super();
    }

    @Override
    protected void configure() {
        requireBinding(ApplicationScoped.class);
        requireBinding(EmbeddableCDIProducer.class);
        requireBinding(EmbeddableCDIExecutorProducer.class);
      }

    Provider<EmbeddableCDIProducer> galleyProvider = () -> {
        throw new IllegalStateException("No galley scope producer");
    };

    EmbeddableCDIProducer galleyProducer() {
        return galleyProvider.get();
    }

//    @Provides
//    @Default
    XMLInfrastructure xmlInfrastructure() {
        return galleyProducer().getXml();
    }

//    @Provides
//    @Default
    FileEventManager fileEventManager() {
        return galleyProducer().getFileEventManager();
    }

//    @Provides
//    @Default
    NotFoundCache notFoundCache() {
        return galleyProducer().getNotFoundCache();
    }

//    @Provides
//    @Default
    TransportManagerConfig transportManagerConfig() {
        return galleyProducer().getTransportManagerConfig();
    }

//    @Provides
//    @Default
    TransferDecoratorManager transportDecoratorManager() {
        return new TransferDecoratorManager(Collections.singletonList(galleyProducer().getTransferDecorator()));
    }

//    @Provides
//    @Default
    PathGenerator pathGenerator() {
        return galleyProducer().getPathGenerator();
    }

//    @Provides
//    @Default
    MavenPluginDefaults mavenPluginDefaults() {
        return galleyProducer().getPluginDefaults();
    }

//    @Provides
//    @Default
    MavenPluginImplications mavenPluginimplications() {
        return galleyProducer().getPluginImplications();
    }

}