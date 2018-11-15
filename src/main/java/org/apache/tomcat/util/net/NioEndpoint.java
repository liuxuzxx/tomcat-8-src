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

import com.alibaba.fastjson.JSONObject;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p>
 * When switching to Java 5, there's an opportunity to use the virtual machine's
 * thread pool.
 * 本来还是用JVM自带的虚拟机好，总是版本的适应和兼容让代码和逻辑变得很臃肿
 * 这个Nio，我是略微的了解一下就是这个：channel<----->buffer的一个交流，其实就是io的一个使用，但是
 * 二者的不同点就是Nio是不堵塞的，但是io是堵塞的
 * 好像是并没有使用什么Http11EndPoint这个实现类，就是使用了这个类，但是也没有给出来什么提示
 * 如果按照我们的想法应该是Http11EndPoint：但是为什么不使用啊，这个是一个问题
 */
public class NioEndpoint extends AbstractEndpoint<NioChannel> {
    private static final Log log = LogFactory.getLog(NioEndpoint.class);
    public static final int OP_REGISTER = 0x100;
    private NioSelectorPool selectorPool = new NioSelectorPool();
    private ServerSocketChannel serverSock = null;
    private boolean useSendfile = true;
    private int oomParachute = 1024 * 1024;
    private byte[] oomParachuteData = null;
    private static final String oomParachuteMsg = "SEVERE:内存不够用, parachute is non existent, 系统启动失败.";
    private long lastParachuteCheck = System.currentTimeMillis();
    private volatile CountDownLatch stopLatch = null;
    private SynchronizedStack<SocketProcessor> processorCache;
    private SynchronizedStack<PollerEvent> eventCache;
    private SynchronizedStack<NioChannel> nioChannels;

    @Override
    public boolean setProperty(String name, String value) {
        /**
         * 方法内部使用final的方式很少见，估计这个作者是c++的程序员转换过来的 就是不让你改变这个字符串对象的吧
         * 我们也是可以经常的使用这种方式的，这样子最起码可以让我们避免一定的错误吧 其实c++的一些个思想是可以借鉴过来的
         */
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

    private int pollerThreadPriority = Thread.NORM_PRIORITY;

    public void setPollerThreadPriority(int pollerThreadPriority) {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPollerThreadPriority() {
        return pollerThreadPriority;
    }

    private Handler handler = null;

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public Handler getHandler() {
        return handler;
    }

    private boolean useComet = true;

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
    } // Always supported

    private int pollerThreadCount = Math.min(2, Runtime.getRuntime().availableProcessors());

    public void setPollerThreadCount(int pollerThreadCount) {
        this.pollerThreadCount = pollerThreadCount;
    }

    public int getPollerThreadCount() {
        return pollerThreadCount;
    }

    private long selectorTimeout = 1000;

    public void setSelectorTimeout(long timeout) {
        this.selectorTimeout = timeout;
    }

    public long getSelectorTimeout() {
        return this.selectorTimeout;
    }

    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);

    /**
     * 这种轮询方式不就是一种最简单的hash算法嘛，使用abs是必要的，因为当轮询次数多了，
     * 很可能出现负数
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
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

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }

    public void setOomParachute(int oomParachute) {
        this.oomParachute = oomParachute;
    }

    public void setOomParachuteData(byte[] oomParachuteData) {
        this.oomParachuteData = oomParachuteData;
    }

    private SSLContext sslContext = null;

    public SSLContext getSSLContext() {
        return sslContext;
    }

    public void setSSLContext(SSLContext c) {
        sslContext = c;
    }

    private String[] enabledCiphers;
    private String[] enabledProtocols;

    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        /**
         * 不明白的是，作者为什么做出来一个副本的对象啊，这不是扯淡吗 你又没有改变这个东西啊，真是不明白，是不是故意部迷阵
         * 减少getfield的操作
         */
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
            int sum = 0;
            for (int i = 0; i < pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }

    @Override
    public void bind() throws Exception {
        SystemUtil.logInfo(this, "开始初始化ServerSocketChannel对象，以及初始化对应的服务线程进行服务...");
        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress address = (getAddress() != null ? new InetSocketAddress(getAddress(), getPort())
                : new InetSocketAddress(getPort()));
        SystemUtil.logInfo("IP地址是：", address.getHostName(), " Port是：", address.getPort() + "");
        serverSock.socket().bind(address, getBacklog());
        serverSock.configureBlocking(true); // mimic APR behavior
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept
            // threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            // minimum one poller thread
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        if (isSSLEnabled()) {
            SSLUtil sslUtil = handler.getSslImplementation().getSSLUtil(this);

            sslContext = sslUtil.createSSLContext();
            sslContext.init(wrap(sslUtil.getKeyManagers()), sslUtil.getTrustManagers(), null);

            SSLSessionContext sessionContext = sslContext.getServerSessionContext();
            if (sessionContext != null) {
                sslUtil.configureSessionContext(sessionContext);
            }
            // Determine which cipher suites and protocols to enable
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }

        if (oomParachute > 0)
            reclaimParachute(true);
        selectorPool.open();
    }

