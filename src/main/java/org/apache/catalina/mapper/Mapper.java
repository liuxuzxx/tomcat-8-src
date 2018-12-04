package org.apache.catalina.mapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.WebResource;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.tomcat.util.buf.Ascii;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.res.StringManager;
import org.lx.tomcat.util.SystemUtil;

public final class Mapper {
    private static final org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory.getLog(Mapper.class);
    private static final StringManager sm = StringManager.getManager(Mapper.class.getPackage().getName());
    private volatile Map<String, MappedHost> hostMap = new HashMap<>();
    private String defaultHostName = null;
    private Map<Context, ContextVersion> contextObjectToContextVersionMap = new ConcurrentHashMap<>();

    public void setDefaultHostName(String defaultHostName) {
        this.defaultHostName = defaultHostName;
    }

    public synchronized void addHost(String name, String[] aliases, Host host) {
        MappedHost newHost = new MappedHost(name, host);
        hostMap.put(name, newHost);
        List<MappedHost> newAliasesHost = Arrays.stream(aliases)
                .map(alias -> new MappedHost(alias, newHost))
                .filter(mappedHost -> mappedHost == hostMap.putIfAbsent(mappedHost.getName(), mappedHost))
                .collect(Collectors.toList());
        newHost.addAliases(newAliasesHost);
    }


