package org.apache.coyote;

import org.apache.juli.logging.Log;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;
import org.apache.tomcat.util.res.StringManager;
import org.lx.tomcat.util.SystemUtil;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractProtocol<S> implements ProtocolHandler, MBeanRegistration {
    protected static final StringManager sm = StringManager.getManager(Constants.Package);
    private static final AtomicInteger nameCounter = new AtomicInteger(0);
    protected ObjectName rgOname = null;
    protected ObjectName tpOname = null;
    private int nameIndex = 0;
    protected AbstractEndpoint<S> endpoint = null;
    protected Adapter adapter;
    protected int processorCache = 200;
    protected String clientCertProvider = null;

    public boolean setProperty(String name, String value) {
        return endpoint.setProperty(name, value);
    }

    public String getProperty(String name) {
        return endpoint.getProperty(name);
    }

    @Override
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    public int getProcessorCache() {
        return this.processorCache;
    }

    public void setProcessorCache(int processorCache) {
        this.processorCache = processorCache;
    }

    public String getClientCertProvider() {
        return clientCertProvider;
    }

    public void setClientCertProvider(String s) {
        this.clientCertProvider = s;
    }

    @Override
    public boolean isAprRequired() {
        return false;
    }

    @Override
    public boolean isCometSupported() {
        return endpoint.getUseComet();
    }

    @Override
    public boolean isCometTimeoutSupported() {
        return endpoint.getUseCometTimeout();
    }

    @Override
    public boolean isSendfileSupported() {
        return endpoint.getUseSendfile();
    }

    @Override
    public Executor getExecutor() {
        return endpoint.getExecutor();
    }

    public void setExecutor(Executor executor) {
        endpoint.setExecutor(executor);
    }

    public int getMaxThreads() {
        return endpoint.getMaxThreads();
    }

    public void setMaxThreads(int maxThreads) {
        endpoint.setMaxThreads(maxThreads);
    }

    public int getMaxConnections() {
        return endpoint.getMaxConnections();
    }

    public void setMaxConnections(int maxConnections) {
        endpoint.setMaxConnections(maxConnections);
    }

    public int getMinSpareThreads() {
        return endpoint.getMinSpareThreads();
    }

    public void setMinSpareThreads(int minSpareThreads) {
        endpoint.setMinSpareThreads(minSpareThreads);
    }

    public int getThreadPriority() {
        return endpoint.getThreadPriority();
    }

    public void setThreadPriority(int threadPriority) {
        endpoint.setThreadPriority(threadPriority);
    }

    public int getBacklog() {
        return endpoint.getBacklog();
    }

    public void setBacklog(int backlog) {
        endpoint.setBacklog(backlog);
    }

    public boolean getTcpNoDelay() {
        return endpoint.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay) {
        endpoint.setTcpNoDelay(tcpNoDelay);
    }

    public int getSoLinger() {
        return endpoint.getSoLinger();
    }

    public void setSoLinger(int soLinger) {
        endpoint.setSoLinger(soLinger);
    }

    public int getKeepAliveTimeout() {
        return endpoint.getKeepAliveTimeout();
    }

    public void setKeepAliveTimeout(int keepAliveTimeout) {
        endpoint.setKeepAliveTimeout(keepAliveTimeout);
    }

    public InetAddress getAddress() {
        return endpoint.getAddress();
    }

    public void setAddress(InetAddress ia) {
        endpoint.setAddress(ia);
    }

    public int getPort() {
        return endpoint.getPort();
    }

    public void setPort(int port) {
        endpoint.setPort(port);
    }

    public int getLocalPort() {
        return endpoint.getLocalPort();
    }

    public int getConnectionTimeout() {
        return endpoint.getSoTimeout();
    }

    public void setConnectionTimeout(int timeout) {
        endpoint.setSoTimeout(timeout);
    }

    public int getSoTimeout() {
        return getConnectionTimeout();
    }

    public void setSoTimeout(int timeout) {
        setConnectionTimeout(timeout);
    }

    public int getMaxHeaderCount() {
        return endpoint.getMaxHeaderCount();
    }

    public void setMaxHeaderCount(int maxHeaderCount) {
        endpoint.setMaxHeaderCount(maxHeaderCount);
    }

    public long getConnectionCount() {
        return endpoint.getConnectionCount();
    }

    public synchronized int getNameIndex() {
        if (nameIndex == 0) {
            nameIndex = nameCounter.incrementAndGet();
        }
        return nameIndex;
    }

    public String getName() {
        StringBuilder name = new StringBuilder(getNamePrefix());
        name.append('-');
        if (getAddress() != null) {
            name.append(getAddress().getHostAddress());
            name.append('-');
        }
        int port = getPort();
        if (port == 0) {
            name.append("auto-");
            name.append(getNameIndex());
            port = getLocalPort();
            if (port != -1) {
                name.append('-');
                name.append(port);
            }
        } else {
            name.append(port);
        }
        return ObjectName.quote(name.toString());
    }

    protected abstract Log getLog();

    protected abstract String getNamePrefix();

    protected abstract String getProtocolName();

    protected abstract Handler getHandler();

    protected String domain;
    protected ObjectName oname;
    protected MBeanServer mserver;

    public ObjectName getObjectName() {
        return oname;
    }

    public String getDomain() {
        return domain;
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        oname = name;
        mserver = server;
        domain = name.getDomain();
        return name;
    }

    @Override
    public void postRegister(Boolean registrationDone) {
    }

    @Override
    public void preDeregister() throws Exception {
    }

    @Override
    public void postDeregister() {
    }

    private ObjectName createObjectName() throws MalformedObjectNameException {
        domain = getAdapter().getDomain();
        if (domain == null) {
            return null;
        }
        StringBuilder name = new StringBuilder(getDomain());
        name.append(":type=ProtocolHandler,port=");
        int port = getPort();
        if (port > 0) {
            name.append(getPort());
        } else {
            name.append("auto-");
            name.append(getNameIndex());
        }
        InetAddress address = getAddress();
        if (address != null) {
            name.append(",address=");
            name.append(ObjectName.quote(address.getHostAddress()));
        }
        return new ObjectName(name.toString());
    }

    @Override
    public void init() throws Exception {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.init", getName()));
        }
        if (oname == null) {
            oname = createObjectName();
            if (oname != null) {
                Registry.getRegistry(null, null).registerComponent(this, oname, null);
            }
        }

        if (this.domain != null) {
            try {
                tpOname = new ObjectName(domain + ":" + "type=ThreadPool,name=" + getName());
                Registry.getRegistry(null, null).registerComponent(endpoint, tpOname, null);
            } catch (Exception e) {
                getLog().error(sm.getString("abstractProtocolHandler.mbeanRegistrationFailed", tpOname, getName()), e);
            }
            rgOname = new ObjectName(domain + ":type=GlobalRequestProcessor,name=" + getName());
            Registry.getRegistry(null, null).registerComponent(getHandler().getGlobal(), rgOname, null);
        }

        String endpointName = getName();
        endpoint.setName(endpointName.substring(1, endpointName.length() - 1));

        try {
            endpoint.init();
        } catch (Exception ex) {
            getLog().error(sm.getString("abstractProtocolHandler.initError", getName()), ex);
            throw ex;
        }
    }

    @Override
    public void start() throws Exception {
        if (getLog().isInfoEnabled())
            getLog().info(sm.getString("abstractProtocolHandler.start", getName()));
        try {
            endpoint.start();
        } catch (Exception ex) {
            getLog().error(sm.getString("abstractProtocolHandler.startError", getName()), ex);
            throw ex;
        }
    }

    @Override
    public void pause() throws Exception {
        if (getLog().isInfoEnabled())
            getLog().info(sm.getString("abstractProtocolHandler.pause", getName()));
        try {
            endpoint.pause();
        } catch (Exception ex) {
            getLog().error(sm.getString("abstractProtocolHandler.pauseError", getName()), ex);
            throw ex;
        }
    }

    @Override
    public void resume() throws Exception {
        if (getLog().isInfoEnabled())
            getLog().info(sm.getString("abstractProtocolHandler.resume", getName()));
        try {
            endpoint.resume();
        } catch (Exception ex) {
            getLog().error(sm.getString("abstractProtocolHandler.resumeError", getName()), ex);
            throw ex;
        }
    }

    @Override
    public void stop() throws Exception {
        if (getLog().isInfoEnabled())
            getLog().info(sm.getString("abstractProtocolHandler.stop", getName()));
        try {
            endpoint.stop();
        } catch (Exception ex) {
            getLog().error(sm.getString("abstractProtocolHandler.stopError", getName()), ex);
            throw ex;
        }
    }

    @Override
    public void destroy() {
        if (getLog().isInfoEnabled()) {
            getLog().info(sm.getString("abstractProtocolHandler.destroy", getName()));
        }
        try {
            endpoint.destroy();
        } catch (Exception e) {
            getLog().error(sm.getString("abstractProtocolHandler.destroyError", getName()), e);
        }

        if (oname != null) {
            if (mserver == null) {
                Registry.getRegistry(null, null).unregisterComponent(oname);
            } else {
                // Possibly registered with a different MBeanServer
                try {
                    mserver.unregisterMBean(oname);
                } catch (MBeanRegistrationException | InstanceNotFoundException e) {
                    getLog().info(sm.getString("abstractProtocol.mbeanDeregistrationFailed", oname, mserver));
                }
            }
        }

        if (tpOname != null)
            Registry.getRegistry(null, null).unregisterComponent(tpOname);
        if (rgOname != null)
            Registry.getRegistry(null, null).unregisterComponent(rgOname);
    }

    // ------------------------------------------- Connection handler base class

    /**
     * 我说AbstractEndPoint为什么定义一个static的interface，原来是为了在这个地方继承和使用啊
     * 一般Handler作为后缀的类都是一些手柄类，也就是真正的处理数据的类
     */
    protected abstract static class AbstractConnectionHandler<S, P extends Processor<S>>
            implements AbstractEndpoint.Handler {

        protected abstract Log getLog();

        protected final RequestGroupInfo global = new RequestGroupInfo();
        protected final AtomicLong registerCount = new AtomicLong(0);

        protected final ConcurrentHashMap<S, Processor<S>> connections = new ConcurrentHashMap<>();

        protected final RecycledProcessors<P, S> recycledProcessors = new RecycledProcessors<>(this);

        protected abstract AbstractProtocol<S> getProtocol();

        @Override
        public Object getGlobal() {
            return global;
        }

        @Override
        public void recycle() {
            recycledProcessors.clear();
        }

        /**
         * 这一块应该是对这个socket的封装吧 反正是只要是访问的时候，这个地方就会出现一次日志的记录问题
         * 这个函数，不得不吐槽一下：真的写的很臃肿啊!
         */
        public SocketState process(SocketWrapper<S> wrapper, SocketStatus status) {
            if (wrapper == null) {
                return SocketState.CLOSED;
            }

            S socket = wrapper.getSocket();
            if (socket == null) {
                return SocketState.CLOSED;
            }

            Processor<S> processor = connections.get(socket);
            if (status == SocketStatus.DISCONNECT && processor == null) {
                return SocketState.CLOSED;
            }

            wrapper.setAsync(false);
            ContainerThreadMarker.set();

            try {
                if (processor == null) {
                    processor = recycledProcessors.pop();
                }
                if (processor == null) {
                    processor = createProcessor();
                }

                initSsl(wrapper, processor);

                SocketState state = SocketState.CLOSED;
                Iterator<DispatchType> dispatches = null;
                SystemUtil.logInfo(this, "二分法的查找，看看是否已经写入到浏览器数据信息了");
                do {
                    if (status == SocketStatus.CLOSE_NOW) {
                        processor.errorDispatch();
                        state = SocketState.CLOSED;
                    } else if (dispatches != null) {
                        connections.put(socket, processor);
                        DispatchType nextDispatch = dispatches.next();
                        if (processor.isUpgrade()) {
                            state = processor.upgradeDispatch(nextDispatch.getSocketStatus());
                        } else {
                            state = processor.asyncDispatch(nextDispatch.getSocketStatus());
                        }
                    } else if (processor.isComet()) {
                        state = processor.event(status);
                    } else if (processor.isUpgrade()) {
                        state = processor.upgradeDispatch(status);
                    } else if (status == SocketStatus.DISCONNECT) {
                        getLog().info("没有执行任何语句，就是空着");
                    } else if (processor.isAsync()) {
                        state = processor.asyncDispatch(status);
                    } else if (state == SocketState.ASYNC_END) {
                        state = processor.asyncDispatch(status);
                        getProtocol().endpoint.removeWaitingRequest(wrapper);
                        if (state == SocketState.OPEN) {
                            state = processor.process(wrapper);
                        }
                    } else if (status == SocketStatus.OPEN_WRITE) {
                        state = SocketState.LONG;
                    } else {
                        state = processor.process(wrapper);
                        SystemUtil.logInfo(this, "就是这一句话，朝向浏览器写入了数据信息");
                    }

                    if (state != SocketState.CLOSED && processor.isAsync()) {
                        state = processor.asyncPostProcess();
                    }

                    if (state == SocketState.UPGRADING) {
                        UpgradeToken upgradeToken = processor.getUpgradeToken();
                        HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
                        ByteBuffer leftoverInput = processor.getLeftoverInput();
                        release(wrapper, processor, false, false);
                        processor = createUpgradeProcessor(wrapper, leftoverInput, upgradeToken);
                        wrapper.setUpgraded(true);
                        connections.put(socket, processor);
                        if (upgradeToken.getInstanceManager() == null) {
                            httpUpgradeHandler.init((WebConnection) processor);
                        } else {
                            ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
                            try {
                                httpUpgradeHandler.init((WebConnection) processor);
                            } finally {
                                upgradeToken.getContextBind().unbind(false, oldCL);
                            }
                        }
                    }
                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Socket: [" + wrapper + "], Status in: [" + status + "], State out: [" + state + "]");
                    }
                    if (dispatches == null || !dispatches.hasNext()) {
                        dispatches = wrapper.getIteratorAndClearDispatches();
                    }
                } while (state == SocketState.ASYNC_END || state == SocketState.UPGRADING
                        || dispatches != null && state != SocketState.CLOSED);

                getLog().info(MessageFormat.format("查看状态信息:{0}", state));
                switch (state) {
                    case LONG:
                        connections.put(socket, processor);
                        longPoll(wrapper, processor);
                        break;
                    case OPEN:
                        connections.remove(socket);
                        release(wrapper, processor, false, true);
                        break;
                    case SENDFILE:
                        connections.remove(socket);
                        release(wrapper, processor, false, false);
                        break;
                    case UPGRADED:
                        if (status != SocketStatus.OPEN_WRITE) {
                            longPoll(wrapper, processor);
                        }
                        break;
                    default:
                        connections.remove(socket);
                        if (processor.isUpgrade()) {
                            UpgradeToken upgradeToken = processor.getUpgradeToken();
                            HttpUpgradeHandler httpUpgradeHandler = upgradeToken.getHttpUpgradeHandler();
                            InstanceManager instanceManager = upgradeToken.getInstanceManager();
                            if (instanceManager == null) {
                                httpUpgradeHandler.destroy();
                            } else {
                                ClassLoader oldCL = upgradeToken.getContextBind().bind(false, null);
                                try {
                                    httpUpgradeHandler.destroy();
                                    instanceManager.destroyInstance(httpUpgradeHandler);
                                } finally {
                                    upgradeToken.getContextBind().unbind(false, oldCL);
                                }
                            }
                        } else {
                            release(wrapper, processor, true, false);
                        }
                        break;
                }
                return state;
            } catch (java.net.SocketException e) {
                getLog().debug(sm.getString("abstractConnectionHandler.socketexception.debug"), e);
            } catch (java.io.IOException e) {
                getLog().debug(sm.getString("abstractConnectionHandler.ioexception.debug"), e);
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                getLog().error(sm.getString("abstractConnectionHandler.error"), e);
            } finally {
                ContainerThreadMarker.clear();
            }

            connections.remove(socket);
            if (processor != null && !processor.isUpgrade()) {
                release(wrapper, processor, true, false);
            }
            return SocketState.CLOSED;
        }

        protected abstract P createProcessor();

        protected abstract void initSsl(SocketWrapper<S> socket, Processor<S> processor);

        protected abstract void longPoll(SocketWrapper<S> socket, Processor<S> processor);

        protected abstract void release(SocketWrapper<S> socket, Processor<S> processor, boolean socketClosing,
                                        boolean addToPoller);

        protected abstract Processor<S> createUpgradeProcessor(SocketWrapper<S> socket, ByteBuffer leftoverInput,
                                                               UpgradeToken upgradeToken) throws IOException;

        protected void register(AbstractProcessor<S> processor) {
            if (getProtocol().getDomain() != null) {
                synchronized (this) {
                    try {
                        long count = registerCount.incrementAndGet();
                        RequestInfo rp = processor.getRequest().getRequestProcessor();
                        rp.setGlobalProcessor(global);
                        ObjectName rpName = new ObjectName(
                                getProtocol().getDomain() + ":type=RequestProcessor,worker=" + getProtocol().getName()
                                        + ",name=" + getProtocol().getProtocolName() + "Request" + count);
                        if (getLog().isDebugEnabled()) {
                            getLog().debug("Register " + rpName);
                        }
                        Registry.getRegistry(null, null).registerComponent(rp, rpName, null);
                        rp.setRpName(rpName);
                    } catch (Exception e) {
                        getLog().warn("Error registering request");
                    }
                }
            }
        }

        protected void unregister(Processor<S> processor) {
            if (getProtocol().getDomain() != null) {
                synchronized (this) {
                    try {
                        Request r = processor.getRequest();
                        if (r == null) {
                            return;
                        }
                        RequestInfo rp = r.getRequestProcessor();
                        rp.setGlobalProcessor(null);
                        ObjectName rpName = rp.getRpName();
                        if (getLog().isDebugEnabled()) {
                            getLog().debug("Unregister " + rpName);
                        }
                        Registry.getRegistry(null, null).unregisterComponent(rpName);
                        rp.setRpName(null);
                    } catch (Exception e) {
                        getLog().warn("Error unregistering request", e);
                    }
                }
            }
        }
    }

    protected static class RecycledProcessors<P extends Processor<S>, S> extends SynchronizedStack<Processor<S>> {

        private final transient AbstractConnectionHandler<S, P> handler;
        protected final AtomicInteger size = new AtomicInteger(0);

        public RecycledProcessors(AbstractConnectionHandler<S, P> handler) {
            this.handler = handler;
        }

        @SuppressWarnings("sync-override") // Size may exceed cache size a bit
        @Override
        public boolean push(Processor<S> processor) {
            int cacheSize = handler.getProtocol().getProcessorCache();
            boolean offer = cacheSize == -1 ? true : size.get() < cacheSize;
            // avoid over growing our cache or add after we have stopped
            boolean result = false;
            if (offer) {
                result = super.push(processor);
                if (result) {
                    size.incrementAndGet();
                }
            }
            if (!result)
                handler.unregister(processor);
            return result;
        }

        @SuppressWarnings("sync-override") // OK if size is too big briefly
        @Override
        public Processor<S> pop() {
            Processor<S> result = super.pop();
            if (result != null) {
                size.decrementAndGet();
            }
            return result;
        }

        @Override
        public synchronized void clear() {
            Processor<S> next = pop();
            while (next != null) {
                handler.unregister(next);
                next = pop();
            }
            super.clear();
            size.set(0);
        }
    }
}
