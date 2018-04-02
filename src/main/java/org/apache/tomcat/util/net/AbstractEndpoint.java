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
 * 还好只有1K行的代码，以前自己对200行的代码都会感觉这么多，现在是对1K行的代码，感觉，都是没有什么的 就是这么的任性啊
 * 作者也没有写这个抽象类的注释，这让我可是好找啊，不过，对于这个前台传递过来的信息都是这个是幕后主导者
 *
 * @param <S>
 * @author liuxu
 */
public abstract class AbstractEndpoint<S> {

    protected static final String DEFAULT_CIPHERS = "HIGH:!aNULL:!eNULL:!EXPORT:!DES:!RC4:!MD5";

    protected static final StringManager sm = StringManager.getManager("org.apache.tomcat.util.net.res");

    /**
     * 真是活久见啊，static方式的interface都能见到啊
     */
    public interface Handler {
        /**
         * Different types of socket states to react upon.
         */
        /**
         * 其实枚举类型才是一种定义常量的好方式 我以前是对这个enum的认识不够深刻，所以，导致这个使用上面很少
         * 不过，我现在的认识就是：其实他就像是一个class一样子，也是一种东西
         * 比如说，我可以定义：class、interface、enum这三种（我目前只能想清楚这三种）
         * 这就是我们读取源代码的好处，可以涉足到我们工作用不到的地方，但是，一个地方，你么有涉足过
         * 那么，我们就没有使用的权限，熟悉是使用的前提，所以，必须熟悉这个领域的东西，
         *
         * @author liuxu
         */
        enum SocketState {
            // TODO Add a new state to the AsyncStateMachine and remove
            // ASYNC_END (if possible)
            OPEN, CLOSED, LONG, ASYNC_END, SENDFILE, UPGRADING, UPGRADED
        }

        /**
         * Obtain the GlobalRequestProcessor associated with the handler.
         */
        Object getGlobal();

        /**
         * Recycle resources associated with the handler.
         */
        void recycle();
    }

    protected enum BindState {
        UNBOUND, BOUND_ON_INIT, BOUND_ON_START
    }

    /**
     * 从网上的搜索可以看出来，似乎就是这个acceptor进行了一个socket的接待，其实就是一个
     * ServerSocket的一个accept的方法的一个代理封装吧，反正基本的accept就是这么操作的
     *
     * @author liuxu
     */
    public abstract static class Acceptor implements Runnable {
        /**
         * 其实enum是常量定义的一个选择方式
         *
         * @author liuxu
         */
        public enum AcceptorState {
            NEW, RUNNING, PAUSED, ENDED
        }

        protected volatile AcceptorState state = AcceptorState.NEW;

        public final AcceptorState getState() {
            return state;
        }

        private String threadName;

        /**
         * 这个权限的限制不错啊，只要是这个abstract的类，那么肯定是会被继承实现的，所以啊，这个不需要改变的
         * 方法，一定给一个final的修饰，不然，你知道这些个用户都是什么人啊，有些人就是喜欢更改，真是有病
         * 但是，规则是限制人们行为的一个圈，人们都喜欢好奇和出圈
         *
         * @param threadName
         */
        protected final void setThreadName(final String threadName) {
            this.threadName = threadName;
        }

        protected final String getThreadName() {
            return threadName;
        }
    }

    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;

    /**
     * Async timeout thread
     */
    protected class AsyncTimeout implements Runnable {

        /**
         * 看看这个boolean的变量的名字的定义，真是一种美国风味的命名格式 完全是美帝资本主义的命名方式
         */
        private volatile boolean asyncTimeoutRunning = true;

