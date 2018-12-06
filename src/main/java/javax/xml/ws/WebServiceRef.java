package javax.xml.ws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface WebServiceRef {
    String name() default "";

    @SuppressWarnings("rawtypes") // Can't use Class<?> because API needs to match specification
    Class type() default java.lang.Object.class;

    @SuppressWarnings("rawtypes") // Can't use Class<?> because API needs to match specification
    Class value() default java.lang.Object.class;

    String wsdlLocation() default "";

    String mappedName() default "";
}
