package org.apache.catalina;

import java.io.File;

import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.startup.Catalina;

/**
 * A <code>Server</code> element represents the entire Catalina
 * servlet container.  Its attributes represent the characteristics of
 * the servlet container as a whole.  A <code>Server</code> may contain
 * one or more <code>Services</code>, and the top level set of naming
 * resources.
 * <p>
 * Normally, an implementation of this interface will also implement
 * <code>Lifecycle</code>, such that when the <code>start()</code> and
 * <code>stop()</code> methods are called, all of the defined
 * <code>Services</code> are also started or stopped.
 * <p>
 * In between, the implementation must open a server socket on the port number
 * specified by the <code>port</code> property.  When a connection is accepted,
 * the first line is read and compared with the specified shutdown command.
 * If the command matches, shutdown of the server is initiated.
 * <p>
 * <strong>NOTE</strong> - The concrete implementation of this class should
 * register the (singleton) instance with the <code>ServerFactory</code>
 * class in its constructor(s).
 *
 * @author Craig R. McClanahan
 */
public interface Server extends Lifecycle {

    NamingResourcesImpl getGlobalNamingResources();

    void setGlobalNamingResources(NamingResourcesImpl globalNamingResources);

    javax.naming.Context getGlobalNamingContext();

    int getPort();

    void setPort(int port);

    String getAddress();

    void setAddress(String address);

    String getShutdown();

    void setShutdown(String shutdown);


    /**
     * @return the parent class loader for this component. If not set, return
     * {@link #getCatalina()} {@link Catalina#getParentClassLoader()}. If
     * catalina has not been set, return the system class loader.
     */
    ClassLoader getParentClassLoader();


    /**
     * Set the parent class loader for this server.
     *
     * @param parent The new parent class loader
     */
    void setParentClassLoader(ClassLoader parent);


    /**
     * @return the outer Catalina startup/shutdown component if present.
     */
    Catalina getCatalina();

    /**
     * Set the outer Catalina startup/shutdown component if present.
     *
     * @param catalina the outer Catalina component
     */
    void setCatalina(Catalina catalina);


    /**
     * @return the configured base (instance) directory. Note that home and base
     * may be the same (and are by default). If this is not set the value
     * returned by {@link #getCatalinaHome()} will be used.
     */
    File getCatalinaBase();

    /**
     * Set the configured base (instance) directory. Note that home and base
     * may be the same (and are by default).
     *
     * @param catalinaBase the configured base directory
     */
    void setCatalinaBase(File catalinaBase);


    /**
     * @return the configured home (binary) directory. Note that home and base
     * may be the same (and are by default).
     */
    File getCatalinaHome();

    /**
     * Set the configured home (binary) directory. Note that home and base
     * may be the same (and are by default).
     *
     * @param catalinaHome the configured home directory
     */
    void setCatalinaHome(File catalinaHome);

    /**
     * Add a new Service to the set of defined Services.
     *
     * @param service The Service to be added
     */
    void addService(Service service);


    /**
     * Wait until a proper shutdown command is received, then return.
     */
    void await();


    /**
     * Find the specified Service
     *
     * @param name Name of the Service to be returned
     * @return the specified Service, or <code>null</code> if none exists.
     */
    Service findService(String name);


    /**
     * @return the set of Services defined within this Server.
     */
    Service[] findServices();


    /**
     * Remove the specified Service from the set associated from this
     * Server.
     *
     * @param service The Service to be removed
     */
    void removeService(Service service);


    /**
     * @return the token necessary for operations on the associated JNDI naming
     * context.
     */
    Object getNamingToken();
}
