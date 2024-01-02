package org.commonjava.maven.ext.core.inject;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;

import org.commonjava.maven.ext.core.fixture.PlexusTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith( PlexusTestRunner.class )
@Named("pom-manipulation")
public class TestPostDestroy {
    
    @Named("pom-manipulation")
    static class UnderTest {
        
        @Inject
        AnotherOne anotherOne;
        
        @PostConstruct
        void postConstruct() {
            return;
        }
        
        @PreDestroy
        void preDestroy() {
            return;
        }
    }
    
    @Named("pom-manipulation")
    static class AnotherOne {
        
    }
    
    @Inject
    UnderTest underTest;
    
    @Test
    public void boot() {
        return;
    }

}
