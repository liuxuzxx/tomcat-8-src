package org.apache.tomcat.util.net;

import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.net.AbstractEndpoint.Acceptor.AcceptorState;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.LimitLatch;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.lx.tomcat.util.SystemUtil;

/**
 * 基本都是get-set方法，设置属性，其他看不出来有啥特别的地方
 */
public abstract class AbstractEndpoint<S> {

    protected static final String DEFAULT_CIPHERS = "HIGH:!aNULL:!eNULL:!EXPORT:!DES:!RC4:!MD5";
    protected static final StringManager sm = StringManager.getManager("org.apache.tomcat.util.net.res");
    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;
    protected volatile boolean running = false;
    protected volatile boolean paused = false;
    protected volatile boolean internalExecutor = false;
    private volatile LimitLatch connectionLimitLatch = null;
    protected SocketProperties socketProperties = new SocketProperties();
    protected Acceptor[] acceptors;
    private long executorTerminationTimeoutMillis = 5000;
    protected int acceptorThreadCount = 0;
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;
    private int maxConnections = 2;
    private Executor executor = null;
    private int port;
    private InetAddress address;
    private int backlog = 100;
    private boolean bindOnInit = true;
    private BindState bindState = BindState.UNBOUND;
    private Integer keepAliveTimeout = null;
    private int maxKeepAliveRequests = 100;
    private int maxHeaderCount = 100;
    private String name = "TP";
    private boolean SSLEnabled = false;
    private int minSpareThreads = 10;
    private int maxThreads = 200;
    private boolean daemon = true;
    protected int threadPriority = Thread.NORM_PRIORITY;
    private String algorithm = KeyManagerFactory.getDefaultAlgorithm();
    private String[] sslEnabledProtocolsarr = new String[0];
    protected final Set<SocketWrapper<S>> waitingRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private AsyncTimeout asyncTimeout = null;
    private String sessionTimeout = "86400";
    private String clientAuth = "false";
    private String allowUnsafeLegacyRenegotiation = null;
    private String sessionCacheSize = null;
    private String keystoreFile = System.getProperty("user.home") + "/.keystore";
    private String trustMaxCertLength = null;
    private String crlFile = null;
    private String trustManagerClassName = null;
    private String keystorePass = null;
    private String keystoreType = "JKS";
    private String keystoreProvider = null;
    private String sslProtocol = Constants.SSL_PROTO_TLS;
    private String ciphers = DEFAULT_CIPHERS;
    private String useServerCipherSuitesOrder = "";
    private String keyAlias = null;
    private String keyPass = null;
    private String truststoreFile = System.getProperty("javax.net.ssl.trustStore");
    private String truststorePass = System.getProperty("javax.net.ssl.trustStorePassword");
    private String truststoreType = System.getProperty("javax.net.ssl.trustStoreType");
    private String truststoreProvider = null;
    private String truststoreAlgorithm = null;

    public interface Handler {
        enum SocketState {
            OPEN, CLOSED, LONG, ASYNC_END, SENDFILE, UPGRADING, UPGRADED
        }

        Object getGlobal();

        void recycle();
    }

    protected enum BindState {
        UNBOUND, BOUND_ON_INIT, BOUND_ON_START
    }

    public SocketProperties getSocketProperties() {
        return socketProperties;
    }

    public long getExecutorTerminationTimeoutMillis() {
        return executorTerminationTimeoutMillis;
    }

    public void setExecutorTerminationTimeoutMillis(long executorTerminationTimeoutMillis) {
        this.executorTerminationTimeoutMillis = executorTerminationTimeoutMillis;
    }

    public void setAcceptorThreadCount(int acceptorThreadCount) {
        this.acceptorThreadCount = acceptorThreadCount;
    }

    public int getAcceptorThreadCount() {
        return acceptorThreadCount;
    }

    public void setAcceptorThreadPriority(int acceptorThreadPriority) {
        this.acceptorThreadPriority = acceptorThreadPriority;
    }

    public int getAcceptorThreadPriority() {
        return acceptorThreadPriority;
    }

    public void setMaxConnections(int maxCon) {
        this.maxConnections = maxCon;
        LimitLatch latch = this.connectionLimitLatch;
        if (latch != null) {
            if (maxCon == -1) {
                releaseConnectionLatch();
            } else {
                latch.setLimit(maxCon);
            }
        } else if (maxCon > 0) {
            initializeConnectionLatch();
        }
    }

