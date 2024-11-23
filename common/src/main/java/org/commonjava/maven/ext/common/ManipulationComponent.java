package org.commonjava.maven.ext.common;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Named;
import javax.inject.Qualifier;

@Retention( RetentionPolicy.RUNTIME )
@Target( { TYPE, FIELD } )
@Qualifier
@Named(ManipulationComponent.HINT)
public @interface ManipulationComponent {
        
    static final String HINT = "manipulation";

}
