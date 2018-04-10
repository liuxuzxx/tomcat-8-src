
package org.apache.catalina.connector;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import javax.management.ObjectName;

import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.core.AprLifecycleListener;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.res.StringManager;
import org.lx.tomcat.util.SystemUtil;


/**
 * Implementation of a Coyote connector.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * 其实，我认为tomcat最精彩的代码部分，莫过于Acceptor配合Poller进行Socket的变成的那块设计
 * 代码的可读性，我就不说了，确实是我看的源代码当中最丑的一个了，没有之一
 * 对于另外一种架构：Connector和Engine什么的，我其实感觉没啥。
 */
public class Connector extends LifecycleMBeanBase {

    private static final Log log = LogFactory.getLog(Connector.class);


    /**
     * Alternate flag to enable recycling of facades.
     */
    public static final boolean RECYCLE_FACADES =
        Boolean.parseBoolean(System.getProperty("org.apache.catalina.connector.RECYCLE_FACADES", "false"));

    public Connector() {
        this(null);
    }

    public Connector(String protocol) {
        SystemUtil.logInfo(this, "启动Service当中的Connector", protocol);
        setProtocol(protocol);
        // Instantiate protocol handler
        ProtocolHandler p = null;
        try {
            Class<?> clazz = Class.forName(protocolHandlerClassName);
            p = (ProtocolHandler) clazz.newInstance();
        } catch (Exception e) {
            log.error(sm.getString(
                "coyoteConnector.protocolHandlerInstantiationFailed"), e);
        } finally {
            this.protocolHandler = p;
        }

        if (!Globals.STRICT_SERVLET_COMPLIANCE) {
            URIEncoding = "UTF-8";
            URIEncodingLower = URIEncoding.toLowerCase(Locale.ENGLISH);
        }
    }

    /**
     * The <code>Service</code> we are associated with (if any).
     * 保持的自己上级的引用
     */
    protected Service service = null;


    /**
     * Do we allow TRACE ?
     */
    protected boolean allowTrace = false;


    /**
     * Default timeout for asynchronous requests (ms).
     */
    protected long asyncTimeout = 30000;


    /**
     * The "enable DNS lookups" flag for this Connector.
     */
    protected boolean enableLookups = false;


    /**
     * Is generation of X-Powered-By response header enabled/disabled?
     */
    protected boolean xpoweredBy = false;


    /**
     * The port number on which we listen for requests.
     */
    protected int port = -1;


    /**
     * The server name to which we should pretend requests to this Connector
     * were directed.  This is useful when operating Tomcat behind a proxy
     * server, so that redirects get constructed accurately.  If not specified,
     * the server name included in the <code>Host</code> header is used.
     */
    protected String proxyName = null;


    /**
     * The server port to which we should pretend requests to this Connector
     * were directed.  This is useful when operating Tomcat behind a proxy
     * server, so that redirects get constructed accurately.  If not specified,
     * the port number specified by the <code>port</code> property is used.
     */
    protected int proxyPort = 0;


    /**
     * The redirect port for non-SSL to SSL redirects.
     */
    protected int redirectPort = 443;


    /**
     * The request scheme that will be set on all requests received
     * through this connector.
     */
    protected String scheme = "http";


    /**
     * The secure connection flag that will be set on all requests received
     * through this connector.
     */
    protected boolean secure = false;


    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(Constants.Package);

    /**
     * The maximum number of parameters (GET plus POST) which will be
     * automatically parsed by the container. 10000 by default. A value of less
     * than 0 means no limit.
     * 最大参数的数量个数
     */
    protected int maxParameterCount = 10000;

    /**
     * Maximum size of a POST which will be automatically parsed by the
     * container. 2MB by default.
     */
    protected int maxPostSize = 2 * 1024 * 1024;


    /**
     * Maximum size of a POST which will be saved by the container
     * during authentication. 4kB by default
     */
    protected int maxSavePostSize = 4 * 1024;

    /**
     * Comma-separated list of HTTP methods that will be parsed according
     * to POST-style rules for application/x-www-form-urlencoded request bodies.
     */
    protected String parseBodyMethods = "POST";

    /**
     * A Set of methods determined by {@link #parseBodyMethods}.
     */
    protected HashSet<String> parseBodyMethodsSet;


    /**
     * Flag to use IP-based virtual hosting.
     */
    protected boolean useIPVHosts = false;


