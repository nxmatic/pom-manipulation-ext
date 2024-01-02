package org.commonjava.maven.ext.core.io;

import static org.junit.Assert.assertNotNull;

import java.util.Collection;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.util.TypeLiteral;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.commonjava.maven.ext.core.fixture.PlexusTestRunner;
import org.commonjava.maven.ext.io.ModelIO;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(PlexusTestRunner.class)
@Named("pom-manipulation")
public class TestScopedBeansInjection {

    @Named("pom-manipulation")
    @Singleton
    @ApplicationScoped
    static class ApplicationScopedBean {

        @Inject
        Collection<ModelIO> sessionBean;

        
        ModelIO sessionScoped() {
            return sessionBean.iterator().next();
        }
    }
    
    @Inject
    private ApplicationScopedBean bean;
    
    @Test
    public void bootUp()
    {
        assertNotNull( bean );
        assertNotNull( bean.sessionScoped() );
    }
    
}