    public KeyManager[] wrap(KeyManager[] managers) {
        if (managers == null)
            return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i = 0; i < result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias() != null) {
                String keyAlias = getKeyAlias();
                // JKS keystores always convert the alias name to lower case
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

            // Create worker collection
            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch();

            // Start poller threads
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

    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
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
                if (pollers[i] == null)
                    continue;
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
        // Close server socket
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
        // Process the connection
        try {
            // disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.pop();
            SystemUtil.logInfo(this, "从池子里面拿到的channel的引用是：", channel == null ? null : channel.toString());
            if (channel == null) {
                // SSL setup
                SystemUtil.logInfo(this, "查看SSL上下文的信息内容：", JSONObject.toJSONString(sslContext));
                if (sslContext != null) {
                    SSLEngine engine = createSSLEngine();
                    int appBufferSize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufferHandler = new NioBufferHandler(
                            Math.max(appBufferSize, socketProperties.getAppReadBufSize()),
                            Math.max(appBufferSize, socketProperties.getAppWriteBufSize()),
                            socketProperties.getDirectBuffer());
                    channel = new SecureNioChannel(socket, engine, bufferHandler, selectorPool);
                } else {
                    // normal tcp setup
                    NioBufferHandler bufferHandler = new NioBufferHandler(socketProperties.getAppReadBufSize(),
                            socketProperties.getAppWriteBufSize(), socketProperties.getDirectBuffer());

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
            /**
             * 这句话通过获取Poller，然后就给注册进去了，现在看起来多么的自然的一段代码啊
             */
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("", t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(t);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        if ("false".equals(getClientAuth())) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth())) {
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth())) {
            engine.setWantClientAuth(true);
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
            if (sc == null)
                sc = new SocketProcessor(attachment, status);
            else
                sc.reset(attachment, status);
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
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    @Override
    protected Log getLog() {
        return log;
    }

    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     * 其实就是这个acceptor进行一个socket的监听和进行一个接待，好像是只有接待，好像是门前的服务员一样子
     * 只会说一个：欢迎光临，里面请，但是具体的菜单吃饭是有这个具体的人员负责的，这个叫做社会分工明确，没有什么 拖泥带水的东西
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {
            SystemUtil.printInfo(this, "Acceptor线程启动了，可以接受客户端的请求了");
            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {
                restAcceptor();

                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    // if we have reached max connections, wait
                    countUpOrAwaitConnection();

                    SocketChannel socket;
                    try {
                        /**
                         * 终于看到了服务端接受到前端的请求了，就是这个地方，找到了！！！
                         * 通篇来看，tomcat就使用了一个ServerSocketChannel
                         * 不过话又说回来了，也造不出来大于一个的ServerSocketChannel啊
                         */
                        socket = serverSock.accept();
                        SystemUtil.logInfo(this, "接受了一个客户端的请求......");
                    } catch (IOException ioe) {
                        errorDelay = cleanAccept(errorDelay);

                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    /**
                     * 根本上说：这个地方才是服务端接受到客户端的请求之后，然后，获取SocketChannel
                     * 之后，交给Poller处理，Poller会交给PollerEvent处理，PollerEvent会把这个SocketChannel
                     * 装填到selector当中去，当然这个selector是Poller的成员变量
                     */
                    loadingSocketChannel(socket);

                } catch (SocketTimeoutException sx) {
                    // Ignore: Normal condition
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                    /**
                     * 从Throwable之中实现的中有两种：Exception、Error，这个就是很少见的错误
                     */
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
            /**
             * 他们的异常抓住之后总是做出一点操作，而不是抛射出去这么的简单，
             * 就是做点事情呗
             */
            // we didn't get a socket
            countDownConnection();
            // Introduce delay if necessary
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

        /**
         * 装填服务端获取的SocketChannel到Selector当中去
         *
         * @param socketChannel 需要装填的与客户端打交道的channel
         */
        private void loadingSocketChannel(SocketChannel socketChannel) {
            // setSocketOptions() will add channel to the poller
            // if successful
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
            // Loop if endpoint is paused
            SystemUtil.printInfo(this, "一直运行，等待客户端的访问");
            while (paused && running) {
                state = AcceptorState.PAUSED;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    /**
                     * 其实很多的异常是不用问的，因为，就算是你知道地球明天就要灭亡，你又能做什么尼
                     * 除了面对这个现实之外，你别无选择了，只能冷静的对待了，其实你是没有能力进行拯救地球的
                     * 所以，很多的异常信息，你就算知道了，那又怎么样子，只能眼睁睁的看着了
                     */
                }
            }
        }
    }

    private void closeSocket(SocketChannel socket) {
        try {
            socket.socket().close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug("", ioe);
            }
        }
    }

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     * 看PollerEvent，也就是这个循环事件的class，好像只是就是给Poller使用的，没看到其他地方使用这个类
     * 其实从名字也能看出来，这个就是从属于Poller的Event
     * 但是一个奇怪的事情就是，这个PollerEvent虽然是实现了Runnable的接口
     * 但是并没有去开启一个线程，而是按部就班的调用run方法，不知道作者的意思是什么
     * 我怀疑作者是打算做成一个线程运行，但是后面估计是不需要，也就给使用run直接调用了
     * 看着很奇怪的感觉
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

        /**
         * 今天的第一个目标完成了，就是找到了注册SocketChannel到Selector当中的语句调用
         */
        @Override
        public void run() {
            if (interestOps == OP_REGISTER) {
                try {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                } catch (Exception x) {
                    log.error("", x);
                }
            } else {
                SystemUtil.logInfo(this, "查看一下是否会有不是注册的情况");
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    boolean cancel = false;
                    if (key != null) {
                        final KeyAttachment att = (KeyAttachment) key.attachment();
                        if (att != null) {
                            SystemUtil.logInfo(this, "查看一下KeyAttachment否为null");
                            att.access();// to prevent timeout
                            // we are registering the key to start with, reset
                            // the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            att.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            cancel = true;
                        }
                    } else {
                        cancel = true;
                    }
                    if (cancel)
                        socket.getPoller().cancelledKey(key, SocketStatus.ERROR);
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
        private long nextExpiration = 0;// optimize expiration handling

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

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller
            // threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        private void addEvent(PollerEvent event) {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0)
                selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket
         * will be added to a temporary array, and polled first after a maximum
         * amount of time equal to pollTime (in most cases, latency will be much
         * lower, however).
         *
         * @param socket to add to the poller
         */
        public void add(final NioChannel socket) {
            add(socket, SelectionKey.OP_READ);
        }

        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if (r == null)
                r = new PollerEvent(socket, null, interestOps);
            else
                r.reset(socket, null, interestOps);
            addEvent(r);
            if (close) {
                NioEndpoint.KeyAttachment ka = (NioEndpoint.KeyAttachment) socket.getAttachment();
                processSocket(ka, SocketStatus.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         * <code>false</code> if queue was empty
         */
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
                    log.error("", x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket The newly created socket
         */
        public void register(final NioChannel socket) {
            socket.setPoller(this);
            KeyAttachment ka = new KeyAttachment(socket);
            ka.setPoller(this);
            ka.setTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            PollerEvent r = eventCache.pop();
            ka.interestOps(SelectionKey.OP_READ);// this is what OP_REGISTER
            // turns into.
            if (r == null)
                r = new PollerEvent(socket, ka, OP_REGISTER);
            else
                r.reset(socket, ka, OP_REGISTER);
            addEvent(r);
        }

        public KeyAttachment cancelledKey(SelectionKey key, SocketStatus status) {
            KeyAttachment ka = null;
            try {
                if (key == null)
                    return null;// nothing to do
                ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.isComet() && status != null) {
                    ka.setComet(false);// to avoid a loop
                    if (status == SocketStatus.TIMEOUT) {
                        if (processSocket(ka, status, true)) {
                            return null; // don't close on comet timeout
                        }
                    } else {
                        // Don't dispatch if the lines below are cancelling the
                        // key
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

        /**
         * The background thread that listens for incoming TCP/IP connections
         * and hands them off to an appropriate processor.
         * 主要是接受到来自外界的TCP请求，然后转交给Processor进行处理
         * 我觉得这个run函数的代码写的真是乱极了，并不是逻辑有多么的复杂，而是没有拆分成几个函数
         * 这里面，也许作者考虑的是：我这个函数是要使用很长时间，并且时经常使用，所有为了减少函数调用
         * 的时候，进行上下文的栈数据切换，只有都写到一个函数里面了，我觉得只有这么一个原因可以解释
         */
        @Override
        public void run() {
            SystemUtil.logInfo(this, "Poller", "轮循开始...");
            while (true) {
                try {
                    restPoller();

                    boolean hasEvents = false;

                    // Time to terminate?
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
                        log.error("", x);
                        continue;
                    }
                    // either we timed out or we woke up, process events first
                    if (keyCount == 0)
                        hasEvents = (hasEvents | events());

                    connectorSelection();

                    // process timeouts
                    timeout(keyCount, hasEvents);
                    if (oomParachute > 0 && oomParachuteData == null)
                        checkParachute();
                } catch (OutOfMemoryError oom) {
                    cleanMemory(oom);
                }
            } // while

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
            // Walk through the collection of ready keys and dispatch
            // any active event.
            while (iterator != null && iterator.hasNext()) {
                SelectionKey sk = iterator.next();
                KeyAttachment attachment = (KeyAttachment) sk.attachment();
                // Attachment may be null if another thread has called
                // cancelledKey()
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
                    SystemUtil.logInfo(this, "睡眠了100ms", new Boolean(close).toString());
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
                    attachment.access();// make sure we don't time out valid
                    // sockets
                    if (sk.isReadable() || sk.isWritable()) {
                        SystemUtil.logInfo(this, "SelectionKey可以读也可以写:read", "" + sk.isReadable(), " write:", "" + sk.isWritable());
                        if (attachment.getSendfileData() != null) {
                            SystemUtil.logInfo(this, "获取的SendDataFile对象不为null");
                            processSendfile(sk, attachment, false);
                        } else {
                            if (isWorkerAvailable()) {
                                unreg(sk, attachment, sk.readyOps());
                                boolean closeSocket = false;
                                if (sk.isReadable()) {
                                    SystemUtil.logInfo(this, "可读取状态，下面进行读取..");
                                    closeSocket = !processSocket(attachment, SocketStatus.OPEN_READ, true);
                                }
                                if (!closeSocket && sk.isWritable()) {
                                    SystemUtil.logInfo(this, "可写入状态，下面进行写入操作。。。");
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
                    // invalid key
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

                // setup the file channel
                if (sd.fchannel == null) {
                    File f = new File(sd.fileName);
                    if (!f.exists()) {
                        cancelledKey(sk, SocketStatus.ERROR);
                        return false;
                    }
                    @SuppressWarnings("resource") // Closed when channel is
                            // closed
                            FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // configure output channel
                sc = attachment.getSocket();
                // ssl channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // we still have data in the buffer
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
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
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
            // this is a must, so that we don't have multiple threads messing
            // with the socket
            reg(sk, attachment, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            // timeout
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            try {
                for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext(); ) {
                    SelectionKey key = iter.next();
                    keycount++;
                    try {
                        KeyAttachment ka = (KeyAttachment) key.attachment();
                        if (ka == null) {
                            cancelledKey(key, SocketStatus.ERROR); // we don't
                            // support
                            // any keys
                            // without
                            // attachments
                        } else if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ
                                || (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            // only timeout sockets that we are waiting for a
                            // read from
                            long delta = now - ka.getLastAccess();
                            long timeout = ka.getTimeout();
                            boolean isTimedout = timeout > 0 && delta > timeout;
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0); // avoid duplicate stop calls
                                processKey(key, ka);
                            } else if (isTimedout) {
                                key.interestOps(0);
                                ka.interestOps(0); // avoid duplicate timeout
                                // calls
                                cancelledKey(key, SocketStatus.TIMEOUT);
                            }
                        } else if (ka.isAsync() || ka.isComet()) {
                            if (close) {
                                key.interestOps(0);
                                ka.interestOps(0); // avoid duplicate stop calls
                                processKey(key, ka);
                            } else if (!ka.isAsync() || ka.getTimeout() > 0) {
                                // Async requests with a timeout of 0 or less
                                // never timeout
                                long delta = now - ka.getLastAccess();
                                long timeout = (ka.getTimeout() == -1) ? ((long) socketProperties.getSoTimeout())
                                        : (ka.getTimeout());
                                boolean isTimedout = delta > timeout;
                                if (isTimedout) {
                                    // Prevent subsequent timeouts if the
                                    // timeout event takes a while to process
                                    ka.access(Long.MAX_VALUE);
                                    processSocket(ka, SocketStatus.TIMEOUT, true);
                                }
                            }
                        } // end if
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key, SocketStatus.ERROR);
                    }
                } // for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; // for logging purposes only
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

        /*
         * @Deprecated // Unused. NO-OP. Will be removed in Tomcat 9.0.x public
         * void setCometNotify(@SuppressWarnings("unused") boolean notify) { }
         *
         * @Deprecated // Unused. Always returns false. Will be removed in
         * Tomcat // 9.0.x public boolean getCometNotify() { return false; }
         */
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
            // Note: While the return value is ignored if the latch does time
            // out, logic further up the call stack will trigger a
            // SocketTimeoutException
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
        private ByteBuffer readbuf = null;
        private ByteBuffer writebuf = null;

        public NioBufferHandler(int readsize, int writesize, boolean direct) {
            if (direct) {
                readbuf = ByteBuffer.allocateDirect(readsize);
                writebuf = ByteBuffer.allocateDirect(writesize);
            } else {
                readbuf = ByteBuffer.allocate(readsize);
                writebuf = ByteBuffer.allocate(writesize);
            }
        }

        @Override
        public ByteBuffer expand(ByteBuffer buffer, int remaining) {
            return buffer;
        }

        @Override
        public ByteBuffer getReadBuffer() {
            return readbuf;
        }

        @Override
        public ByteBuffer getWriteBuffer() {
            return writebuf;
        }

    }

    public interface Handler extends AbstractEndpoint.Handler {
        SocketState process(SocketWrapper<NioChannel> socket, SocketStatus status);

        void release(SocketWrapper<NioChannel> socket);

        void release(SocketChannel socket);

        SSLImplementation getSslImplementation();
    }

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     * Socket的处理就是在这个地方进行一个加工处理的过程
     * 这作者是把所有的;Acceptor-->Poller--->PollerEvent-->SocketProcessor都给防止在NioEndpoint这个类当中了，
     * 这样子也好，不用到处来回跳着看class了。
     */
    protected class SocketProcessor implements Runnable {

        private KeyAttachment ka = null;
        private SocketStatus status = null;

        public SocketProcessor(KeyAttachment ka, SocketStatus status) {
            /**
             * 不明白为什么把这个进行一个属性的赋值的时候会出现一个方法的间接的调用操作，不知道是为什么 是不是作者就是故意的啊
             */
            reset(ka, status);
        }

        public void reset(KeyAttachment ka, SocketStatus status) {
            this.ka = ka;
            this.status = status;
        }

        /**
         * 终于找到了到底是谁处理这个socket了，原来是这个NioEndPoint处理的，让我好好看看这个类
         */
        @Override
        public void run() {
            SystemUtil.logInfo(this, "接受socket的一个处理工作");
            NioChannel socket = ka.getSocket();

            // Upgraded connections need to allow multiple threads to access the
            // connection at the same time to enable blocking IO to be used when
            // NIO has been configured
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
            SystemUtil.logInfo(this, "doRun", "真正的开始接受这个浏览器的访问信息数据");
            NioChannel socket = ka.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                handshake = identificationHandshake(socket, key, handshake);
                switch (handshake) {
                    case 0:
                        SocketState state;
                        // Process the request from this socket
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
            // return to cache
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
                    // For STOP there is no point trying to handshake as the
                    // Poller has been stopped.
                    if (socket.isHandshakeComplete() || status == SocketStatus.STOP) {
                        handshake = 0;
                    } else {
                        handshake = socket.handshake(key.isReadable(), key.isWritable());
                        // The handshake process reads/writes from/to the
                        // socket. status may therefore be OPEN_WRITE once
                        // the handshake completes. However, the handshake
                        // happens when the socket is opened so the status
                        // must always be OPEN_READ after it completes. It
                        // is OK to always set this as it is only used if
                        // the handshake completes.
                        status = SocketStatus.OPEN_READ;
                    }
                }
            } catch (IOException x) {
                handshake = -1;
                if (log.isDebugEnabled())
                    log.debug("Error during SSL handshake", x);
            } catch (CancelledKeyException ckx) {
                handshake = -1;
            }
            return handshake;
        }

        private void close(NioChannel socket, SelectionKey key, SocketStatus socketStatus) {
            // Close socket and pool
            try {
                ka.setComet(false);
                if (socket.getPoller().cancelledKey(key, socketStatus) != null) {
                    // SocketWrapper (attachment) was removed from the
                    // key - recycle the key. This can only happen once
                    // per attempted closure so it is used to determine
                    // whether or not to return the key to the cache.
                    // We do NOT want to do this more than once - see BZ
                    // 57340 / 57943.
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
        // File
        public volatile String fileName;
        public volatile FileChannel fchannel;
        public volatile long pos;
        public volatile long length;
        // KeepAlive flag
        public volatile boolean keepAlive;
    }
}
