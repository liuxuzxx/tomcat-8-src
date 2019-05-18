package org.apache.tomcat.util.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.X509KeyManager;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;
import org.lx.tomcat.util.SystemUtil;

public class NioEndpoint extends AbstractEndpoint<NioChannel> {
    private static final Log log = LogFactory.getLog(NioEndpoint.class);
    public static final int OP_REGISTER = 0x100;
    private static final String oomParachuteMsg = "SEVERE:内存不够用, parachute is non existent, 系统启动失败.";
    private NioSelectorPool selectorPool = new NioSelectorPool();
    private ServerSocketChannel serverSock = null;
    private boolean useSendfile = true;
    private int oomParachute = 1024 * 1024;
    private byte[] oomParachuteData = null;
    private long lastParachuteCheck = System.currentTimeMillis();
    private volatile CountDownLatch stopLatch = null;
    private SynchronizedStack<SocketProcessor> processorCache;
    private SynchronizedStack<PollerEvent> eventCache;
    private SynchronizedStack<NioChannel> nioChannels;
    private Handler handler = null;
    private boolean useComet = true;
    private int pollerThreadCount = Math.min(2, Runtime.getRuntime().availableProcessors());
    private long selectorTimeout = 1000;
    private Poller[] pollers = null;
    private AtomicInteger pollerRotator = new AtomicInteger(0);
    private SSLContext sslContext = null;
    private String[] enabledCiphers;
    private String[] enabledProtocols;
    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception x) {
            log.error("Unable to set attribute \"" + name + "\" to \"" + value + "\"", x);
            return false;
        }
    }

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }

    public void setUseComet(boolean useComet) {
        this.useComet = useComet;
    }

    @Override
    public boolean getUseComet() {
        return useComet;
    }

    @Override
    public boolean getUseCometTimeout() {
        return getUseComet();
    }

    @Override
    public boolean getUsePolling() {
        return true;
    }

    public void setPollerThreadCount(int pollerThreadCount) {
        this.pollerThreadCount = pollerThreadCount;
    }

    public int getPollerThreadCount() {
        return pollerThreadCount;
    }

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    private Poller getPoller0() {
        int idx = Math.abs(pollerRotator.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }

    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }

    @Override
    public boolean getDeferAccept() {
        return false;
    }

    public void setOomParachute(int oomParachute) {
        this.oomParachute = oomParachute;
    }

    public void setOomParachuteData(byte[] oomParachuteData) {
        this.oomParachuteData = oomParachuteData;
    }

    public SSLContext getSSLContext() {
        return sslContext;
    }

    public void setSSLContext(SSLContext c) {
        sslContext = c;
    }

    @Override
    public int getLocalPort() {
        ServerSocketChannel ssc = serverSock;
        if (ssc == null) {
            return -1;
        } else {
            ServerSocket s = ssc.socket();
            if (s == null) {
                return -1;
            } else {
                return s.getLocalPort();
            }
        }
    }

    @Override
    public String[] getCiphersUsed() {
        return enabledCiphers;
    }

    protected void checkParachute() {
        boolean para = reclaimParachute(false);
        if (!para && (System.currentTimeMillis() - lastParachuteCheck) > 10000) {
            try {
                log.fatal(oomParachuteMsg);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                System.err.println(oomParachuteMsg);
            }
            lastParachuteCheck = System.currentTimeMillis();
        }
    }

    protected boolean reclaimParachute(boolean force) {
        if (oomParachuteData != null)
            return true;
        if (oomParachute > 0 && (force || (Runtime.getRuntime().freeMemory() > (oomParachute * 2))))
            oomParachuteData = new byte[oomParachute];
        return oomParachuteData != null;
    }

    protected void releaseCaches() {
        this.nioChannels.clear();
        this.processorCache.clear();
        if (handler != null)
            handler.recycle();

    }

    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            return Arrays.stream(pollers).mapToInt(Poller::getKeyCount).sum();
        }
    }

    @Override
    public void bind() throws Exception {
        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress address = (getAddress() != null ? new InetSocketAddress(getAddress(), getPort()) : new InetSocketAddress(getPort()));
        serverSock.socket().bind(address, getBacklog());
        serverSock.configureBlocking(true);
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        if (acceptorThreadCount == 0) {
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);
        if (isSSLEnabled()) {
            SSLUtil sslUtil = handler.getSslImplementation().getSSLUtil(this);
            sslContext = sslUtil.createSSLContext();
            sslContext.init(wrap(sslUtil.getKeyManagers()), sslUtil.getTrustManagers(), null);
            SSLSessionContext sessionContext = sslContext.getServerSessionContext();
            if (sessionContext != null) {
                sslUtil.configureSessionContext(sessionContext);
            }
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }

        if (oomParachute > 0) {
            reclaimParachute(true);
        }
        selectorPool.open();
    }

    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers == null) {
            return null;
        }
        KeyManager[] result = new KeyManager[managers.length];
        for (int i = 0; i < result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias() != null) {
                String keyAlias = getKeyAlias();
                if ("jks".equalsIgnoreCase(getKeystoreType())) {
                    keyAlias = keyAlias.toLowerCase(Locale.ENGLISH);
                }
                result[i] = new NioX509KeyManager((X509KeyManager) managers[i], keyAlias);
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }

    @Override
    public void startInternal() throws Exception {
        if (!running) {
            running = true;
            paused = false;
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getProcessorCache());
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getEventCache());
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getBufferPool());

            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch();
            startPollerThreads();
            startAcceptorThreads();
        }
    }

    private void startPollerThreads() throws IOException {
        pollers = new Poller[getPollerThreadCount()];
        for (int i = 0; i < pollers.length; i++) {
            pollers[i] = new Poller();
            Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-" + i);
            pollerThread.setPriority(threadPriority);
            pollerThread.setDaemon(true);
            pollerThread.start();
        }
    }

    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i = 0; pollers != null && i < pollers.length; i++) {
                if (pollers[i] == null) {
                    continue;
                }
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                stopLatch.await(selectorTimeout + 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }

    }

    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " + new InetSocketAddress(getAddress(), getPort()));
        }
        if (running) {
            stop();
        }
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = null;
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " + new InetSocketAddress(getAddress(), getPort()));
        }
    }

    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }

    @Override
    public boolean getUseSendfile() {
        return useSendfile;
    }

    public int getOomParachute() {
        return oomParachute;
    }

    public byte[] getOomParachuteData() {
        return oomParachuteData;
    }

    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }

    protected boolean setSocketOptions(SocketChannel socket) {
        try {
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                if (sslContext != null) {
                    SSLEngine engine = createSSLEngine();
                    int appBufferSize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufferHandler = new NioBufferHandler(
                            Math.max(appBufferSize, socketProperties.getAppReadBufSize()),
                            Math.max(appBufferSize, socketProperties.getAppWriteBufSize()),
                            socketProperties.getDirectBuffer());
                    channel = new SecureNioChannel(socket, engine, bufferHandler, selectorPool);
                } else {
                    NioBufferHandler bufferHandler = new NioBufferHandler(socketProperties.getAppReadBufSize(), socketProperties.getAppWriteBufSize(), socketProperties.getDirectBuffer());
                    channel = new NioChannel(socket, bufferHandler);
                }
            } else {
                channel.setIOChannel(socket);
                if (channel instanceof SecureNioChannel) {
                    SSLEngine engine = createSSLEngine();
                    ((SecureNioChannel) channel).reset(engine);
                } else {
                    channel.reset();
                }
            }
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("", t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(t);
            }
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        String clientAuth = getClientAuth();
        switch (clientAuth) {
            case "false":
                engine.setNeedClientAuth(false);
                engine.setWantClientAuth(false);
                break;
            case "true":
            case "yes":
                engine.setNeedClientAuth(true);
                break;
            case "want":
                engine.setWantClientAuth(true);
                break;
        }
        engine.setUseClientMode(false);
        engine.setEnabledCipherSuites(enabledCiphers);
        engine.setEnabledProtocols(enabledProtocols);

        configureUseServerCipherSuitesOrder(engine);

        return engine;
    }

    protected boolean isWorkerAvailable() {
        return true;
    }

    @Override
    public void processSocket(SocketWrapper<NioChannel> socketWrapper, SocketStatus socketStatus, boolean dispatch) {
        processSocket((KeyAttachment) socketWrapper, socketStatus, dispatch);
    }

    protected boolean processSocket(KeyAttachment attachment, SocketStatus status, boolean dispatch) {
        try {
            if (attachment == null) {
                return false;
            }
            SocketProcessor sc = processorCache.pop();
            if (sc == null) {
                sc = new SocketProcessor(attachment, status);
            } else {
                sc.reset(attachment, status);
            }
            Executor executor = getExecutor();
            if (dispatch && executor != null) {
                executor.execute(sc);
            } else {
                sc.run();
            }
        } catch (RejectedExecutionException ree) {
            log.warn(sm.getString("endpoint.executor.fail", attachment.getSocket()), ree);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    @Override
    protected Log getLog() {
        return log;
    }

    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {
            int errorDelay = 0;
            while (running) {
                restAcceptor();
                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;
                try {
                    countUpOrAwaitConnection();
                    SocketChannel socket;
                    try {
                        SystemUtil.logInfo(this,serverSock.getLocalAddress().toString(),String.valueOf(System.currentTimeMillis()));
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        errorDelay = cleanAccept(errorDelay);
                        throw ioe;
                    }
                    errorDelay = 0;
                    loadingSocketChannel(socket);
                } catch (SocketTimeoutException ignored) {
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (OutOfMemoryError oom) {
                    cleanOom(oom);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }

        private int cleanAccept(int errorDelay) {
            countDownConnection();
            errorDelay = handleExceptionWithDelay(errorDelay);
            return errorDelay;
        }

        private void cleanOom(OutOfMemoryError oom) {
            try {
                oomParachuteData = null;
                releaseCaches();
                log.error("", oom);
            } catch (Throwable oomt) {
                try {
                    try {
                        System.err.println(oomParachuteMsg);
                        oomt.printStackTrace();
                    } catch (Throwable letsHopeWeDontGetHere) {
                        ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                    }
                } catch (Throwable letsHopeWeDontGetHere) {
                    ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                }
            }
        }

        private void loadingSocketChannel(SocketChannel socketChannel) {
            SystemUtil.logInfo(this,"开始封装SocketChannel为NioChannel对象");
            if (running && !paused) {
                if (!setSocketOptions(socketChannel)) {
                    cleanSocketChannel(socketChannel);
                }
            } else {
                cleanSocketChannel(socketChannel);
            }
        }

        private void cleanSocketChannel(SocketChannel socket) {
            countDownConnection();
            closeSocket(socket);
        }

        private void restAcceptor() {
            SystemUtil.logInfo(this, "查看paused的值:", String.valueOf(paused));
            while (paused && running) {
                state = AcceptorState.PAUSED;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private void closeSocket(SocketChannel socket) {
        try {
            socket.socket().close();
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
    }

    /**
     * 这个类就是一个事件类，然后是register到Selector上面去
     */
    public static class PollerEvent implements Runnable {
        private NioChannel socket;
        private int interestOps;
        private KeyAttachment key;

        public PollerEvent(NioChannel ch, KeyAttachment k, int intOps) {
            reset(ch, k, intOps);
        }

        public void reset(NioChannel ch, KeyAttachment k, int intOps) {
            socket = ch;
            interestOps = intOps;
            key = k;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        public void run() {
            if (interestOps == OP_REGISTER) {
                try {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                } catch (Exception x) {
                    log.error("", x);
                }
            } else {
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    boolean cancel = false;
                    if (key != null) {
                        final KeyAttachment att = (KeyAttachment) key.attachment();
                        if (att != null) {
                            att.access();
                            int ops = key.interestOps() | interestOps;
                            att.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            cancel = true;
                        }
                    } else {
                        cancel = true;
                    }
                    if (cancel) {
                        socket.getPoller().cancelledKey(key, SocketStatus.ERROR);
                    }
                } catch (CancelledKeyException ckx) {
                    socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT);
                }
            }
        }

        @Override
        public String toString() {
            return super.toString() + "[intOps=" + this.interestOps + "]";
        }
    }

    public class Poller implements Runnable {
        private Selector selector;
        private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>();
        private volatile boolean close = false;
        private long nextExpiration = 0;
        private AtomicLong wakeupCounter = new AtomicLong(0);
        private volatile int keyCount = 0;

        public Poller() throws IOException {
            synchronized (Selector.class) {
                this.selector = Selector.open();
            }
        }

        public int getKeyCount() {
            return keyCount;
        }

        public Selector getSelector() {
            return selector;
        }

        protected void destroy() {
            close = true;
            selector.wakeup();
        }

        private void addEvent(PollerEvent event) {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0) {
                selector.wakeup();
            }
        }

        public void add(final NioChannel socket) {
            add(socket, SelectionKey.OP_READ);
        }

        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if (r == null) {
                r = new PollerEvent(socket, null, interestOps);
            }
            else {
                r.reset(socket, null, interestOps);
            }
            addEvent(r);
            if (close) {
                NioEndpoint.KeyAttachment ka = (NioEndpoint.KeyAttachment) socket.getAttachment();
                processSocket(ka, SocketStatus.STOP, false);
            }
        }

        public boolean events() {
            boolean result = false;
            PollerEvent pe;
            while ((pe = events.poll()) != null) {
                result = true;
                try {
                    pe.run();
                    pe.reset();
                    if (running && !paused) {
                        eventCache.push(pe);
                    }
                } catch (Throwable x) {
                    log.error(x.getMessage(), x);
                }
            }

            return result;
        }

        public void register(final NioChannel socket) {
            socket.setPoller(this);
            KeyAttachment ka = new KeyAttachment(socket);
            ka.setPoller(this);
            ka.setTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            PollerEvent r = eventCache.pop();
            ka.interestOps(SelectionKey.OP_READ);
            if (r == null) {
                r = new PollerEvent(socket, ka, OP_REGISTER);
            } else {
                r.reset(socket, ka, OP_REGISTER);
            }
            addEvent(r);
        }

        public KeyAttachment cancelledKey(SelectionKey key, SocketStatus status) {
            KeyAttachment ka = null;
            try {
                if (key == null)
                    return null;
                ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.isComet() && status != null) {
                    ka.setComet(false);
                    if (status == SocketStatus.TIMEOUT) {
                        if (processSocket(ka, status, true)) {
                            return null;
                        }
                    } else {
                        processSocket(ka, status, false);
                    }
                }
                ka = (KeyAttachment) key.attach(null);
                if (ka != null)
                    handler.release(ka);
                else
                    handler.release((SocketChannel) key.channel());
                if (key.isValid())
                    key.cancel();
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null) {
                        ka.getSocket().close(true);
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("endpoint.debug.socketCloseFail"), e);
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled())
                    log.error("", e);
            }
            return ka;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    restPoller();
                    boolean hasEvents;
                    if (close) {
                        closeClean();
                        break;
                    } else {
                        hasEvents = events();
                    }
                    try {
                        if (!close) {
                            if (wakeupCounter.getAndSet(-1) > 0) {
                                keyCount = selector.selectNow();
                            } else {
                                keyCount = selector.select(selectorTimeout);
                            }
                            wakeupCounter.set(0);
                        }
                        if (close) {
                            closeClean();
                            break;
                        }
                    } catch (Throwable x) {
                        ExceptionUtils.handleThrowable(x);
                        log.error(x.getMessage(), x);
                        continue;
                    }
                    if (keyCount == 0) {
                        hasEvents = (hasEvents | events());
                    }
                    connectorSelection();
                    timeout(keyCount, hasEvents);
                    if (oomParachute > 0 && oomParachuteData == null) {
                        checkParachute();
                    }
                } catch (OutOfMemoryError oom) {
                    cleanMemory(oom);
                }
            }

            stopLatch.countDown();
        }

        private void closeClean() {
            events();
            timeout(0, false);
            try {
                selector.close();
            } catch (IOException ioe) {
                log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
            }
        }

        private void connectorSelection() {
            Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
            while (iterator != null && iterator.hasNext()) {
                SelectionKey sk = iterator.next();
                KeyAttachment attachment = (KeyAttachment) sk.attachment();
                if (attachment == null) {
                    iterator.remove();
                } else {
                    attachment.access();
                    iterator.remove();
                    processKey(sk, attachment);
                }
            }
        }

        private void cleanMemory(OutOfMemoryError oom) {
            try {
                oomParachuteData = null;
                releaseCaches();
                log.error("", oom);
            } catch (Throwable oomt) {
                try {
                    System.err.println(oomParachuteMsg);
                    oomt.printStackTrace();
                } catch (Throwable letsHopeWeDontGetHere) {
                    ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                }
            }
        }

        private void restPoller() {
            while (paused && (!close)) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.info("我被打断了.", e);
                }
            }
        }

        protected boolean processKey(SelectionKey sk, KeyAttachment attachment) {
            boolean result = true;
            try {
                if (close) {
                    cancelledKey(sk, SocketStatus.STOP);
                } else if (sk.isValid() && attachment != null) {
                    attachment.access();
                    if (sk.isReadable() || sk.isWritable()) {
                        if (attachment.getSendfileData() != null) {
                            processSendfile(sk, attachment, false);
                        } else {
                            if (isWorkerAvailable()) {
                                unreg(sk, attachment, sk.readyOps());
                                boolean closeSocket = false;
                                if (sk.isReadable()) {
                                    closeSocket = !processSocket(attachment, SocketStatus.OPEN_READ, true);
                                }
                                if (!closeSocket && sk.isWritable()) {
                                    closeSocket = !processSocket(attachment, SocketStatus.OPEN_WRITE, true);
                                }
                                if (closeSocket) {
                                    cancelledKey(sk, SocketStatus.DISCONNECT);
                                }
                            } else {
                                result = false;
                            }
                        }
                    }
                } else {
                    cancelledKey(sk, SocketStatus.ERROR);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk, SocketStatus.ERROR);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("", t);
            }
            return result;
        }

        public boolean processSendfile(SelectionKey sk, KeyAttachment attachment, boolean event) {
            NioChannel sc = null;
            try {
                unreg(sk, attachment, sk.readyOps());
                SendfileData sd = attachment.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    File f = new File(sd.fileName);
                    if (!f.exists()) {
                        cancelledKey(sk, SocketStatus.ERROR);
                        return false;
                    }
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                sc = attachment.getSocket();
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        attachment.access();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        attachment.access();
                    } else {
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " + "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    attachment.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    if (sd.keepAlive) {
                        if (log.isDebugEnabled()) {
                            log.debug("Connection is keep alive, registering back for OP_READ");
                        }
                        if (event) {
                            this.add(attachment.getSocket(), SelectionKey.OP_READ);
                        } else {
                            reg(sk, attachment, SelectionKey.OP_READ);
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Send file connection is being closed");
                        }
                        cancelledKey(sk, SocketStatus.STOP);
                        return false;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (event) {
                        add(attachment.getSocket(), SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, attachment, SelectionKey.OP_WRITE);
                    }
                }
            } catch (IOException x) {
                if (log.isDebugEnabled())
                    log.debug("Unable to complete sendfile request:", x);
                cancelledKey(sk, SocketStatus.ERROR);
                return false;
            } catch (Throwable t) {
                log.error("", t);
                cancelledKey(sk, SocketStatus.ERROR);
                return false;
            }
            return true;
        }

        protected void unreg(SelectionKey sk, KeyAttachment attachment, int readyOps) {
            reg(sk, attachment, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            try {
                for (Iterator<SelectionKey> iterator = keys.iterator(); iterator.hasNext(); ) {
                    SelectionKey key = iterator.next();
                    keycount++;
                    try {
                        KeyAttachment ka = (KeyAttachment) key.attachment();
                        if (ka == null) {
                            cancelledKey(key, SocketStatus.ERROR); // we don't
                        } else if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ
                                || (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            long delta = now - ka.getLastAccess();
                            long timeout = ka.getTimeout();
                            boolean isTimedout = timeout > 0 && delta > timeout;
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0);
                                processKey(key, ka);
                            } else if (isTimedout) {
                                key.interestOps(0);
                                ka.interestOps(0);
                                cancelledKey(key, SocketStatus.TIMEOUT);
                            }
                        } else if (ka.isAsync() || ka.isComet()) {
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0);
                                processKey(key, ka);
                            } else if (!ka.isAsync() || ka.getTimeout() > 0) {
                                long delta = now - ka.getLastAccess();
                                long timeout = (ka.getTimeout() == -1) ? ((long) socketProperties.getSoTimeout()) : (ka.getTimeout());
                                boolean isTimeOut = delta > timeout;
                                if (isTimeOut) {
                                    ka.access(Long.MAX_VALUE);
                                    processSocket(ka, SocketStatus.TIMEOUT, true);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key, SocketStatus.ERROR);
                    }
                }
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration;
            nextExpiration = System.currentTimeMillis() + socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount + "; now=" + now + "; nextExpiration="
                        + prevExp + "; keyCount=" + keyCount + "; hasEvents=" + hasEvents + "; eval="
                        + ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));
            }

        }
    }

    public static class KeyAttachment extends SocketWrapper<NioChannel> {
        private Poller poller = null;
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private long writeTimeout = -1;

        public KeyAttachment(NioChannel channel) {
            super(channel);
        }

        public Poller getPoller() {
            return poller;
        }

        public void setPoller(Poller poller) {
            this.poller = poller;
        }

        @Deprecated
        public int interestOps() {
            return interestOps;
        }

        public int interestOps(int ops) {
            this.interestOps = ops;
            return ops;
        }

        public CountDownLatch getReadLatch() {
            return readLatch;
        }

        public CountDownLatch getWriteLatch() {
            return writeLatch;
        }

        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0)
                return null;
            else
                throw new IllegalStateException("Latch must be at count 0");
        }

        public void resetReadLatch() {
            readLatch = resetLatch(readLatch);
        }

        public void resetWriteLatch() {
            writeLatch = resetLatch(writeLatch);
        }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else
                throw new IllegalStateException("Latch must be at count 0 or null.");
        }

        public void startReadLatch(int cnt) {
            readLatch = startLatch(readLatch, cnt);
        }

        public void startWriteLatch(int cnt) {
            writeLatch = startLatch(writeLatch, cnt);
        }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null)
                throw new IllegalStateException("Latch cannot be null");
            latch.await(timeout, unit);
        }

        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(readLatch, timeout, unit);
        }

        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException {
            awaitLatch(writeLatch, timeout, unit);
        }

        public void setSendfileData(SendfileData sf) {
            this.sendfileData = sf;
        }

        public SendfileData getSendfileData() {
            return this.sendfileData;
        }

        public void setWriteTimeout(long writeTimeout) {
            this.writeTimeout = writeTimeout;
        }

        public long getWriteTimeout() {
            return this.writeTimeout;
        }
    }

    public static class NioBufferHandler implements ApplicationBufferHandler {
        private ByteBuffer readBuffer = null;
        private ByteBuffer writeBuffer = null;

        public NioBufferHandler(int readSize, int writeSize, boolean direct) {
            if (direct) {
                readBuffer = ByteBuffer.allocateDirect(readSize);
                writeBuffer = ByteBuffer.allocateDirect(writeSize);
            } else {
                readBuffer = ByteBuffer.allocate(readSize);
                writeBuffer = ByteBuffer.allocate(writeSize);
            }
        }

        @Override
        public ByteBuffer expand(ByteBuffer buffer, int remaining) {
            return buffer;
        }

        @Override
        public ByteBuffer getReadBuffer() {
            return readBuffer;
        }

        @Override
        public ByteBuffer getWriteBuffer() {
            return writeBuffer;
        }

    }

    public interface Handler extends AbstractEndpoint.Handler {
        SocketState process(SocketWrapper<NioChannel> socket, SocketStatus status);

        void release(SocketWrapper<NioChannel> socket);

        void release(SocketChannel socket);

        SSLImplementation getSslImplementation();
    }

    protected class SocketProcessor implements Runnable {

        private KeyAttachment ka = null;
        private SocketStatus status = null;

        public SocketProcessor(KeyAttachment ka, SocketStatus status) {
            reset(ka, status);
        }

        public void reset(KeyAttachment ka, SocketStatus status) {
            this.ka = ka;
            this.status = status;
        }

        @Override
        public void run() {
            NioChannel socket = ka.getSocket();
            if (ka.isUpgraded() && SocketStatus.OPEN_WRITE == status) {
                synchronized (ka.getWriteThreadLock()) {
                    doRun();
                }
            } else {
                synchronized (socket) {
                    doRun();
                }
            }
        }

        private void doRun() {
            NioChannel socket = ka.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
            try {
                int handshake = -1;
                handshake = identificationHandshake(socket, key, handshake);
                switch (handshake) {
                    case 0:
                        SocketState state;
                        if (status == null) {
                            state = handler.process(ka, SocketStatus.OPEN_READ);
                        } else {
                            state = handler.process(ka, status);
                        }
                        if (state == SocketState.CLOSED) {
                            close(socket, key, SocketStatus.ERROR);
                        }
                        break;
                    case -1:
                        close(socket, key, SocketStatus.DISCONNECT);
                        break;
                    default:
                        ka.getPoller().add(socket, handshake);
                        break;
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key, null);
            } catch (OutOfMemoryError oom) {
                cleanOom(socket, key, oom);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key, SocketStatus.ERROR);
            } finally {
                cleanFinal();
            }
        }

        private void cleanFinal() {
            ka = null;
            status = null;
            if (running && !paused) {
                processorCache.push(this);
            }
        }

        private void cleanOom(NioChannel socket, SelectionKey key, OutOfMemoryError oom) {
            try {
                oomParachuteData = null;
                log.error("", oom);
                socket.getPoller().cancelledKey(key, SocketStatus.ERROR);
                releaseCaches();
            } catch (Throwable oomt) {
                try {
                    System.err.println(oomParachuteMsg);
                    oomt.printStackTrace();
                } catch (Throwable letsHopeWeDontGetHere) {
                    ExceptionUtils.handleThrowable(letsHopeWeDontGetHere);
                }
            }
        }

        private int identificationHandshake(NioChannel socket, SelectionKey key, int handshake) {
            try {
                if (key != null) {
                    if (socket.isHandshakeComplete() || status == SocketStatus.STOP) {
                        handshake = 0;
                    } else {
                        handshake = socket.handshake(key.isReadable(), key.isWritable());
                        status = SocketStatus.OPEN_READ;
                    }
                }
            } catch (IOException x) {
                handshake = -1;
                if (log.isDebugEnabled()) {
                    log.debug("Error during SSL handshake", x);
                }
            } catch (CancelledKeyException ckx) {
                handshake = -1;
            }
            return handshake;
        }

        private void close(NioChannel socket, SelectionKey key, SocketStatus socketStatus) {
            try {
                ka.setComet(false);
                if (socket.getPoller().cancelledKey(key, socketStatus) != null) {
                    if (running && !paused) {
                        nioChannels.push(socket);
                    }
                }
            } catch (Exception x) {
                log.error("", x);
            }
        }
    }

    public static class SendfileData {
        public volatile String fileName;
        public volatile FileChannel fchannel;
        public volatile long pos;
        public volatile long length;
        public volatile boolean keepAlive;
    }
}