    /**
     * Coyote Protocol handler class name.
     * Defaults to the Coyote HTTP/1.1 protocolHandler.
     */
    protected String protocolHandlerClassName = "org.apache.coyote.http11.Http11NioProtocol";


    /**
     * Coyote protocol handler.
     */
    protected final ProtocolHandler protocolHandler;


    /**
     * Coyote adapter.
     */
    protected Adapter adapter = null;


    /**
     * URI encoding.
     */
    protected String URIEncoding = null;
    protected String URIEncodingLower = null;


    /**
     * URI encoding as body.
     */
    protected boolean useBodyEncodingForURI = false;


    protected static final HashMap<String, String> replacements =
        new HashMap<>();

    static {
        replacements.put("acceptCount", "backlog");
        replacements.put("connectionLinger", "soLinger");
        replacements.put("connectionTimeout", "soTimeout");
        replacements.put("rootFile", "rootfile");
    }

    /**
     * Return a configured property.
     */
    public Object getProperty(String name) {
        String repl = name;
        if (replacements.get(name) != null) {
            repl = replacements.get(name);
        }
        return IntrospectionUtils.getProperty(protocolHandler, repl);
    }


    /**
     * Set a configured property.
     */
    public boolean setProperty(String name, String value) {
        String repl = name;
        if (replacements.get(name) != null) {
            repl = replacements.get(name);
        }
        return IntrospectionUtils.setProperty(protocolHandler, repl, value);
    }

    public Object getAttribute(String name) {
        return getProperty(name);
    }

    public void setAttribute(String name, Object value) {
        setProperty(name, String.valueOf(value));
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public boolean getAllowTrace() {
        return allowTrace;

    }

    /**
     * Set the allowTrace flag, to disable or enable the TRACE HTTP method.
     *
     * @param allowTrace The new allowTrace flag
     */
    public void setAllowTrace(boolean allowTrace) {
        this.allowTrace = allowTrace;
        setProperty("allowTrace", String.valueOf(allowTrace));
    }

    public long getAsyncTimeout() {
        return asyncTimeout;
    }

    public void setAsyncTimeout(long asyncTimeout) {
        this.asyncTimeout = asyncTimeout;
        setProperty("asyncTimeout", String.valueOf(asyncTimeout));
    }

    public boolean getEnableLookups() {
        return enableLookups;
    }

    public void setEnableLookups(boolean enableLookups) {
        this.enableLookups = enableLookups;
        setProperty("enableLookups", String.valueOf(enableLookups));
    }

    public int getMaxHeaderCount() {
        return ((Integer) getProperty("maxHeaderCount")).intValue();
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        setProperty("maxHeaderCount", String.valueOf(maxHeaderCount));
    }

    public int getMaxParameterCount() {
        return maxParameterCount;
    }

    public void setMaxParameterCount(int maxParameterCount) {
        this.maxParameterCount = maxParameterCount;
    }

    public int getMaxPostSize() {
        return maxPostSize;
    }

    public void setMaxPostSize(int maxPostSize) {
        this.maxPostSize = maxPostSize;
    }

    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }

    public void setMaxSavePostSize(int maxSavePostSize) {
        this.maxSavePostSize = maxSavePostSize;
        setProperty("maxSavePostSize", String.valueOf(maxSavePostSize));
    }

    public String getParseBodyMethods() {
        return this.parseBodyMethods;
    }

    public void setParseBodyMethods(String methods) {
        HashSet<String> methodSet = new HashSet<>();
        if (null != methods) {
            methodSet.addAll(Arrays.asList(methods.split("\\s*,\\s*")));
        }
        if (methodSet.contains("TRACE")) {
            throw new IllegalArgumentException(sm.getString("coyoteConnector.parseBodyMethodNoTrace"));
        }
        this.parseBodyMethods = methods;
        this.parseBodyMethodsSet = methodSet;
    }

    protected boolean isParseBodyMethod(String method) {
        return parseBodyMethodsSet.contains(method);
    }

    public int getPort() {
        return port;

    }

    public void setPort(int port) {
        this.port = port;
        setProperty("port", String.valueOf(port));
    }

    public int getLocalPort() {
        return ((Integer) getProperty("localPort")).intValue();
    }