    public int getMaxConnections() {
        return this.maxConnections;
    }

    public long getConnectionCount() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            return latch.getCount();
        }
        return -1;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
        this.internalExecutor = (executor == null);
    }

    public Executor getExecutor() {
        return executor;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public abstract int getLocalPort();

    public InetAddress getAddress() {
        return address;
    }

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    public void setBacklog(int backlog) {
        if (backlog > 0)
            this.backlog = backlog;
    }

    public int getBacklog() {
        return backlog;
    }

    public boolean getBindOnInit() {
        return bindOnInit;
    }

    public void setBindOnInit(boolean b) {
        this.bindOnInit = b;
    }

    public int getKeepAliveTimeout() {
        if (keepAliveTimeout == null) {
            return getSoTimeout();
        } else {
            return keepAliveTimeout;
        }
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = keepAliveTimeout;
    }

    public boolean getTcpNoDelay() {
        return socketProperties.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        socketProperties.setTcpNoDelay(tcpNoDelay);
    }

    public int getSoLinger() {
        return socketProperties.getSoLingerTime();
    }

    public void setSoLinger(int soLinger) {
        socketProperties.setSoLingerTime(soLinger);
        socketProperties.setSoLingerOn(soLinger >= 0);
    }

    public int getSoTimeout() {
        return socketProperties.getSoTimeout();
    }

    public void setSoTimeout(int soTimeout) {
        socketProperties.setSoTimeout(soTimeout);
    }

    public boolean isSSLEnabled() {
        return SSLEnabled;
    }

    public void setSSLEnabled(boolean SSLEnabled) {
        this.SSLEnabled = SSLEnabled;
    }

    public int getMinSpareThreads() {
        return Math.min(minSpareThreads, getMaxThreads());
    }

    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        Executor executor = this.executor;
        if (running && executor != null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                ((java.util.concurrent.ThreadPoolExecutor) executor).setCorePoolSize(minSpareThreads);
            } else if (executor instanceof ResizableExecutor) {
                ((ResizableExecutor) executor).resizePool(minSpareThreads, maxThreads);
            }
        }
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        Executor executor = this.executor;
        if (running && executor != null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                ((java.util.concurrent.ThreadPoolExecutor) executor).setMaximumPoolSize(maxThreads);
            } else if (executor instanceof ResizableExecutor) {
                ((ResizableExecutor) executor).resizePool(minSpareThreads, maxThreads);
            }
        }
    }

    public int getMaxThreads() {
        return getMaxThreadsExecutor(running);
    }

    protected int getMaxThreadsExecutor(boolean useExecutor) {
        Executor executor = this.executor;
        if (useExecutor && executor != null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                return ((java.util.concurrent.ThreadPoolExecutor) executor).getMaximumPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getMaxThreads();
            } else {
                return -1;
            }
        } else {
            return maxThreads;
        }
    }

    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }

    public void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setDaemon(boolean b) {
        daemon = b;
    }

    public boolean getDaemon() {
        return daemon;
    }

    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public int getThreadPriority() {
        return threadPriority;
    }

    protected abstract boolean getDeferAccept();

    protected HashMap<String, Object> attributes = new HashMap<>();

    public void setAttribute(String name, Object value) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.setAttribute", name, value));
        }
        attributes.put(name, value);
    }

    public Object getAttribute(String key) {
        Object value = attributes.get(key);
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.getAttribute", key, value));
        }
        return value;
    }

    public boolean setProperty(String name, String value) {
        setAttribute(name, value);
        final String socketName = "socket.";
        try {
            if (name.startsWith(socketName)) {
                return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
            } else {
                return IntrospectionUtils.setProperty(this, name, value, false);
            }
        } catch (Exception x) {
            getLog().error("Unable to set attribute \"" + name + "\" to \"" + value + "\"", x);
            return false;
        }
    }

    public String getProperty(String name) {
        return (String) getAttribute(name);
    }

    public int getCurrentThreadCount() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getPoolSize();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    public int getCurrentThreadsBusy() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getActiveCount();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getActiveCount();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public void createExecutor() {
        internalExecutor = true;
        TaskQueue taskqueue = new TaskQueue();
        TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), 60, TimeUnit.SECONDS, taskqueue, tf);
        taskqueue.setParent((ThreadPoolExecutor) executor);
    }

    public void shutdownExecutor() {
        Executor executor = this.executor;
        if (executor != null && internalExecutor) {
            this.executor = null;
            if (executor instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                long timeout = getExecutorTerminationTimeoutMillis();
                if (timeout > 0) {
                    try {
                        tpe.awaitTermination(timeout, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                    }
                    if (tpe.isTerminating()) {
                        getLog().warn(sm.getString("endpoint.warn.executorShutdown", getName()));
                    }
                }
                TaskQueue queue = (TaskQueue) tpe.getQueue();
                queue.setParent(null);
            }
        }
    }

    protected void unlockAccept() {
        boolean unlockRequired = false;
        for (Acceptor acceptor : acceptors) {
            if (acceptor.getState() == AcceptorState.RUNNING) {
                unlockRequired = true;
                break;
            }
        }
        if (!unlockRequired) {
            return;
        }

        InetSocketAddress saddr = null;
        try {
            if (address == null) {
                saddr = new InetSocketAddress("localhost", getLocalPort());
            } else {
                saddr = new InetSocketAddress(address, getLocalPort());
            }
            try (java.net.Socket s = new java.net.Socket()) {
                int stmo = 2 * 1000;
                int utmo = 2 * 1000;
                if (getSocketProperties().getSoTimeout() > stmo)
                    stmo = getSocketProperties().getSoTimeout();
                if (getSocketProperties().getUnlockTimeout() > utmo)
                    utmo = getSocketProperties().getUnlockTimeout();
                s.setSoTimeout(stmo);
                s.setSoLinger(getSocketProperties().getSoLingerOn(), getSocketProperties().getSoLingerTime());
                if (getLog().isDebugEnabled()) {
                    getLog().debug("About to unlock socket for:" + saddr);
                }
                s.connect(saddr, utmo);
                if (getDeferAccept()) {
                    OutputStreamWriter sw;
                    sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
                    sw.write("OPTIONS * HTTP/1.0\r\n" + "User-Agent: Tomcat wakeup connection\r\n\r\n");
                    sw.flush();
                }
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Socket unlock completed for:" + saddr);
                }
                long waitLeft = 1000;
                for (Acceptor acceptor : acceptors) {
                    while (waitLeft > 0 && acceptor.getState() == AcceptorState.RUNNING) {
                        Thread.sleep(50);
                        waitLeft -= 50;
                    }
                }
            }
        } catch (Exception e) {
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("endpoint.debug.unlock", "" + getPort()), e);
            }
        }
    }

    public abstract void processSocket(SocketWrapper<S> socketWrapper, SocketStatus socketStatus, boolean dispatch);

    public void executeNonBlockingDispatches(SocketWrapper<S> socketWrapper) {
        synchronized (socketWrapper) {
            Iterator<DispatchType> dispatches = socketWrapper.getIteratorAndClearDispatches();

            while (dispatches != null && dispatches.hasNext()) {
                DispatchType dispatchType = dispatches.next();
                processSocket(socketWrapper, dispatchType.getSocketStatus(), false);
            }
        }
    }

    public abstract void bind() throws Exception;

    public abstract void unbind() throws Exception;

    public abstract void startInternal() throws Exception;

    public abstract void stopInternal() throws Exception;

    public final void init() throws Exception {
        testServerCipherSuitesOrderSupport();
        if (bindOnInit) {
            bind();
            bindState = BindState.BOUND_ON_INIT;
        }
    }

    private void testServerCipherSuitesOrderSupport() {
        if (!"".equals(getUseServerCipherSuitesOrder().trim())) {
            try {
                SSLParameters.class.getMethod("setUseCipherSuitesOrder", Boolean.TYPE);
            } catch (NoSuchMethodException ex) {
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"), ex);
            }
        }
    }

    public final void start() throws Exception {
        SystemUtil.logInfo(this, "启动Endpoint...");
        if (bindState == BindState.UNBOUND) {
            bind();
            bindState = BindState.BOUND_ON_START;
        }
        startInternal();
    }

    protected final void startAcceptorThreads() {
        acceptors = new Acceptor[getAcceptorThreadCount()];
        for (int i = 0; i < acceptors.length; i++) {
            acceptors[i] = createAcceptor();
            String threadName = getName() + "-Acceptor-" + i;
            acceptors[i].setThreadName(threadName);
            Thread t = new Thread(acceptors[i], threadName);
            t.setPriority(getAcceptorThreadPriority());
            t.setDaemon(getDaemon());
            t.start();
        }
    }

    protected abstract Acceptor createAcceptor();

    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }

    public void resume() {
        if (running) {
            paused = false;
        }
    }

    public final void stop() throws Exception {
        stopInternal();
        if (bindState == BindState.BOUND_ON_START) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }

    public final void destroy() throws Exception {
        if (bindState == BindState.BOUND_ON_INIT) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }

    protected abstract Log getLog();

    public abstract boolean getUseSendfile();

    public abstract boolean getUseComet();

    public abstract boolean getUseCometTimeout();

    public abstract boolean getUsePolling();

    protected LimitLatch initializeConnectionLatch() {
        if (maxConnections == -1)
            return null;
        if (connectionLimitLatch == null) {
            connectionLimitLatch = new LimitLatch(getMaxConnections());
        }
        return connectionLimitLatch;
    }

    protected void releaseConnectionLatch() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null)
            latch.releaseAll();
        connectionLimitLatch = null;
    }

    protected void countUpOrAwaitConnection() throws InterruptedException {
        SystemUtil.logInfo(this, "查看maxConnections的连接数：", String.valueOf(maxConnections));
        if (maxConnections == -1)
            return;
        LimitLatch latch = connectionLimitLatch;
        if (latch != null)
            latch.countUpOrAwait();
    }

    protected long countDownConnection() {
        if (maxConnections == -1 || connectionLimitLatch == null) {
            return -1;
        }
        return connectionLimitLatch.countDown();

    }

    protected int handleExceptionWithDelay(int currentErrorDelay) {
        if (currentErrorDelay > 0) {
            try {
                Thread.sleep(currentErrorDelay);
            } catch (InterruptedException e) {
            }
        }

        if (currentErrorDelay == 0) {
            return INITIAL_ERROR_DELAY;
        } else if (currentErrorDelay < MAX_ERROR_DELAY) {
            return currentErrorDelay * 2;
        } else {
            return MAX_ERROR_DELAY;
        }

    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String s) {
        this.algorithm = s;
    }

    public String getClientAuth() {
        return clientAuth;
    }

    public void setClientAuth(String s) {
        this.clientAuth = s;
    }

    public String getKeystoreFile() {
        return keystoreFile;
    }

    public void setKeystoreFile(String s) {
        keystoreFile = s;
    }

    public String getKeystorePass() {
        return keystorePass;
    }

    public void setKeystorePass(String s) {
        this.keystorePass = s;
    }

    public String getKeystoreType() {
        return keystoreType;
    }

    public void setKeystoreType(String s) {
        this.keystoreType = s;
    }

    public String getKeystoreProvider() {
        return keystoreProvider;
    }

    public void setKeystoreProvider(String s) {
        this.keystoreProvider = s;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String s) {
        sslProtocol = s;
    }

    public String getCiphers() {
        return ciphers;
    }

    public void setCiphers(String s) {
        ciphers = s;
    }

    public abstract String[] getCiphersUsed();

    public String getUseServerCipherSuitesOrder() {
        return useServerCipherSuitesOrder;
    }

    public void setUseServerCipherSuitesOrder(String s) {
        this.useServerCipherSuitesOrder = s;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String s) {
        keyAlias = s;
    }

    public String getKeyPass() {
        return keyPass;
    }

    public void setKeyPass(String s) {
        this.keyPass = s;
    }

    public String getTruststoreFile() {
        return truststoreFile;
    }

    public void setTruststoreFile(String s) {
        truststoreFile = s;
    }

    public String getTruststorePass() {
        return truststorePass;
    }

    public void setTruststorePass(String truststorePass) {
        this.truststorePass = truststorePass;
    }

    public String getTruststoreType() {
        return truststoreType;
    }

    public void setTruststoreType(String truststoreType) {
        this.truststoreType = truststoreType;
    }

    public String getTruststoreProvider() {
        return truststoreProvider;
    }

    public void setTruststoreProvider(String truststoreProvider) {
        this.truststoreProvider = truststoreProvider;
    }

    public String getTruststoreAlgorithm() {
        return truststoreAlgorithm;
    }

    public void setTruststoreAlgorithm(String truststoreAlgorithm) {
        this.truststoreAlgorithm = truststoreAlgorithm;
    }

    public String getTrustManagerClassName() {
        return trustManagerClassName;
    }

    public void setTrustManagerClassName(String trustManagerClassName) {
        this.trustManagerClassName = trustManagerClassName;
    }

    public String getCrlFile() {
        return crlFile;
    }

    public void setCrlFile(String crlFile) {
        this.crlFile = crlFile;
    }

    public String getTrustMaxCertLength() {
        return trustMaxCertLength;
    }

    public void setTrustMaxCertLength(String trustMaxCertLength) {
        this.trustMaxCertLength = trustMaxCertLength;
    }

    public String getSessionCacheSize() {
        return sessionCacheSize;
    }

    public void setSessionCacheSize(String s) {
        sessionCacheSize = s;
    }

    public String getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(String s) {
        sessionTimeout = s;
    }

    public String getAllowUnsafeLegacyRenegotiation() {
        return allowUnsafeLegacyRenegotiation;
    }

    public void setAllowUnsafeLegacyRenegotiation(String s) {
        allowUnsafeLegacyRenegotiation = s;
    }

    public String[] getSslEnabledProtocolsArray() {
        return this.sslEnabledProtocolsarr;
    }

    public void setSslEnabledProtocols(String s) {
        if (s == null) {
            this.sslEnabledProtocolsarr = new String[0];
        } else {
            List<String> sslEnabledProtocols = new ArrayList<>();
            StringTokenizer t = new StringTokenizer(s, ",");
            while (t.hasMoreTokens()) {
                String p = t.nextToken().trim();
                if (p.length() > 0) {
                    sslEnabledProtocols.add(p);
                }
            }
            sslEnabledProtocolsarr = sslEnabledProtocols.toArray(new String[sslEnabledProtocols.size()]);
        }
    }

    public void removeWaitingRequest(SocketWrapper<S> socketWrapper) {
        waitingRequests.remove(socketWrapper);
    }

    protected void configureUseServerCipherSuitesOrder(SSLEngine engine) {
        String useServerCipherSuitesOrderStr = this.getUseServerCipherSuitesOrder().trim();
        if (!"".equals(useServerCipherSuitesOrderStr)) {
            SSLParameters sslParameters = engine.getSSLParameters();
            boolean useServerCipherSuitesOrder = ("true".equalsIgnoreCase(useServerCipherSuitesOrderStr)
                    || "yes".equalsIgnoreCase(useServerCipherSuitesOrderStr));
            try {
                Method m = SSLParameters.class.getMethod("setUseCipherSuitesOrder", Boolean.TYPE);
                m.invoke(sslParameters, useServerCipherSuitesOrder);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalArgumentException | IllegalAccessException exception) {
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"), exception);
            }
            engine.setSSLParameters(sslParameters);
        }
    }

    public AsyncTimeout getAsyncTimeout() {
        return asyncTimeout;
    }

    public void setAsyncTimeout(AsyncTimeout asyncTimeout) {
        this.asyncTimeout = asyncTimeout;
    }

    protected class AsyncTimeout implements Runnable {
        private volatile boolean asyncTimeoutRunning = true;

        @Override
        public void run() {
            while (asyncTimeoutRunning) {
                rest();
                long now = System.currentTimeMillis();
                for (SocketWrapper<S> socket : waitingRequests) {
                    long access = socket.getLastAccess();
                    if (socket.getTimeout() > 0 && (now - access) > socket.getTimeout()) {
                        socket.setTimeout(-1);
                        processSocket(socket, SocketStatus.TIMEOUT, true);
                    }
                }
                while (paused && asyncTimeoutRunning) {
                    rest();
                }

            }
        }

        private void rest() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }

        protected void stop() {
            asyncTimeoutRunning = false;
        }
    }

    public abstract static class Acceptor implements Runnable {
        protected volatile AcceptorState state = AcceptorState.NEW;
        private String threadName;

        public enum AcceptorState {
            NEW, RUNNING, PAUSED, ENDED
        }

        public final AcceptorState getState() {
            return state;
        }

        protected final void setThreadName(final String threadName) {
            this.threadName = threadName;
        }

        protected final String getThreadName() {
            return threadName;
        }
    }
}