    /**
     * Remove a host from the mapper.
     *
     * @param name Virtual host name
     */
    public synchronized void removeHost(String name) {
        MappedHost host = hostMap.get(name);
        if (host == null || host.isAlias()) {
            return;
        }
        hostMap = hostMap.entrySet()
                .stream().filter(entry -> entry.getValue().getRealHost() != host)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Add an alias to an existing host.
     *
     * @param name  The name of the host
     * @param alias The alias to add
     */
    public synchronized void addHostAlias(String name, String alias) {
        MappedHost realHost = hostMap.get(name);
        if (realHost == null) {
            return;
        }
        MappedHost newAlias = new MappedHost(alias, realHost);
        if (newAlias == hostMap.putIfAbsent(newAlias.getName(), newAlias)) {
            realHost.addAlias(newAlias);
        }
    }

    public synchronized void removeHostAlias(String alias) {
        MappedHost hostMapping = hostMap.get(alias);
        if (hostMapping == null || !hostMapping.isAlias()) {
            return;
        }
        if (hostMap.remove(hostMapping.getName(), hostMapping)) {
            hostMapping.getRealHost().removeAlias(hostMapping);
        }

    }

    private void updateContextList(MappedHost realHost, ContextList newContextList) {
        realHost.contextList = newContextList;
        for (MappedHost alias : realHost.getAliases()) {
            alias.contextList = newContextList;
        }
    }

    @Deprecated
    public void addContextVersion(String hostName, Host host, String path, String version, Context context, String[] welcomeResources, WebResourceRoot resources) {
        addContextVersion(hostName, host, path, version, context, welcomeResources, resources, null);
    }

    public void addContextVersion(String hostName, Host host, String path, String version, Context context, String[] welcomeResources, WebResourceRoot resources, Collection<WrapperMappingInfo> wrappers) {

        MappedHost mappedHost = hostMap.get(hostName);
        if (mappedHost == null) {
            addHost(hostName, new String[0], host);
            mappedHost = hostMap.get(hostName);
            if (mappedHost == null) {
                log.error("No host found: " + hostName);
                return;
            }
        }
        if (mappedHost.isAlias()) {
            log.error("No host found: " + hostName);
            return;
        }
        int slashCount = slashCount(path);
        synchronized (mappedHost) {
            ContextVersion newContextVersion = new ContextVersion(version,
                    path, slashCount, context, resources, welcomeResources);
            if (wrappers != null) {
                addWrappers(newContextVersion, wrappers);
            }

            ContextList contextList = mappedHost.contextList;
            MappedContext mappedContext = exactFind(contextList.contexts, path);
            if (mappedContext == null) {
                mappedContext = new MappedContext(path, newContextVersion);
                ContextList newContextList = contextList.addContext(
                        mappedContext, slashCount);
                if (newContextList != null) {
                    updateContextList(mappedHost, newContextList);
                    contextObjectToContextVersionMap.put(context, newContextVersion);
                }
            } else {
                ContextVersion[] contextVersions = mappedContext.versions;
                ContextVersion[] newContextVersions = new ContextVersion[contextVersions.length + 1];
                if (insertMap(contextVersions, newContextVersions,
                        newContextVersion)) {
                    mappedContext.versions = newContextVersions;
                    contextObjectToContextVersionMap.put(context, newContextVersion);
                } else {
                    // Re-registration after Context.reload()
                    // Replace ContextVersion with the new one
                    int pos = find(contextVersions, version);
                    if (pos >= 0 && contextVersions[pos].name.equals(version)) {
                        contextVersions[pos] = newContextVersion;
                        contextObjectToContextVersionMap.put(context, newContextVersion);
                    }
                }
            }
        }

    }


    /**
     * Remove a context from an existing host.
     *
     * @param ctxt     The actual context
     * @param hostName Virtual host name this context belongs to
     * @param path     Context path
     * @param version  Context version
     */
    public void removeContextVersion(Context ctxt, String hostName, String path, String version) {
        contextObjectToContextVersionMap.remove(ctxt);
        MappedHost host = hostMap.get(hostName);
        if (host == null || host.isAlias()) {
            return;
        }
        synchronized (host) {
            ContextList contextList = host.contextList;
            MappedContext context = exactFind(contextList.contexts, path);
            if (context == null) {
                return;
            }

            ContextVersion[] contextVersions = context.versions;
            ContextVersion[] newContextVersions =
                    new ContextVersion[contextVersions.length - 1];
            if (removeMap(contextVersions, newContextVersions, version)) {
                if (newContextVersions.length == 0) {
                    // Remove the context
                    ContextList newContextList = contextList.removeContext(path);
                    if (newContextList != null) {
                        updateContextList(host, newContextList);
                    }
                } else {
                    context.versions = newContextVersions;
                }
            }
        }
    }


    /**
     * Mark a context as being reloaded. Reversion of this state is performed
     * by calling <code>addContextVersion(...)</code> when context starts up.
     *
     * @param ctxt        The actual context
     * @param hostName    Virtual host name this context belongs to
     * @param contextPath Context path
     * @param version     Context version
     */
    public void pauseContextVersion(Context ctxt, String hostName, String contextPath, String version) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, true);
        if (contextVersion == null || !ctxt.equals(contextVersion.object)) {
            return;
        }
        contextVersion.markPaused();
    }


    private ContextVersion findContextVersion(String hostName, String contextPath, String version, boolean silent) {
        MappedHost host = hostMap.get(hostName);
        if (host == null || host.isAlias()) {
            if (!silent) {
                log.error("No host found: " + hostName);
            }
            return null;
        }
        MappedContext context = exactFind(host.contextList.contexts, contextPath);
        if (context == null) {
            if (!silent) {
                log.error("No context found: " + contextPath);
            }
            return null;
        }
        ContextVersion contextVersion = exactFind(context.versions, version);
        if (contextVersion == null) {
            if (!silent) {
                log.error("No context version found: " + contextPath + " "
                        + version);
            }
            return null;
        }
        return contextVersion;
    }


    public void addWrapper(String hostName, String contextPath, String version, String path, Wrapper wrapper, boolean jspWildCard, boolean resourceOnly) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        addWrapper(contextVersion, path, wrapper, jspWildCard, resourceOnly);
    }

    public void addWrappers(String hostName, String contextPath, String version, Collection<WrapperMappingInfo> wrappers) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        addWrappers(contextVersion, wrappers);
    }

    /**
     * Adds wrappers to the given context.
     *
     * @param contextVersion The context to which to add the wrappers
     * @param wrappers       Information on wrapper mappings
     */
    private void addWrappers(ContextVersion contextVersion, Collection<WrapperMappingInfo> wrappers) {
        for (WrapperMappingInfo wrapper : wrappers) {
            addWrapper(contextVersion, wrapper.getMapping(),
                    wrapper.getWrapper(), wrapper.isJspWildCard(),
                    wrapper.isResourceOnly());
        }
    }

    /**
     * Adds a wrapper to the given context.
     *
     * @param context      The context to which to add the wrapper
     * @param path         Wrapper mapping
     * @param wrapper      The Wrapper object
     * @param jspWildCard  true if the wrapper corresponds to the JspServlet
     *                     and the mapping path contains a wildcard; false otherwise
     * @param resourceOnly true if this wrapper always expects a physical
     *                     resource to be present (such as a JSP)
     */
    protected void addWrapper(ContextVersion context, String path, Wrapper wrapper, boolean jspWildCard, boolean resourceOnly) {

        synchronized (context) {
            if (path.endsWith("/*")) {
                // Wildcard wrapper
                String name = path.substring(0, path.length() - 2);
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.wildcardWrappers;
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.wildcardWrappers = newWrappers;
                    int slashCount = slashCount(newWrapper.name);
                    if (slashCount > context.nesting) {
                        context.nesting = slashCount;
                    }
                }
            } else if (path.startsWith("*.")) {
                // Extension wrapper
                String name = path.substring(2);
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.extensionWrappers;
                MappedWrapper[] newWrappers =
                        new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.extensionWrappers = newWrappers;
                }
            } else if (path.equals("/")) {
                // Default wrapper
                MappedWrapper newWrapper = new MappedWrapper("", wrapper,
                        jspWildCard, resourceOnly);
                context.defaultWrapper = newWrapper;
            } else {
                // Exact wrapper
                final String name;
                if (path.length() == 0) {
                    // Special case for the Context Root mapping which is
                    // treated as an exact match
                    name = "/";
                } else {
                    name = path;
                }
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.exactWrappers;
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.exactWrappers = newWrappers;
                }
            }
        }
    }


    /**
     * Remove a wrapper from an existing context.
     *
     * @param hostName    Virtual host name this wrapper belongs to
     * @param contextPath Context path this wrapper belongs to
     * @param path        Wrapper mapping
     */
    public void removeWrapper(String hostName, String contextPath, String version, String path) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, true);
        if (contextVersion == null || contextVersion.isPaused()) {
            return;
        }
        removeWrapper(contextVersion, path);
    }

    protected void removeWrapper(ContextVersion context, String path) {
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("mapper.removeWrapper", context.name, path));
        }
        synchronized (context) {
            if (path.endsWith("/*")) {
                // Wildcard wrapper
                String name = path.substring(0, path.length() - 2);
                MappedWrapper[] oldWrappers = context.wildcardWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    // Recalculate nesting
                    context.nesting = 0;
                    for (MappedWrapper newWrapper : newWrappers) {
                        int slashCount = slashCount(newWrapper.name);
                        if (slashCount > context.nesting) {
                            context.nesting = slashCount;
                        }
                    }
                    context.wildcardWrappers = newWrappers;
                }
            } else if (path.startsWith("*.")) {
                // Extension wrapper
                String name = path.substring(2);
                MappedWrapper[] oldWrappers = context.extensionWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    context.extensionWrappers = newWrappers;
                }
            } else if (path.equals("/")) {
                // Default wrapper
                context.defaultWrapper = null;
            } else {
                // Exact wrapper
                String name;
                if (path.length() == 0) {
                    // Special case for the Context Root mapping which is
                    // treated as an exact match
                    name = "/";
                } else {
                    name = path;
                }
                MappedWrapper[] oldWrappers = context.exactWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    context.exactWrappers = newWrappers;
                }
            }
        }
    }


    /**
     * Add a welcome file to the given context.
     */
    public void addWelcomeFile(String hostName, String contextPath, String version, String welcomeFile) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        int len = contextVersion.welcomeResources.length + 1;
        String[] newWelcomeResources = new String[len];
        System.arraycopy(contextVersion.welcomeResources, 0, newWelcomeResources, 0, len - 1);
        newWelcomeResources[len - 1] = welcomeFile;
        contextVersion.welcomeResources = newWelcomeResources;
    }

    /**
     * Remove a welcome file from the given context.
     */
    public void removeWelcomeFile(String hostName, String contextPath, String version, String welcomeFile) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null || contextVersion.isPaused()) {
            return;
        }
        int match = -1;
        for (int i = 0; i < contextVersion.welcomeResources.length; i++) {
            if (welcomeFile.equals(contextVersion.welcomeResources[i])) {
                match = i;
                break;
            }
        }
        if (match > -1) {
            int len = contextVersion.welcomeResources.length - 1;
            String[] newWelcomeResources = new String[len];
            System.arraycopy(contextVersion.welcomeResources, 0, newWelcomeResources, 0, match);
            if (match < len) {
                System.arraycopy(contextVersion.welcomeResources, match + 1, newWelcomeResources, match, len - match);
            }
            contextVersion.welcomeResources = newWelcomeResources;
        }
    }

    /**
     * Clear the welcome files for the given context.
     */
    public void clearWelcomeFiles(String hostName, String contextPath, String version) {
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        contextVersion.welcomeResources = new String[0];
    }

    /**
     * Map the specified host name and URI, mutating the given mapping data.
     *
     * @param host        Virtual host name
     * @param uri         URI
     * @param mappingData This structure will contain the result of the mapping
     *                    operation
     * @throws IOException if the buffers are too small to hold the results of
     *                     the mapping.
     */
    public void map(MessageBytes host, MessageBytes uri, String version, MappingData mappingData) throws IOException {

        if (host.isNull()) {
            host.getCharChunk().append(defaultHostName);
        }
        host.toChars();
        uri.toChars();
        internalMap(host.getCharChunk(), uri.getCharChunk(), version, mappingData);
    }

    public void map(Context context, MessageBytes uri, MappingData mappingData) throws IOException {
        ContextVersion contextVersion = contextObjectToContextVersionMap.get(context);
        uri.toChars();
        CharChunk uricc = uri.getCharChunk();
        uricc.setLimit(-1);
        internalMapWrapper(contextVersion, uricc, mappingData);
    }

    private void internalMap(CharChunk host, CharChunk uri, String version, MappingData mappingData) throws IOException {
        if (mappingData.host != null) {
            throw new AssertionError();
        }

        uri.setLimit(-1);

        // Virtual host mapping
        MappedHost mappedHost = hostMap.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(host.toString()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (mappedHost == null) {
            if (defaultHostName == null) {
                return;
            }
            mappedHost = hostMap.get(defaultHostName);
            if (mappedHost == null) {
                return;
            }
        }
        mappingData.host = mappedHost.object;

        // Context mapping
        ContextList contextList = mappedHost.contextList;
        MappedContext[] contexts = contextList.contexts;
        int pos = find(contexts, uri);
        if (pos == -1) {
            return;
        }

        int lastSlash = -1;
        int uriEnd = uri.getEnd();
        int length = -1;
        boolean found = false;
        MappedContext context = null;
        while (pos >= 0) {
            context = contexts[pos];
            if (uri.startsWith(context.name)) {
                length = context.name.length();
                if (uri.getLength() == length) {
                    found = true;
                    break;
                } else if (uri.startsWithIgnoreCase("/", length)) {
                    found = true;
                    break;
                }
            }
            if (lastSlash == -1) {
                lastSlash = nthSlash(uri, contextList.nesting + 1);
            } else {
                lastSlash = lastSlash(uri);
            }
            uri.setEnd(lastSlash);
            pos = find(contexts, uri);
        }
        uri.setEnd(uriEnd);

        if (!found) {
            if (contexts[0].name.equals("")) {
                context = contexts[0];
            } else {
                context = null;
            }
        }
        if (context == null) {
            return;
        }

        mappingData.contextPath.setString(context.name);

        ContextVersion contextVersion = null;
        ContextVersion[] contextVersions = context.versions;
        final int versionCount = contextVersions.length;
        if (versionCount > 1) {
            Context[] contextObjects = new Context[contextVersions.length];
            for (int i = 0; i < contextObjects.length; i++) {
                contextObjects[i] = contextVersions[i].object;
            }
            mappingData.contexts = contextObjects;
            if (version != null) {
                contextVersion = exactFind(contextVersions, version);
            }
        }
        if (contextVersion == null) {
            // Return the latest version
            // The versions array is known to contain at least one element
            contextVersion = contextVersions[versionCount - 1];
        }
        mappingData.context = contextVersion.object;
        mappingData.contextSlashCount = contextVersion.slashCount;

        // Wrapper mapping
        if (!contextVersion.isPaused()) {
            internalMapWrapper(contextVersion, uri, mappingData);
        }

    }


    /**
     * Wrapper mapping.
     * 看代码的意思就是：匹配客户端的url，映射到我们后台服务的资源上面
     *
     * @throws IOException if the buffers are too small to hold the results of
     *                     the mapping.
     */
    private void internalMapWrapper(ContextVersion contextVersion, CharChunk path, MappingData mappingData) throws IOException {
        SystemUtil.logInfo(this, "开始匹配访问的URL，映射到后端的资源上面...");
        int pathOffset = path.getOffset();
        int pathEnd = path.getEnd();
        boolean noServletPath = false;

        int length = contextVersion.path.length();
        if (length == (pathEnd - pathOffset)) {
            noServletPath = true;
        }
        int servletPath = pathOffset + length;
        path.setOffset(servletPath);

        // Rule 1 -- Exact Match
        MappedWrapper[] exactWrappers = contextVersion.exactWrappers;
        internalMapExactWrapper(exactWrappers, path, mappingData);

        // Rule 2 -- Prefix Match
        boolean checkJspWelcomeFiles = false;
        MappedWrapper[] wildcardWrappers = contextVersion.wildcardWrappers;
        if (mappingData.wrapper == null) {
            internalMapWildcardWrapper(wildcardWrappers, contextVersion.nesting, path, mappingData);
            if (mappingData.wrapper != null && mappingData.jspWildCard) {
                char[] buf = path.getBuffer();
                if (buf[pathEnd - 1] == '/') {
                    /*
                     * Path ending in '/' was mapped to JSP servlet based on
                     * wildcard match (e.g., as specified in url-pattern of a
                     * jsp-property-group.
                     * Force the context's welcome files, which are interpreted
                     * as JSP files (since they match the url-pattern), to be
                     * considered. See Bugzilla 27664.
                     */
                    mappingData.wrapper = null;
                    checkJspWelcomeFiles = true;
                } else {
                    // See Bugzilla 27704
                    mappingData.wrapperPath.setChars(buf, path.getStart(), path.getLength());
                    mappingData.pathInfo.recycle();
                }
            }
        }

        if (mappingData.wrapper == null && noServletPath &&
                mappingData.context.getMapperContextRootRedirectEnabled()) {
            // The path is empty, redirect to "/"
            path.append('/');
            pathEnd = path.getEnd();
            mappingData.redirectPath.setChars(path.getBuffer(), pathOffset, pathEnd - pathOffset);
            path.setEnd(pathEnd - 1);
            return;
        }

        // Rule 3 -- Extension Match
        MappedWrapper[] extensionWrappers = contextVersion.extensionWrappers;
        if (mappingData.wrapper == null && !checkJspWelcomeFiles) {
            internalMapExtensionWrapper(extensionWrappers, path, mappingData, true);
        }

        // Rule 4 -- Welcome resources processing for servlets
        if (mappingData.wrapper == null) {
            boolean checkWelcomeFiles = checkJspWelcomeFiles;
            if (!checkWelcomeFiles) {
                char[] buf = path.getBuffer();
                checkWelcomeFiles = (buf[pathEnd - 1] == '/');
            }
            if (checkWelcomeFiles) {
                for (int i = 0; (i < contextVersion.welcomeResources.length) && (mappingData.wrapper == null); i++) {
                    path.setOffset(pathOffset);
                    path.setEnd(pathEnd);
                    path.append(contextVersion.welcomeResources[i], 0, contextVersion.welcomeResources[i].length());
                    path.setOffset(servletPath);

                    // Rule 4a -- Welcome resources processing for exact macth
                    internalMapExactWrapper(exactWrappers, path, mappingData);

                    // Rule 4b -- Welcome resources processing for prefix match
                    if (mappingData.wrapper == null) {
                        internalMapWildcardWrapper(wildcardWrappers, contextVersion.nesting, path, mappingData);
                    }

                    // Rule 4c -- Welcome resources processing
                    //            for physical folder
                    if (mappingData.wrapper == null && contextVersion.resources != null) {
                        String pathStr = path.toString();
                        WebResource file = contextVersion.resources.getResource(pathStr);
                        if (file != null && file.isFile()) {
                            internalMapExtensionWrapper(extensionWrappers, path, mappingData, true);
                            if (mappingData.wrapper == null && contextVersion.defaultWrapper != null) {
                                mappingData.wrapper = contextVersion.defaultWrapper.object;
                                mappingData.requestPath.setChars(path.getBuffer(), path.getStart(), path.getLength());
                                mappingData.wrapperPath.setChars(path.getBuffer(), path.getStart(), path.getLength());
                                mappingData.requestPath.setString(pathStr);
                                mappingData.wrapperPath.setString(pathStr);
                            }
                        }
                    }
                }

                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }

        }

        /* welcome file processing - take 2
         * Now that we have looked for welcome files with a physical
         * backing, now look for an extension mapping listed
         * but may not have a physical backing to it. This is for
         * the case of index.jsf, index.do, etc.
         * A watered down version of rule 4
         */
        if (mappingData.wrapper == null) {
            boolean checkWelcomeFiles = checkJspWelcomeFiles;
            if (!checkWelcomeFiles) {
                char[] buf = path.getBuffer();
                checkWelcomeFiles = (buf[pathEnd - 1] == '/');
            }
            if (checkWelcomeFiles) {
                for (int i = 0; (i < contextVersion.welcomeResources.length) && (mappingData.wrapper == null); i++) {
                    path.setOffset(pathOffset);
                    path.setEnd(pathEnd);
                    path.append(contextVersion.welcomeResources[i], 0, contextVersion.welcomeResources[i].length());
                    path.setOffset(servletPath);
                    internalMapExtensionWrapper(extensionWrappers, path, mappingData, false);
                }

                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }
        }


        // Rule 7 -- Default servlet
        if (mappingData.wrapper == null && !checkJspWelcomeFiles) {
            if (contextVersion.defaultWrapper != null) {
                mappingData.wrapper = contextVersion.defaultWrapper.object;
                mappingData.requestPath.setChars(path.getBuffer(), path.getStart(), path.getLength());
                mappingData.wrapperPath.setChars(path.getBuffer(), path.getStart(), path.getLength());
            }
            // Redirection to a folder
            char[] buf = path.getBuffer();
            if (contextVersion.resources != null && buf[pathEnd - 1] != '/') {
                String pathStr = path.toString();
                WebResource file;
                // Handle context root
                if (pathStr.length() == 0) {
                    file = contextVersion.resources.getResource("/");
                } else {
                    file = contextVersion.resources.getResource(pathStr);
                }
                if (file != null && file.isDirectory() && mappingData.context.getMapperDirectoryRedirectEnabled()) {
                    // Note: this mutates the path: do not do any processing
                    // after this (since we set the redirectPath, there
                    // shouldn't be any)
                    path.setOffset(pathOffset);
                    path.append('/');
                    mappingData.redirectPath.setChars(path.getBuffer(), path.getStart(), path.getLength());
                } else {
                    mappingData.requestPath.setString(pathStr);
                    mappingData.wrapperPath.setString(pathStr);
                }
            }
        }

        path.setOffset(pathOffset);
        path.setEnd(pathEnd);
    }


    /**
     * Exact mapping.
     */
    private final void internalMapExactWrapper(MappedWrapper[] wrappers, CharChunk path, MappingData mappingData) {
        MappedWrapper wrapper = exactFind(wrappers, path);
        if (wrapper != null) {
            mappingData.requestPath.setString(wrapper.name);
            mappingData.wrapper = wrapper.object;
            if (path.equals("/")) {
                // Special handling for Context Root mapped servlet
                mappingData.pathInfo.setString("/");
                mappingData.wrapperPath.setString("");
                // This seems wrong but it is what the spec says...
                mappingData.contextPath.setString("");
            } else {
                mappingData.wrapperPath.setString(wrapper.name);
            }
        }
    }


    /**
     * Wildcard mapping.
     */
    private final void internalMapWildcardWrapper(MappedWrapper[] wrappers, int nesting, CharChunk path, MappingData mappingData) {

        int pathEnd = path.getEnd();

        int lastSlash = -1;
        int length = -1;
        int pos = find(wrappers, path);
        if (pos != -1) {
            boolean found = false;
            while (pos >= 0) {
                if (path.startsWith(wrappers[pos].name)) {
                    length = wrappers[pos].name.length();
                    if (path.getLength() == length) {
                        found = true;
                        break;
                    } else if (path.startsWithIgnoreCase("/", length)) {
                        found = true;
                        break;
                    }
                }
                if (lastSlash == -1) {
                    lastSlash = nthSlash(path, nesting + 1);
                } else {
                    lastSlash = lastSlash(path);
                }
                path.setEnd(lastSlash);
                pos = find(wrappers, path);
            }
            path.setEnd(pathEnd);
            if (found) {
                mappingData.wrapperPath.setString(wrappers[pos].name);
                if (path.getLength() > length) {
                    mappingData.pathInfo.setChars(path.getBuffer(), path.getOffset() + length, path.getLength() - length);
                }
                mappingData.requestPath.setChars(path.getBuffer(), path.getOffset(), path.getLength());
                mappingData.wrapper = wrappers[pos].object;
                mappingData.jspWildCard = wrappers[pos].jspWildCard;
            }
        }
    }


    /**
     * Extension mappings.
     *
     * @param wrappers         Set of wrappers to check for matches
     * @param path             Path to map
     * @param mappingData      Mapping data for result
     * @param resourceExpected Is this mapping expecting to find a resource
     */
    private final void internalMapExtensionWrapper(MappedWrapper[] wrappers, CharChunk path, MappingData mappingData, boolean resourceExpected) {
        char[] buf = path.getBuffer();
        int pathEnd = path.getEnd();
        int servletPath = path.getOffset();
        int slash = -1;
        for (int i = pathEnd - 1; i >= servletPath; i--) {
            if (buf[i] == '/') {
                slash = i;
                break;
            }
        }
        if (slash >= 0) {
            int period = -1;
            for (int i = pathEnd - 1; i > slash; i--) {
                if (buf[i] == '.') {
                    period = i;
                    break;
                }
            }
            if (period >= 0) {
                path.setOffset(period + 1);
                path.setEnd(pathEnd);
                MappedWrapper wrapper = exactFind(wrappers, path);
                if (wrapper != null && (resourceExpected || !wrapper.resourceOnly)) {
                    mappingData.wrapperPath.setChars(buf, servletPath, pathEnd - servletPath);
                    mappingData.requestPath.setChars(buf, servletPath, pathEnd - servletPath);
                    mappingData.wrapper = wrapper.object;
                }
                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }
        }
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int find(MapElement<T>[] map, CharChunk name) {
        return find(map, name, name.getStart(), name.getEnd());
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int find(MapElement<T>[] map, CharChunk name, int start, int end) {

        int a = 0;
        int b = map.length - 1;

        // Special cases: -1 and 0
        if (b == -1) {
            return -1;
        }

        if (compare(name, start, end, map[0].name) < 0) {
            return -1;
        }
        if (b == 0) {
            return 0;
        }

        int i = 0;
        while (true) {
            i = (b + a) / 2;
            int result = compare(name, start, end, map[i].name);
            if (result == 1) {
                a = i;
            } else if (result == 0) {
                return i;
            } else {
                b = i;
            }
            if ((b - a) == 1) {
                int result2 = compare(name, start, end, map[b].name);
                if (result2 < 0) {
                    return a;
                } else {
                    return b;
                }
            }
        }

    }

    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int findIgnoreCase(MapElement<T>[] map, CharChunk name) {
        return findIgnoreCase(map, name, name.getStart(), name.getEnd());
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int findIgnoreCase(MapElement<T>[] map, CharChunk name, int start, int end) {

        int a = 0;
        int b = map.length - 1;

        // Special cases: -1 and 0
        if (b == -1) {
            return -1;
        }
        if (compareIgnoreCase(name, start, end, map[0].name) < 0) {
            return -1;
        }
        if (b == 0) {
            return 0;
        }

        int i = 0;
        while (true) {
            i = (b + a) / 2;
            int result = compareIgnoreCase(name, start, end, map[i].name);
            if (result == 1) {
                a = i;
            } else if (result == 0) {
                return i;
            } else {
                b = i;
            }
            if ((b - a) == 1) {
                int result2 = compareIgnoreCase(name, start, end, map[b].name);
                if (result2 < 0) {
                    return a;
                } else {
                    return b;
                }
            }
        }

    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     *
     * @see #exactFind(MapElement[], String)
     * 这个应该是个二分查找的算法，不明白，直接用HashMap,还会有这些个问题。。。，排序的目的可能就是为了去重，我猜测
     */
    private static <T> int find(MapElement<T>[] map, String name) {

        int start = 0;
        int end = map.length - 1;

        // Special cases: -1 and 0
        if (end == -1) {
            return -1;
        }

        if (name.compareTo(map[0].name) < 0) {
            return -1;
        }
        if (end == 0) {
            return 0;
        }

        int i = 0;
        while (true) {
            i = (end + start) / 2;
            int result = name.compareTo(map[i].name);
            if (result > 0) {
                start = i;
            } else if (result == 0) {
                return i;
            } else {
                end = i;
            }
            if ((end - start) == 1) {
                int result2 = name.compareTo(map[end].name);
                if (result2 < 0) {
                    return start;
                } else {
                    return end;
                }
            }
        }

    }


    /**
     * Find a map element given its name in a sorted array of map elements. This
     * will return the element that you were searching for. Otherwise it will
     * return <code>null</code>.
     *
     * @see #find(MapElement[], String)
     */
    private static final <T, E extends MapElement<T>> E exactFind(E[] map, String name) {
        int pos = find(map, name);
        if (pos >= 0) {
            E result = map[pos];
            if (name.equals(result.name)) {
                return result;
            }
        }
        return null;
    }

    /**
     * Find a map element given its name in a sorted array of map elements. This
     * will return the element that you were searching for. Otherwise it will
     * return <code>null</code>.
     */
    private static final <T, E extends MapElement<T>> E exactFind(E[] map, CharChunk name) {
        int pos = find(map, name);
        if (pos >= 0) {
            E result = map[pos];
            if (name.equals(result.name)) {
                return result;
            }
        }
        return null;
    }

    private static final int compare(CharChunk name, int start, int end, String compareTo) {
        int result = 0;
        char[] c = name.getBuffer();
        int len = compareTo.length();
        if ((end - start) < len) {
            len = end - start;
        }
        for (int i = 0; (i < len) && (result == 0); i++) {
            if (c[i + start] > compareTo.charAt(i)) {
                result = 1;
            } else if (c[i + start] < compareTo.charAt(i)) {
                result = -1;
            }
        }
        if (result == 0) {
            if (compareTo.length() > (end - start)) {
                result = -1;
            } else if (compareTo.length() < (end - start)) {
                result = 1;
            }
        }
        return result;
    }

    private static final int compareIgnoreCase(CharChunk name, int start, int end, String compareTo) {
        int result = 0;
        char[] c = name.getBuffer();
        int len = compareTo.length();
        if ((end - start) < len) {
            len = end - start;
        }
        for (int i = 0; (i < len) && (result == 0); i++) {
            if (Ascii.toLower(c[i + start]) > Ascii.toLower(compareTo.charAt(i))) {
                result = 1;
            } else if (Ascii.toLower(c[i + start]) < Ascii.toLower(compareTo.charAt(i))) {
                result = -1;
            }
        }
        if (result == 0) {
            if (compareTo.length() > (end - start)) {
                result = -1;
            } else if (compareTo.length() < (end - start)) {
                result = 1;
            }
        }
        return result;
    }


    /**
     * Find the position of the last slash in the given char chunk.
     */
    private static final int lastSlash(CharChunk name) {

        char[] c = name.getBuffer();
        int end = name.getEnd();
        int start = name.getStart();
        int pos = end;

        while (pos > start) {
            if (c[--pos] == '/') {
                break;
            }
        }
        return pos;
    }


    /**
     * Find the position of the nth slash, in the given char chunk.
     */
    private static int nthSlash(CharChunk name, int n) {

        char[] c = name.getBuffer();
        int end = name.getEnd();
        int pos = name.getStart();
        int count = 0;

        while (pos < end) {
            if ((c[pos++] == '/') && ((++count) == n)) {
                pos--;
                break;
            }
        }

        return (pos);

    }


    /**
     * Return the slash count in a given string.
     */
    private static int slashCount(String name) {
        int pos = -1;
        int count = 0;
        while ((pos = name.indexOf('/', pos + 1)) != -1) {
            count++;
        }
        return count;
    }


    /**
     * Insert into the right place in a sorted MapElement array, and prevent
     * duplicates.
     * 我感觉作者使用了一个很垃圾的自己写代码的cpp的轮子形式
     */
    private static <T> boolean insertMap(MapElement<T>[] oldMap, MapElement<T>[] newMap, MapElement<T> newElement) {
        int pos = find(oldMap, newElement.name);
        if ((pos != -1) && (newElement.name.equals(oldMap[pos].name))) {
            return false;
        }
        System.arraycopy(oldMap, 0, newMap, 0, pos + 1);
        newMap[pos + 1] = newElement;
        System.arraycopy(oldMap, pos + 1, newMap, pos + 2, oldMap.length - pos - 1);
        return true;
    }


    /**
     * Insert into the right place in a sorted MapElement array.
     */
    private static final <T> boolean removeMap(MapElement<T>[] oldMap, MapElement<T>[] newMap, String name) {
        int pos = find(oldMap, name);
        if ((pos != -1) && (name.equals(oldMap[pos].name))) {
            System.arraycopy(oldMap, 0, newMap, 0, pos);
            System.arraycopy(oldMap, pos + 1, newMap, pos, oldMap.length - pos - 1);
            return true;
        }
        return false;
    }

    protected abstract static class MapElement<T> {
        public final String name;
        public final T object;

        MapElement(String name, T object) {
            this.name = name;
            this.object = object;
        }

        public String getName() {
            return name;
        }
    }

    protected static final class MappedHost extends MapElement<Host> {
        private volatile ContextList contextList;
        private final MappedHost realHost;
        private final List<MappedHost> aliases;

        public MappedHost(String name, Host host) {
            super(name, host);
            realHost = this;
            contextList = new ContextList();
            aliases = new CopyOnWriteArrayList<>();
        }

        public MappedHost(String alias, MappedHost realHost) {
            super(alias, realHost.object);
            this.realHost = realHost;
            this.contextList = realHost.contextList;
            this.aliases = null;
        }

        public boolean isAlias() {
            return realHost != this;
        }

        public MappedHost getRealHost() {
            return realHost;
        }

        public String getRealHostName() {
            return realHost.name;
        }

        public Collection<MappedHost> getAliases() {
            return aliases;
        }

        public void addAlias(MappedHost alias) {
            aliases.add(alias);
        }

        public void addAliases(Collection<? extends MappedHost> c) {
            aliases.addAll(c);
        }

        public void removeAlias(MappedHost alias) {
            aliases.remove(alias);
        }
    }


    protected static final class ContextList {

        public final MappedContext[] contexts;
        public final int nesting;
        private final Map<String,MappedContext> contextMap = new HashMap<>();

        public ContextList() {
            this(new MappedContext[0], 0);
        }

        private ContextList(MappedContext[] contexts, int nesting) {
            this.contexts = contexts;
            this.nesting = nesting;
            Arrays.stream(contexts).forEach(mappedContext -> contextMap.put(mappedContext.getName(),mappedContext));
        }

        public ContextList addContext(MappedContext mappedContext, int slashCount) {
            MappedContext[] newContexts = new MappedContext[contexts.length + 1];
            if (insertMap(contexts, newContexts, mappedContext)) {
                return new ContextList(newContexts, Math.max(nesting, slashCount));
            }
            return null;
        }

        public ContextList removeContext(String path) {
            MappedContext[] newContexts = new MappedContext[contexts.length - 1];
            if (removeMap(contexts, newContexts, path)) {
                int newNesting = 0;
                for (MappedContext context : newContexts) {
                    newNesting = Math.max(newNesting, slashCount(context.name));
                }
                return new ContextList(newContexts, newNesting);
            }
            return null;
        }

        public MappedContext[] getContexts() {
            return contexts;
        }

        public int getNesting() {
            return nesting;
        }

        public Map<String, MappedContext> getContextMap() {
            return contextMap;
        }
    }

    protected static final class MappedContext extends MapElement<Void> {
        public volatile ContextVersion[] versions;

        public MappedContext(String name, ContextVersion firstVersion) {
            super(name, null);
            this.versions = new ContextVersion[]{firstVersion};
        }
    }

    protected static final class ContextVersion extends MapElement<Context> {
        public final String path;
        public final int slashCount;
        public final WebResourceRoot resources;
        public String[] welcomeResources;
        public MappedWrapper defaultWrapper = null;
        public MappedWrapper[] exactWrappers = new MappedWrapper[0];
        public MappedWrapper[] wildcardWrappers = new MappedWrapper[0];
        public MappedWrapper[] extensionWrappers = new MappedWrapper[0];
        public int nesting = 0;
        private volatile boolean paused;

        public ContextVersion(String version, String path, int slashCount,
                              Context context, WebResourceRoot resources,
                              String[] welcomeResources) {
            super(version, context);
            this.path = path;
            this.slashCount = slashCount;
            this.resources = resources;
            this.welcomeResources = welcomeResources;
        }

        public boolean isPaused() {
            return paused;
        }

        public void markPaused() {
            paused = true;
        }
    }

    protected static class MappedWrapper extends MapElement<Wrapper> {

        public final boolean jspWildCard;
        public final boolean resourceOnly;

        public MappedWrapper(String name, Wrapper wrapper, boolean jspWildCard,
                             boolean resourceOnly) {
            super(name, wrapper);
            this.jspWildCard = jspWildCard;
            this.resourceOnly = resourceOnly;
        }
    }
}