    public String getProtocol() {

        if ("org.apache.coyote.http11.Http11NioProtocol".equals
            (getProtocolHandlerClassName())
            || "org.apache.coyote.http11.Http11AprProtocol".equals
            (getProtocolHandlerClassName())) {
            return "HTTP/1.1";
        } else if ("org.apache.coyote.ajp.AjpNioProtocol".equals
            (getProtocolHandlerClassName())
            || "org.apache.coyote.ajp.AjpAprProtocol".equals
            (getProtocolHandlerClassName())) {
            return "AJP/1.3";
        }
        return getProtocolHandlerClassName();
    }

    public void setProtocol(String protocol) {
        if (AprLifecycleListener.isAprAvailable()) {
            if ("HTTP/1.1".equals(protocol)) {
                setProtocolHandlerClassName
                    ("org.apache.coyote.http11.Http11AprProtocol");
            } else if ("AJP/1.3".equals(protocol)) {
                setProtocolHandlerClassName
                    ("org.apache.coyote.ajp.AjpAprProtocol");
            } else if (protocol != null) {
                setProtocolHandlerClassName(protocol);
            } else {
                setProtocolHandlerClassName
                    ("org.apache.coyote.http11.Http11AprProtocol");
            }
        } else {
            if ("HTTP/1.1".equals(protocol)) {
                setProtocolHandlerClassName
                    ("org.apache.coyote.http11.Http11NioProtocol");
            } else if ("AJP/1.3".equals(protocol)) {
                setProtocolHandlerClassName
                    ("org.apache.coyote.ajp.AjpNioProtocol");
            } else if (protocol != null) {
                setProtocolHandlerClassName(protocol);
            }
        }

    }

    public String getProtocolHandlerClassName() {
        return protocolHandlerClassName;
    }

    public void setProtocolHandlerClassName(String protocolHandlerClassName) {
        this.protocolHandlerClassName = protocolHandlerClassName;
    }

    public ProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    public String getProxyName() {
        return proxyName;
    }

    public void setProxyName(String proxyName) {
        if (proxyName != null && proxyName.length() > 0) {
            this.proxyName = proxyName;
            setProperty("proxyName", proxyName);
        } else {
            this.proxyName = null;
        }
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
        setProperty("proxyPort", String.valueOf(proxyPort));
    }

    public int getRedirectPort() {
        return redirectPort;
    }

    public void setRedirectPort(int redirectPort) {
        this.redirectPort = redirectPort;
        setProperty("redirectPort", String.valueOf(redirectPort));
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public boolean getSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
        setProperty("secure", Boolean.toString(secure));
    }

    public String getURIEncoding() {
        return this.URIEncoding;
    }

    public String getURIEncodingLower() {
        return this.URIEncodingLower;
    }

    public void setURIEncoding(String URIEncoding) {
        this.URIEncoding = URIEncoding;
        if (URIEncoding == null) {
            URIEncodingLower = null;
        } else {
            this.URIEncodingLower = URIEncoding.toLowerCase(Locale.ENGLISH);
        }
        setProperty("uRIEncoding", URIEncoding);
    }

    public boolean getUseBodyEncodingForURI() {
        return useBodyEncodingForURI;
    }

    public void setUseBodyEncodingForURI(boolean useBodyEncodingForURI) {
        this.useBodyEncodingForURI = useBodyEncodingForURI;
        setProperty("useBodyEncodingForURI", String.valueOf(useBodyEncodingForURI));
    }

    public boolean getXpoweredBy() {
        return xpoweredBy;
    }

    public void setXpoweredBy(boolean xpoweredBy) {
        this.xpoweredBy = xpoweredBy;
        setProperty("xpoweredBy", String.valueOf(xpoweredBy));
    }

    public void setUseIPVHosts(boolean useIPVHosts) {
        this.useIPVHosts = useIPVHosts;
        setProperty("useIPVHosts", String.valueOf(useIPVHosts));
    }

    public boolean getUseIPVHosts() {
        return useIPVHosts;
    }

    public String getExecutorName() {
        Object obj = protocolHandler.getExecutor();
        if (obj instanceof org.apache.catalina.Executor) {
            return ((org.apache.catalina.Executor) obj).getName();
        }
        return "Internal";
    }

    /**
     * Create (or allocate) and return a Request object suitable for
     * specifying the contents of a Request to the responsible Container.
     */
    public Request createRequest() {
        Request request = new Request();
        request.setConnector(this);
        return request;
    }

    /**
     * Create (or allocate) and return a Response object suitable for
     * receiving the contents of a Response from the responsible Container.
     */
    public Response createResponse() {
        Response response = new Response();
        response.setConnector(this);
        return response;
    }


