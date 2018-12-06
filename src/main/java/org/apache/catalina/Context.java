package org.apache.catalina;

import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletSecurityElement;
import javax.servlet.descriptor.JspConfigDescriptor;

import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.tomcat.ContextBind;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.descriptor.web.ApplicationParameter;
import org.apache.tomcat.util.descriptor.web.ErrorPage;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.http.CookieProcessor;

/**
 * A <b>Context</b> is a Container that represents a servlet context, and
 * therefore an individual web application, in the Catalina servlet engine.
 * It is therefore useful in almost every deployment of Catalina (even if a
 * Connector attached to a web server (such as Apache) uses the web server's
 * facilities to identify the appropriate Wrapper to handle this request.
 * It also provides a convenient mechanism to use Interceptors that see
 * every request processed by this particular web application.
 * <p>
 * The parent Container attached to a Context is generally a Host, but may
 * be some other implementation, or may be omitted if it is not necessary.
 * <p>
 * The child containers attached to a Context are generally implementations
 * of Wrapper (representing individual servlet definitions).
 * <p>
 * <p>
 * 我不是很明白的一点就是：为什么一个接口写了1700多行的代码，而且绝大部分都是get-set方法
 * 难道这种方法也提供给外面使用，我就不相信了。
 * 我感觉接口应该提供那些实在的方法，没必要把get-set方法提供出去。。。真是一个荒唐的接口
 */
public interface Context extends Container, ContextBind {
    String ADD_WELCOME_FILE_EVENT = "addWelcomeFile";
    String REMOVE_WELCOME_FILE_EVENT = "removeWelcomeFile";
    String CLEAR_WELCOME_FILES_EVENT = "clearWelcomeFiles";
    String CHANGE_SESSION_ID_EVENT = "changeSessionId";

    boolean getAllowCasualMultipartParsing();

    void setAllowCasualMultipartParsing(boolean allowCasualMultipartParsing);

    Object[] getApplicationEventListeners();

    void setApplicationEventListeners(Object listeners[]);

    Object[] getApplicationLifecycleListeners();

    void setApplicationLifecycleListeners(Object listeners[]);

    String getCharset(Locale locale);

    URL getConfigFile();

    void setConfigFile(URL configFile);

    boolean getConfigured();

    void setConfigured(boolean configured);

    boolean getCookies();

    void setCookies(boolean cookies);

    String getSessionCookieName();

    void setSessionCookieName(String sessionCookieName);

    boolean getUseHttpOnly();

    void setUseHttpOnly(boolean useHttpOnly);

    String getSessionCookieDomain();

    void setSessionCookieDomain(String sessionCookieDomain);

    String getSessionCookiePath();

    void setSessionCookiePath(String sessionCookiePath);

    boolean getSessionCookiePathUsesTrailingSlash();

    void setSessionCookiePathUsesTrailingSlash(boolean sessionCookiePathUsesTrailingSlash);

    boolean getCrossContext();

    String getAltDDName();

    void setAltDDName(String altDDName);

    void setCrossContext(boolean crossContext);

    boolean getDenyUncoveredHttpMethods();

    void setDenyUncoveredHttpMethods(boolean denyUncoveredHttpMethods);

    String getDisplayName();

    void setDisplayName(String displayName);

    boolean getDistributable();

    void setDistributable(boolean distributable);

    String getDocBase();

    void setDocBase(String docBase);

    String getEncodedPath();

    boolean getIgnoreAnnotations();

    void setIgnoreAnnotations(boolean ignoreAnnotations);

    LoginConfig getLoginConfig();

    void setLoginConfig(LoginConfig config);

    NamingResourcesImpl getNamingResources();

    void setNamingResources(NamingResourcesImpl namingResources);

    String getPath();

    void setPath(String path);

    String getPublicId();

    void setPublicId(String publicId);

    boolean getReloadable();

    void setReloadable(boolean reloadable);

    boolean getOverride();

    void setOverride(boolean override);

    boolean getPrivileged();

    void setPrivileged(boolean privileged);

    ServletContext getServletContext();

    int getSessionTimeout();

    void setSessionTimeout(int timeout);

    boolean getSwallowAbortedUploads();

    void setSwallowAbortedUploads(boolean swallowAbortedUploads);

    boolean getSwallowOutput();

    void setSwallowOutput(boolean swallowOutput);

    String getWrapperClass();

    void setWrapperClass(String wrapperClass);

    boolean getXmlNamespaceAware();

    void setXmlNamespaceAware(boolean xmlNamespaceAware);

    boolean getXmlValidation();

    void setXmlValidation(boolean xmlValidation);

    boolean getXmlBlockExternal();

    void setXmlBlockExternal(boolean xmlBlockExternal);

    boolean getTldValidation();

    void setTldValidation(boolean tldValidation);

    JarScanner getJarScanner();

    void setJarScanner(JarScanner jarScanner);

    Authenticator getAuthenticator();

    void setLogEffectiveWebXml(boolean logEffectiveWebXml);

    boolean getLogEffectiveWebXml();

    InstanceManager getInstanceManager();

    void setInstanceManager(InstanceManager instanceManager);

    void setContainerSciFilter(String containerSciFilter);

    String getContainerSciFilter();

    void addApplicationListener(String listener);

    void addApplicationParameter(ApplicationParameter parameter);

    void addConstraint(SecurityConstraint constraint);

