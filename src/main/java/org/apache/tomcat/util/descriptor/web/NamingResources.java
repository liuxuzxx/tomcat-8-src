package org.apache.tomcat.util.descriptor.web;

/**
 * Defines an interface for the object that is added to the representation of a
 * JNDI resource in web.xml to enable it to also be the implementation of that
 * JNDI resource. Only Catalina implements this interface but because the
 * web.xml representation is shared this interface has to be visible to Catalina
 * and Jasper.
 */
public interface NamingResources {

    void addEnvironment(ContextEnvironment ce);
    void removeEnvironment(String name);

    void addResource(ContextResource cr);
    void removeResource(String name);

    void addResourceLink(ContextResourceLink crl);
    void removeResourceLink(String name);

    Object getContainer();
}
