package org.apache.catalina;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.mapper.Mapper;

/**
 * A <strong>Service</strong> is a group of one or more
 * <strong>Connectors</strong> that share a single <strong>Container</strong>
 * to process their incoming requests.  This arrangement allows, for example,
 * a non-SSL and SSL connector to share the same population of web apps.
 * <p>
 * A given JVM can contain any number of Service instances; however, they are
 * completely independent of each other and share only the basic JVM facilities
 * and classes on the system class path.
 *
 * @author Craig R. McClanahan
 * 这个才是Tomcat的核心组件，其他的都是一些虚的东西
 */
public interface Service extends Lifecycle {

    /**
     * @return the <code>Container</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     */
    Engine getContainer();

    void setContainer(Container container);

    /**
     * Set the <code>Engine</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     *
     * @param engine The new Engine
     */
    void setContainer(Engine engine);

    String getName();

    void setName(String name);

    Server getServer();

    void setServer(Server server);

    ClassLoader getParentClassLoader();

    void setParentClassLoader(ClassLoader parent);

    String getDomain();

    /**
     * Add a new Connector to the set of defined Connectors, and associate it
     * with this Service's Container.
     *
     * @param connector The Connector to be added
     */
    void addConnector(Connector connector);

    /**
     * Find and return the set of Connectors associated with this Service.
     *
     * @return the set of associated Connectors
     */
    Connector[] findConnectors();

    /**
     * Remove the specified Connector from the set associated from this
     * Service.  The removed Connector will also be disassociated from our
     * Container.
     *
     * @param connector The Connector to be removed
     */
    void removeConnector(Connector connector);

    /**
     * Adds a named executor to the service
     * @param ex Executor
     */
    void addExecutor(Executor ex);

    /**
     * Retrieves all executors
     * @return Executor[]
     */
    Executor[] findExecutors();

    /**
     * Retrieves executor by name, null if not found
     * @param name String
     * @return Executor
     */
    Executor getExecutor(String name);

    /**
     * Removes an executor from the service
     * @param ex Executor
     */
    void removeExecutor(Executor ex);

    /**
     * @return the mapper associated with this Service.
     */
    Mapper getMapper();
}
