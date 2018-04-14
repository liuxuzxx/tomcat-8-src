package org.apache.catalina;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;


/**
 * A <b>Host</b> is a Container that represents a virtual host in the
 * Catalina servlet engine.  It is useful in the following types of scenarios:
 * <ul>
 * <li>You wish to use Interceptors that see every single request processed
 *     by this particular virtual host.
 * <li>You wish to run Catalina in with a standalone HTTP connector, but still
 *     want support for multiple virtual hosts.
 * </ul>
 * In general, you would not use a Host when deploying Catalina connected
 * to a web server (such as Apache), because the Connector will have
 * utilized the web server's facilities to determine which Context (or
 * perhaps even which Wrapper) should be utilized to process this request.
 * <p>
 * The parent Container attached to a Host is generally an Engine, but may
 * be some other implementation, or may be omitted if it is not necessary.
 * <p>
 * The child containers attached to a Host are generally implementations
 * of Context (representing an individual servlet context).
 *
 * @author Craig R. McClanahan
 */
public interface Host extends Container {
    /**
     * The ContainerEvent event type sent when a new alias is added
     * by <code>addAlias()</code>.
     */
    String ADD_ALIAS_EVENT = "addAlias";


    /**
     * The ContainerEvent event type sent when an old alias is removed
     * by <code>removeAlias()</code>.
     */
    String REMOVE_ALIAS_EVENT = "removeAlias";

    /**
     * Return the XML root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     * If null, defaults to
     * ${catalina.base}/conf/&lt;engine name&gt;/&lt;host name&gt; directory
     */
    String getXmlBase();

    /**
     * Set the Xml root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     * If null, defaults to
     * ${catalina.base}/conf/&lt;engine name&gt;/&lt;host name&gt; directory
     * @param xmlBase The new XML root
     */
    void setXmlBase(String xmlBase);

    /**
     * Return a default configuration path of this Host. The file will be
     * canonical if possible.
     */
    File getConfigBaseFile();

    /**
     * Return the application root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     */
    String getAppBase();


    /**
     * Return an absolute {@link File} for the appBase of this Host. The file
     * will be canonical if possible. There is no guarantee that that the
     * appBase exists.
     */
    File getAppBaseFile();


    /**
     * Set the application root for this Host.  This can be an absolute
     * pathname, a relative pathname, or a URL.
     *
     * @param appBase The new application root
     */
    void setAppBase(String appBase);


    /**
     * Return the value of the auto deploy flag.  If true, it indicates that
     * this host's child webapps should be discovered and automatically
     * deployed dynamically.
     */
    boolean getAutoDeploy();


    /**
     * Set the auto deploy flag value for this host.
     *
     * @param autoDeploy The new auto deploy flag
     */
    void setAutoDeploy(boolean autoDeploy);


    /**
     * Return the Java class name of the context configuration class
     * for new web applications.
     */
    String getConfigClass();


    /**
     * Set the Java class name of the context configuration class
     * for new web applications.
     *
     * @param configClass The new context configuration class
     */
    void setConfigClass(String configClass);


    /**
     * Return the value of the deploy on startup flag.  If true, it indicates
     * that this host's child webapps should be discovered and automatically
     * deployed.
     */
    boolean getDeployOnStartup();


    /**
     * Set the deploy on startup flag value for this host.
     *
     * @param deployOnStartup The new deploy on startup flag
     */
    void setDeployOnStartup(boolean deployOnStartup);


    /**
     * Return the regular expression that defines the files and directories in
     * the host's appBase that will be ignored by the automatic deployment
     * process.
     */
    String getDeployIgnore();


    /**
     * Return the compiled regular expression that defines the files and
     * directories in the host's appBase that will be ignored by the automatic
     * deployment process.
     */
    Pattern getDeployIgnorePattern();


    /**
     * Set the regular expression that defines the files and directories in
     * the host's appBase that will be ignored by the automatic deployment
     * process.
     */
    void setDeployIgnore(String deployIgnore);


    /**
     * Return the executor that is used for starting and stopping contexts. This
     * is primarily for use by components deploying contexts that want to do
     * this in a multi-threaded manner.
     */
    ExecutorService getStartStopExecutor();


    /**
     * Returns true if the Host will attempt to create directories for appBase and xmlBase
     * unless they already exist.
     * @return true if the Host will attempt to create directories
     */
    boolean getCreateDirs();


    /**
     * Should the Host attempt to create directories for xmlBase and appBase
     * upon startup.
     *
     * @param createDirs The new value for this flag
     */
    void setCreateDirs(boolean createDirs);


    /**
     * Returns true of the Host is configured to automatically undeploy old
     * versions of applications deployed using parallel deployment. This only
     * takes effect is {@link #getAutoDeploy()} also returns true.
     */
    boolean getUndeployOldVersions();


    /**
     * Set to true if the Host should automatically undeploy old versions of
     * applications deployed using parallel deployment. This only takes effect
     * if {@link #getAutoDeploy()} returns true.
     */
    void setUndeployOldVersions(boolean undeployOldVersions);

    /**
     * Add an alias name that should be mapped to this same Host.
     *
     * @param alias The alias to be added
     */
    void addAlias(String alias);


    /**
     * Return the set of alias names for this Host.  If none are defined,
     * a zero length array is returned.
     */
    String[] findAliases();


    /**
     * Remove the specified alias name from the aliases for this Host.
     *
     * @param alias Alias name to be removed
     */
    void removeAlias(String alias);
}