    void addErrorPage(ErrorPage errorPage);

    void addFilterDef(FilterDef filterDef);

    void addFilterMap(FilterMap filterMap);

    void addFilterMapBefore(FilterMap filterMap);

    @Deprecated
    void addInstanceListener(String listener);

    void addLocaleEncodingMappingParameter(String locale, String encoding);

    void addMimeMapping(String extension, String mimeType);

    void addParameter(String name, String value);

    void addRoleMapping(String role, String link);

    void addSecurityRole(String role);

    void addServletMapping(String pattern, String name);

    void addServletMapping(String pattern, String name, boolean jspWildcard);

    void addWatchedResource(String name);

    void addWelcomeFile(String name);

    void addWrapperLifecycle(String listener);

    void addWrapperListener(String listener);

    Wrapper createWrapper();

    String[] findApplicationListeners();

    ApplicationParameter[] findApplicationParameters();

    SecurityConstraint[] findConstraints();

    ErrorPage findErrorPage(int errorCode);

    ErrorPage findErrorPage(String exceptionType);

    ErrorPage[] findErrorPages();

    FilterDef findFilterDef(String filterName);

    FilterDef[] findFilterDefs();

    FilterMap[] findFilterMaps();

    @Deprecated
    String[] findInstanceListeners();

    String findMimeMapping(String extension);

    String[] findMimeMappings();

    String findParameter(String name);

    String[] findParameters();

    String findRoleMapping(String role);

    boolean findSecurityRole(String role);

    String[] findSecurityRoles();

    String findServletMapping(String pattern);

    String[] findServletMappings();

    String findStatusPage(int status);

    int[] findStatusPages();

    ThreadBindingListener getThreadBindingListener();

    void setThreadBindingListener(ThreadBindingListener threadBindingListener);

    String[] findWatchedResources();

    boolean findWelcomeFile(String name);

    String[] findWelcomeFiles();

    String[] findWrapperLifecycles();

    String[] findWrapperListeners();

    boolean fireRequestInitEvent(ServletRequest request);

    boolean fireRequestDestroyEvent(ServletRequest request);

    void reload();

    void removeApplicationListener(String listener);

    void removeApplicationParameter(String name);

    void removeConstraint(SecurityConstraint constraint);

    void removeErrorPage(ErrorPage errorPage);

    void removeFilterDef(FilterDef filterDef);

    void removeFilterMap(FilterMap filterMap);

    @Deprecated
    void removeInstanceListener(String listener);

    void removeMimeMapping(String extension);

    void removeParameter(String name);

    void removeRoleMapping(String role);

    void removeSecurityRole(String role);

    void removeServletMapping(String pattern);

    void removeWatchedResource(String name);

    void removeWelcomeFile(String name);

    void removeWrapperLifecycle(String listener);

    void removeWrapperListener(String listener);

    String getRealPath(String path);

    int getEffectiveMajorVersion();

    void setEffectiveMajorVersion(int major);

    int getEffectiveMinorVersion();

    void setEffectiveMinorVersion(int minor);

    JspConfigDescriptor getJspConfigDescriptor();

    void setJspConfigDescriptor(JspConfigDescriptor descriptor);

    void addServletContainerInitializer(ServletContainerInitializer sci, Set<Class<?>> classes);

    boolean getPaused();

    boolean isServlet22();

    Set<String> addServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement);

    void setResourceOnlyServlets(String resourceOnlyServlets);

    String getResourceOnlyServlets();

    boolean isResourceOnlyServlet(String servletName);

    String getBaseName();

    void setWebappVersion(String webappVersion);

    String getWebappVersion();

    void setFireRequestListenersOnForwards(boolean enable);

    boolean getFireRequestListenersOnForwards();

    void setPreemptiveAuthentication(boolean enable);

    boolean getPreemptiveAuthentication();

    void setSendRedirectBody(boolean enable);

    boolean getSendRedirectBody();

    Loader getLoader();

    void setLoader(Loader loader);

    WebResourceRoot getResources();

    void setResources(WebResourceRoot resources);

    Manager getManager();

    void setManager(Manager manager);

    void setAddWebinfClassesResources(boolean addWebinfClassesResources);

    boolean getAddWebinfClassesResources();

    void addPostConstructMethod(String clazz, String method);

    void addPreDestroyMethod(String clazz, String method);

    void removePostConstructMethod(String clazz);

    void removePreDestroyMethod(String clazz);

    String findPostConstructMethod(String clazz);

    String findPreDestroyMethod(String clazz);

    Map<String, String> findPostConstructMethods();

    Map<String, String> findPreDestroyMethods();

    Object getNamingToken();

    void setCookieProcessor(CookieProcessor cookieProcessor);

    CookieProcessor getCookieProcessor();

    void setValidateClientProvidedNewSessionId(boolean validateClientProvidedNewSessionId);

    boolean getValidateClientProvidedNewSessionId();

    void setMapperContextRootRedirectEnabled(boolean mapperContextRootRedirectEnabled);

    boolean getMapperContextRootRedirectEnabled();

    void setMapperDirectoryRedirectEnabled(boolean mapperDirectoryRedirectEnabled);

    boolean getMapperDirectoryRedirectEnabled();

    void setUseRelativeRedirects(boolean useRelativeRedirects);

    boolean getUseRelativeRedirects();
}