        /**
         * The background thread that checks async requests and fires the
         * timeout if there has been no activity.
         */
        @Override
        public void run() {

            // Loop until we receive a shutdown command
            while (asyncTimeoutRunning) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore
                    /**
                     * 又是一个ignore的异常信息，从来不是打印出来这个异常信息， 严重的鄙视printStackTrace的方式
                     */
                }
                long now = System.currentTimeMillis();
                for (SocketWrapper<S> socket : waitingRequests) {
                    long access = socket.getLastAccess();
                    if (socket.getTimeout() > 0 && (now - access) > socket.getTimeout()) {
                        // Prevent multiple timeouts
                        socket.setTimeout(-1);
                        processSocket(socket, SocketStatus.TIMEOUT, true);
                    }
                }

                // Loop if endpoint is paused
                while (paused && asyncTimeoutRunning) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

            }
        }

        protected void stop() {
            asyncTimeoutRunning = false;
        }
    }

    // ----------------------------------------------------------------- Fields

    /**
     * Running state of the endpoint.
     * 这个endpoint的状态信息
     */
    protected volatile boolean running = false;

    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;

    /**
     * Are we using an internal executor
     */
    protected volatile boolean internalExecutor = false;

    /**
     * counter for nr of connections handled by an endpoint
     */
    private volatile LimitLatch connectionLimitLatch = null;

    /**
     * Socket properties
     */
    protected SocketProperties socketProperties = new SocketProperties();

    public SocketProperties getSocketProperties() {
        return socketProperties;
    }

    /**
     * Threads used to accept new connections and pass them to worker threads.
     */
    protected Acceptor[] acceptors;

    // -----------------------------------------------------------------
    // Properties

    /**
     * Time to wait for the internal executor (if used) to terminate when the
     * endpoint is stopped in milliseconds. Defaults to 5000 (5 seconds).
     */
    private long executorTerminationTimeoutMillis = 5000;

    public long getExecutorTerminationTimeoutMillis() {
        return executorTerminationTimeoutMillis;
    }

    public void setExecutorTerminationTimeoutMillis(long executorTerminationTimeoutMillis) {
        this.executorTerminationTimeoutMillis = executorTerminationTimeoutMillis;
    }

    /**
     * Acceptor thread count.
     */
    protected int acceptorThreadCount = 0;

    public void setAcceptorThreadCount(int acceptorThreadCount) {
        this.acceptorThreadCount = acceptorThreadCount;
    }

    public int getAcceptorThreadCount() {
        return acceptorThreadCount;
    }

    /**
     * Priority of the acceptor threads.
     */
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;

    public void setAcceptorThreadPriority(int acceptorThreadPriority) {
        this.acceptorThreadPriority = acceptorThreadPriority;
    }

    public int getAcceptorThreadPriority() {
        return acceptorThreadPriority;
    }

    private int maxConnections = 10000;

    public void setMaxConnections(int maxCon) {
        this.maxConnections = maxCon;
        LimitLatch latch = this.connectionLimitLatch;
        if (latch != null) {
            // Update the latch that enforces this
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

    /**
     * Return the current count of connections handled by this endpoint, if the
     * connections are counted (which happens when the maximum count of
     * connections is limited), or <code>-1</code> if they are not. This
     * property is added here so that this value can be inspected through JMX.
     * It is visible on "ThreadPool" MBean.
     * <p>
     * <p>
     * The count is incremented by the Acceptor before it tries to accept a new
     * connection. Until the limit is reached and thus the count cannot be
     * incremented, this value is more by 1 (the count of acceptors) than the
     * actual count of connections that are being served.
     *
     * @return The count
     */
    public long getConnectionCount() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            return latch.getCount();
        }
        return -1;
    }

    /**
     * External Executor based thread pool. External:外部，外面的意思，
     * 这个是tomcat的使用的外部的线程池，这个线程池其实就是一个接口，就是通过Executors的方法获取的一个接口
     * 应该是有人写好了这个实现，这样子，我们就可以使用java自带的jdk的线程池就行了
     */
    private Executor executor = null;

    /**
     * 这个是tomcat进行一个线程池的选择的一个过程，就是说，我们的tomcat在获取浏览器或者是说的更加准确一点
     * 就是客户端，发送的请求信息的时候，肯定是使用socket进行一个接触以后，就使用线程池进行一个处理工作
     * 但是，根据面向过程的编码原则，这个线程池怎么实现是一个问题。
     * tomcat的方案是，：使用java自带的jdk环境的线程池，和自己写线程池，但是，这个tomcat似乎有这个spring
     * 的影子在里面，或者是说spring是受这个tomcat的影响导致的，反正是都是那种可以配置的东西，
     * 所以，这个外部的线程池就是用户自己配置的，可以提供给你选择处理这个socket
     */
    public void setExecutor(Executor executor) {
        this.executor = executor;
        this.internalExecutor = (executor == null);
    }

    public Executor getExecutor() {
        return executor;
    }

    /**
     * Server socket port.
     */
    private int port;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public abstract int getLocalPort();

    /**
     * Address for the server socket.
     */
    private InetAddress address;

    public InetAddress getAddress() {
        return address;
    }

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    /**
     * Allows the server developer to specify the backlog that should be used
     * for server sockets. By default, this value is 100.
     * 这个其实是java的socket编程：就是说，这个socket是客户端，但是这个服务器端是：ServerSocket，但是这个客户端之恩那个
     * 只能发送一个请求，但是服务器端就需要接受多个请求，这个时候，我们需要进行一个操作就是：给一个队列，让他们等着
     */
    private int backlog = 100;

    public void setBacklog(int backlog) {
        if (backlog > 0)
            this.backlog = backlog;
    }

    public int getBacklog() {
        return backlog;
    }

    /**
     * Controls when the Endpoint binds the port. <code>true</code>, the default
     * binds the port on {@link #init()} and unbinds it on {@link #destroy()}.
     * If set to <code>false</code> the port is bound on {@link #start()} and
     * unbound on {@link #stop()}.
     */
    private boolean bindOnInit = true;

    public boolean getBindOnInit() {
        return bindOnInit;
    }

    public void setBindOnInit(boolean b) {
        this.bindOnInit = b;
    }

    private BindState bindState = BindState.UNBOUND;

    /**
     * Keepalive timeout, if not set the soTimeout is used.
     */
    private Integer keepAliveTimeout = null;

    public int getKeepAliveTimeout() {
        if (keepAliveTimeout == null) {
            return getSoTimeout();
        } else {
            return keepAliveTimeout.intValue();
        }
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = Integer.valueOf(keepAliveTimeout);
    }

    /**
     * Socket TCP no delay.
     */
    public boolean getTcpNoDelay() {
        return socketProperties.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        socketProperties.setTcpNoDelay(tcpNoDelay);
    }

    /**
     * Socket linger.
     */
    public int getSoLinger() {
        return socketProperties.getSoLingerTime();
    }

    public void setSoLinger(int soLinger) {
        socketProperties.setSoLingerTime(soLinger);
        socketProperties.setSoLingerOn(soLinger >= 0);
    }

    /**
     * Socket timeout.
     */
    public int getSoTimeout() {
        return socketProperties.getSoTimeout();
    }

    public void setSoTimeout(int soTimeout) {
        socketProperties.setSoTimeout(soTimeout);
    }

    /**
     * SSL engine.
     */
    private boolean SSLEnabled = false;

    public boolean isSSLEnabled() {
        return SSLEnabled;
    }

    public void setSSLEnabled(boolean SSLEnabled) {
        this.SSLEnabled = SSLEnabled;
    }

    private int minSpareThreads = 10;

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

    /**
     * Maximum amount of worker threads.
     */
    private int maxThreads = 200;

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

    /**
     * Max keep alive requests
     */
    private int maxKeepAliveRequests = 100; // as in Apache HTTPD server

    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }

    public void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }

    /**
     * The maximum number of headers in a request that are allowed. 100 by
     * default. A value of less than 0 means no limit.
     */
    private int maxHeaderCount = 100; // as in Apache HTTPD server

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        this.maxHeaderCount = maxHeaderCount;
    }

    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    private String name = "TP";

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * The default is true - the created threads will be in daemon mode. If set
     * to false, the control thread will not be daemon - and will keep the
     * process alive.
     */
    private boolean daemon = true;

    public void setDaemon(boolean b) {
        daemon = b;
    }

    public boolean getDaemon() {
        return daemon;
    }

    /**
     * Priority of the worker threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;

    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public int getThreadPriority() {
        return threadPriority;
    }

    protected abstract boolean getDeferAccept();

    /**
     * Attributes provide a way for configuration to be passed to sub-components
     * without the {@link org.apache.coyote.ProtocolHandler} being aware of the
     * properties available on those sub-components. One example of such a
     * sub-component is the
     * {@link org.apache.tomcat.util.net.ServerSocketFactory}.
     */
    protected HashMap<String, Object> attributes = new HashMap<>();

    /**
     * Generic property setter called when a property for which a specific
     * setter already exists within the
     * {@link org.apache.coyote.ProtocolHandler} needs to be made available to
     * sub-components. The specific setter will call this method to populate the
     * attributes.
     */
    public void setAttribute(String name, Object value) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.setAttribute", name, value));
        }
        attributes.put(name, value);
    }

    /**
     * Used by sub-components to retrieve configuration information.
     */
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

    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
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

    /**
     * Return the amount of threads that are in use
     *
     * @return the amount of threads that are in use
     */
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

    /**
     * 如果用户没有使用外部的线程池，那么对不起这个我们的用户要使用这个内部的线程池了，估计是很高效率的
     */
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
                // this is our internal one, so we need to shut it down
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                long timeout = getExecutorTerminationTimeoutMillis();
                if (timeout > 0) {
                    try {
                        tpe.awaitTermination(timeout, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // Ignore
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

    /**
     * Unlock the server socket accept using a bogus connection.
     */
    protected void unlockAccept() {
        // Only try to unlock the acceptor if it is necessary
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
            // Need to create a connection to unlock the accept();
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
                // TODO Consider hard-coding to s.setSoLinger(true,0)
                s.setSoLinger(getSocketProperties().getSoLingerOn(), getSocketProperties().getSoLingerTime());
                if (getLog().isDebugEnabled()) {
                    getLog().debug("About to unlock socket for:" + saddr);
                }
                s.connect(saddr, utmo);
                if (getDeferAccept()) {
                    /*
                     * In the case of a deferred accept / accept filters we need
                     * to send data to wake up the accept. Send OPTIONS * to
                     * bypass even BSD accept filters. The Acceptor will discard
                     * it.
                     */
                    OutputStreamWriter sw;

                    sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
                    sw.write("OPTIONS * HTTP/1.0\r\n" + "User-Agent: Tomcat wakeup connection\r\n\r\n");
                    sw.flush();
                }
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Socket unlock completed for:" + saddr);
                }

                // Wait for upto 1000ms acceptor threads to unlock
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

    // ---------------------------------------------- Request processing methods

    /**
     * Process the given SocketWrapper with the given status. Used to trigger
     * processing as if the Poller (for those endpoints that have one) selected
     * the socket.
     *
     * @param socketWrapper The socket wrapper to process
     * @param socketStatus  The input status to the processing
     * @param dispatch      Should the processing be performed on a new container thread
     */
    public abstract void processSocket(SocketWrapper<S> socketWrapper, SocketStatus socketStatus, boolean dispatch);

    public void executeNonBlockingDispatches(SocketWrapper<S> socketWrapper) {
        /*
         * This method is called when non-blocking IO is initiated by defining a
         * read and/or write listener in a non-container thread. It is called
         * once the non-container thread completes so that the first calls to
         * onWritePossible() and/or onDataAvailable() as appropriate are made by
         * the container.
         *
         * Processing the dispatches requires (for BIO and APR/native at least)
         * that the socket has been added to the waitingRequests queue. This may
         * not have occurred by the time that the non-container thread completes
         * triggering the call to this method. Therefore, the coded syncs on the
         * SocketWrapper as the container thread that initiated this
         * non-container thread holds a lock on the SocketWrapper. The container
         * thread will add the socket to the waitingRequests queue before
         * releasing the lock on the socketWrapper. Therefore, by obtaining the
         * lock on socketWrapper before processing the dispatches, we can be
         * sure that the socket has been added to the waitingRequests queue.
         */
        synchronized (socketWrapper) {
            Iterator<DispatchType> dispatches = socketWrapper.getIteratorAndClearDispatches();

            while (dispatches != null && dispatches.hasNext()) {
                DispatchType dispatchType = dispatches.next();
                processSocket(socketWrapper, dispatchType.getSocketStatus(), false);
            }
        }
    }

    // ------------------------------------------------------- Lifecycle methods

    /*
     * NOTE: There is no maintenance of state or checking for valid transitions
     * within this class other than ensuring that bind/unbind are called in the
     * right place. It is expected that the calling code will maintain state and
     * prevent invalid state transitions.
     */

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
        // Only test this feature if the user explicitly requested its use.
        if (!"".equals(getUseServerCipherSuitesOrder().trim())) {
            try {
                // This method is only available in Java 8+
                // Check to see if the method exists, and then call it.
                SSLParameters.class.getMethod("setUseCipherSuitesOrder", Boolean.TYPE);
            } catch (NoSuchMethodException nsme) {
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"),
                        nsme);
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
        int count = getAcceptorThreadCount();
        acceptors = new Acceptor[count];

        for (int i = 0; i < count; i++) {
            acceptors[i] = createAcceptor();
            String threadName = getName() + "-Acceptor-" + i;
            acceptors[i].setThreadName(threadName);
            Thread t = new Thread(acceptors[i], threadName);
            t.setPriority(getAcceptorThreadPriority());
            t.setDaemon(getDaemon());
            t.start();
        }
    }

    /**
     * Hook to allow Endpoints to provide a specific Acceptor implementation.
     */
    protected abstract Acceptor createAcceptor();

    /**
     * Pause the endpoint, which will stop it accepting new connections.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }

    /**
     * Resume the endpoint, which will make it start accepting new connections
     * again.
     */
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

    // Flags to indicate optional feature support
    // Some of these are always hard-coded, some are hard-coded to false (i.e.
    // the endpoint does not support them) and some are configurable.
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

    /**
     * 这个函数好像就是tomcat能一次性处理的线程数了。
     * 通过这个函数的分析，可以看出来如果maxConnections没有进行设置，那么就是对线程个数没有任何限制
     * 但是系统资源会压制我们的个数，我觉得还是限制点的好。
     * 当然如果设置了总数限制，那么就使用LimitLatch这个类进行统计就好了，这个是线程安全的，请放心使用。
     * 换句话来说，如果这个maxConnections的数值是-1的情况下，tomcat就不会限制，不会让这个当前的线程阻塞
     */
    protected void countUpOrAwaitConnection() throws InterruptedException {
        if (maxConnections == -1)
            return;
        LimitLatch latch = connectionLimitLatch;
        if (latch != null)
            latch.countUpOrAwait();
    }

    protected long countDownConnection() {
        if (maxConnections == -1)
            return -1;
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            long result = latch.countDown();
            if (result < 0) {
                getLog().warn("Incorrect connection count, multiple socket.close called on the same socket.");
            }
            return result;
        } else
            return -1;
    }

    /**
     * Provides a common approach for sub-classes to handle exceptions where a
     * delay is required to prevent a Thread from entering a tight loop which
     * will consume CPU and may also trigger large amounts of logging. For
     * example, this can happen with the Acceptor thread if the ulimit for open
     * files is reached.
     *
     * @param currentErrorDelay The current delay being applied on failure
     * @return The delay to apply on the next failure
     */
    protected int handleExceptionWithDelay(int currentErrorDelay) {
        // Don't delay on first exception
        if (currentErrorDelay > 0) {
            try {
                Thread.sleep(currentErrorDelay);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        // On subsequent exceptions, start the delay at 50ms, doubling the delay
        // on every subsequent exception until the delay reaches 1.6 seconds.
        if (currentErrorDelay == 0) {
            return INITIAL_ERROR_DELAY;
        } else if (currentErrorDelay < MAX_ERROR_DELAY) {
            return currentErrorDelay * 2;
        } else {
            return MAX_ERROR_DELAY;
        }

    }

    // -------------------- SSL related properties --------------------

    private String algorithm = KeyManagerFactory.getDefaultAlgorithm();

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String s) {
        this.algorithm = s;
    }

    private String clientAuth = "false";

    public String getClientAuth() {
        return clientAuth;
    }

    public void setClientAuth(String s) {
        this.clientAuth = s;
    }

    private String keystoreFile = System.getProperty("user.home") + "/.keystore";

    public String getKeystoreFile() {
        return keystoreFile;
    }

    public void setKeystoreFile(String s) {
        keystoreFile = s;
    }

    private String keystorePass = null;

    public String getKeystorePass() {
        return keystorePass;
    }

    public void setKeystorePass(String s) {
        this.keystorePass = s;
    }

    private String keystoreType = "JKS";

    public String getKeystoreType() {
        return keystoreType;
    }

    public void setKeystoreType(String s) {
        this.keystoreType = s;
    }

    private String keystoreProvider = null;

    public String getKeystoreProvider() {
        return keystoreProvider;
    }

    public void setKeystoreProvider(String s) {
        this.keystoreProvider = s;
    }

    private String sslProtocol = Constants.SSL_PROTO_TLS;

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String s) {
        sslProtocol = s;
    }

    private String ciphers = DEFAULT_CIPHERS;

    public String getCiphers() {
        return ciphers;
    }

    public void setCiphers(String s) {
        ciphers = s;
    }

    /**
     * @return The ciphers in use by this Endpoint
     */
    public abstract String[] getCiphersUsed();

    private String useServerCipherSuitesOrder = "";

    public String getUseServerCipherSuitesOrder() {
        return useServerCipherSuitesOrder;
    }

    public void setUseServerCipherSuitesOrder(String s) {
        this.useServerCipherSuitesOrder = s;
    }

    private String keyAlias = null;

    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String s) {
        keyAlias = s;
    }

    private String keyPass = null;

    public String getKeyPass() {
        return keyPass;
    }

    public void setKeyPass(String s) {
        this.keyPass = s;
    }

    private String truststoreFile = System.getProperty("javax.net.ssl.trustStore");

    public String getTruststoreFile() {
        return truststoreFile;
    }

    public void setTruststoreFile(String s) {
        truststoreFile = s;
    }

    private String truststorePass = System.getProperty("javax.net.ssl.trustStorePassword");

    public String getTruststorePass() {
        return truststorePass;
    }

    public void setTruststorePass(String truststorePass) {
        this.truststorePass = truststorePass;
    }

    private String truststoreType = System.getProperty("javax.net.ssl.trustStoreType");

    public String getTruststoreType() {
        return truststoreType;
    }

    public void setTruststoreType(String truststoreType) {
        this.truststoreType = truststoreType;
    }

    private String truststoreProvider = null;

    public String getTruststoreProvider() {
        return truststoreProvider;
    }

    public void setTruststoreProvider(String truststoreProvider) {
        this.truststoreProvider = truststoreProvider;
    }

    private String truststoreAlgorithm = null;

    public String getTruststoreAlgorithm() {
        return truststoreAlgorithm;
    }

    public void setTruststoreAlgorithm(String truststoreAlgorithm) {
        this.truststoreAlgorithm = truststoreAlgorithm;
    }

    private String trustManagerClassName = null;

    public String getTrustManagerClassName() {
        return trustManagerClassName;
    }

    public void setTrustManagerClassName(String trustManagerClassName) {
        this.trustManagerClassName = trustManagerClassName;
    }

    private String crlFile = null;

    public String getCrlFile() {
        return crlFile;
    }

    public void setCrlFile(String crlFile) {
        this.crlFile = crlFile;
    }

    private String trustMaxCertLength = null;

    public String getTrustMaxCertLength() {
        return trustMaxCertLength;
    }

    public void setTrustMaxCertLength(String trustMaxCertLength) {
        this.trustMaxCertLength = trustMaxCertLength;
    }

    private String sessionCacheSize = null;

    public String getSessionCacheSize() {
        return sessionCacheSize;
    }

    public void setSessionCacheSize(String s) {
        sessionCacheSize = s;
    }

    private String sessionTimeout = "86400";

    public String getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(String s) {
        sessionTimeout = s;
    }

    private String allowUnsafeLegacyRenegotiation = null;

    public String getAllowUnsafeLegacyRenegotiation() {
        return allowUnsafeLegacyRenegotiation;
    }

    public void setAllowUnsafeLegacyRenegotiation(String s) {
        allowUnsafeLegacyRenegotiation = s;
    }

    private String[] sslEnabledProtocolsarr = new String[0];

    public String[] getSslEnabledProtocolsArray() {
        return this.sslEnabledProtocolsarr;
    }

    public void setSslEnabledProtocols(String s) {
        if (s == null) {
            this.sslEnabledProtocolsarr = new String[0];
        } else {
            ArrayList<String> sslEnabledProtocols = new ArrayList<>();
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

    protected final Set<SocketWrapper<S>> waitingRequests = Collections
            .newSetFromMap(new ConcurrentHashMap<SocketWrapper<S>, Boolean>());

    public void removeWaitingRequest(SocketWrapper<S> socketWrapper) {
        waitingRequests.remove(socketWrapper);
    }

    /**
     * Configures SSLEngine to honor cipher suites ordering based upon endpoint
     * configuration.
     */
    protected void configureUseServerCipherSuitesOrder(SSLEngine engine) {
        String useServerCipherSuitesOrderStr = this.getUseServerCipherSuitesOrder().trim();

        // Only use this feature if the user explicitly requested its use.
        if (!"".equals(useServerCipherSuitesOrderStr)) {
            SSLParameters sslParameters = engine.getSSLParameters();
            boolean useServerCipherSuitesOrder = ("true".equalsIgnoreCase(useServerCipherSuitesOrderStr)
                    || "yes".equalsIgnoreCase(useServerCipherSuitesOrderStr));

            try {
                // This method is only available in Java 8+
                // Check to see if the method exists, and then call it.
                Method m = SSLParameters.class.getMethod("setUseCipherSuitesOrder", Boolean.TYPE);

                m.invoke(sslParameters, Boolean.valueOf(useServerCipherSuitesOrder));
            } catch (NoSuchMethodException nsme) {
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"),
                        nsme);
            } catch (InvocationTargetException ite) {
                // Should not happen
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"),
                        ite);
            } catch (IllegalArgumentException iae) {
                // Should not happen
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"),
                        iae);
            } catch (IllegalAccessException e) {
                // Should not happen
                throw new UnsupportedOperationException(sm.getString("endpoint.jsse.cannotHonorServerCipherOrder"), e);
            }
            engine.setSSLParameters(sslParameters);
        }
    }

    /**
     * The async timeout thread.
     */
    private AsyncTimeout asyncTimeout = null;

    public AsyncTimeout getAsyncTimeout() {
        return asyncTimeout;
    }

    public void setAsyncTimeout(AsyncTimeout asyncTimeout) {
        this.asyncTimeout = asyncTimeout;
    }
}