    protected String createObjectNameKeyProperties(String type) {
        Object addressObj = getProperty("address");
        StringBuilder sb = new StringBuilder("type=");
        sb.append(type);
        sb.append(",port=");
        int port = getPort();
        if (port > 0) {
            sb.append(port);
        } else {
            sb.append("auto-");
            sb.append(getProperty("nameIndex"));
        }
        String address = "";
        if (addressObj instanceof InetAddress) {
            address = ((InetAddress) addressObj).getHostAddress();
        } else if (addressObj != null) {
            address = addressObj.toString();
        }
        if (address.length() > 0) {
            sb.append(",address=");
            sb.append(ObjectName.quote(address));
        }
        return sb.toString();
    }


    /**
     * Pause the connector.
     */
    public void pause() {
        try {
            protocolHandler.pause();
        } catch (Exception e) {
            log.error(sm.getString
                ("coyoteConnector.protocolHandlerPauseFailed"), e);
        }
    }

    /**
     * Pause the connector.
     */
    public void resume() {
        try {
            protocolHandler.resume();
        } catch (Exception e) {
            log.error(sm.getString
                ("coyoteConnector.protocolHandlerResumeFailed"), e);
        }
    }

    @Override
    protected void initInternal() throws LifecycleException {
        super.initInternal();
        // Initialize adapter
        adapter = new CoyoteAdapter(this);
        protocolHandler.setAdapter(adapter);
        // Make sure parseBodyMethodsSet has a default
        if (null == parseBodyMethodsSet) {
            setParseBodyMethods(getParseBodyMethods());
        }
        if (protocolHandler.isAprRequired() &&
            !AprLifecycleListener.isAprAvailable()) {
            throw new LifecycleException(
                sm.getString("coyoteConnector.protocolHandlerNoApr",
                    getProtocolHandlerClassName()));
        }
        try {
            protocolHandler.init();
        } catch (Exception e) {
            throw new LifecycleException
                (sm.getString
                    ("coyoteConnector.protocolHandlerInitializationFailed"), e);
        }
    }


    /**
     * Begin processing requests via this Connector.
     *
     * @throws LifecycleException if a fatal startup error occurs
     */
    @Override
    protected void startInternal() throws LifecycleException {

        // Validate settings before starting
        if (getPort() < 0) {
            throw new LifecycleException(sm.getString(
                "coyoteConnector.invalidPort", Integer.valueOf(getPort())));
        }

        setState(LifecycleState.STARTING);

        try {
            protocolHandler.start();
        } catch (Exception e) {
            String errPrefix = "";
            if (this.service != null) {
                errPrefix += "service.getName(): \"" + this.service.getName() + "\"; ";
            }

            throw new LifecycleException
                (errPrefix + " " + sm.getString
                    ("coyoteConnector.protocolHandlerStartFailed"), e);
        }
    }


    /**
     * Terminate processing requests via this Connector.
     *
     * @throws LifecycleException if a fatal shutdown error occurs
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);

        try {
            protocolHandler.stop();
        } catch (Exception e) {
            throw new LifecycleException
                (sm.getString
                    ("coyoteConnector.protocolHandlerStopFailed"), e);
        }
    }


    @Override
    protected void destroyInternal() throws LifecycleException {
        try {
            protocolHandler.destroy();
        } catch (Exception e) {
            throw new LifecycleException
                (sm.getString
                    ("coyoteConnector.protocolHandlerDestroyFailed"), e);
        }

        if (getService() != null) {
            getService().removeConnector(this);
        }

        super.destroyInternal();
    }


    /**
     * Provide a useful toString() implementation as it may be used when logging
     * Lifecycle errors to identify the component.
     */
    @Override
    public String toString() {
        // Not worth caching this right now
        StringBuilder sb = new StringBuilder("Connector[");
        sb.append(getProtocol());
        sb.append('-');
        int port = getPort();
        if (port > 0) {
            sb.append(port);
        } else {
            sb.append("auto-");
            sb.append(getProperty("nameIndex"));
        }
        sb.append(']');
        return sb.toString();
    }


    // -------------------- JMX registration  --------------------

    @Override
    protected String getDomainInternal() {
        Service s = getService();
        if (s == null) {
            return null;
        } else {
            return service.getDomain();
        }
    }

    @Override
    protected String getObjectNameKeyProperties() {
        return createObjectNameKeyProperties("Connector");
    }

}
