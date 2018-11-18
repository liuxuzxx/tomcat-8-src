package org.apache.tomcat.util.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import org.apache.tomcat.util.net.NioEndpoint.Poller;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;
import org.lx.tomcat.util.SystemUtil;

public class NioChannel implements ByteChannel {

    protected static final StringManager sm = StringManager.getManager("org.apache.tomcat.util.net.res");
    protected static ByteBuffer emptyBuf = ByteBuffer.allocate(0);
    protected SocketChannel sc = null;
    protected ApplicationBufferHandler bufHandler;
    protected Poller poller;

    public NioChannel(SocketChannel channel, ApplicationBufferHandler bufHandler) {
        this.sc = channel;
        this.bufHandler = bufHandler;
    }

    public void reset() throws IOException {
        bufHandler.getReadBuffer().clear();
        bufHandler.getWriteBuffer().clear();
    }

    public int getBufferSize() {
        if (bufHandler == null)
            return 0;
        int size = 0;
        size += bufHandler.getReadBuffer() != null ? bufHandler.getReadBuffer().capacity() : 0;
        size += bufHandler.getWriteBuffer() != null ? bufHandler.getWriteBuffer().capacity() : 0;
        return size;
    }

    public boolean flush(boolean block, Selector s, long timeout) throws IOException {
        return true;
    }

    @Override
    public void close() throws IOException {
        getIOChannel().socket().close();
        getIOChannel().close();
    }

    public void close(boolean force) throws IOException {
        if (isOpen() || force)
            close();
    }

    @Override
    public boolean isOpen() {
        return sc.isOpen();
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        checkInterruptStatus();
        SystemUtil.logInfo(this, "筚路蓝缕，千里追凶，终于找到第一案发现场，就是这句话，最原始的SocketChannel朝向",
                "客户端写入数据信息，和我的认知一样...");
        return sc.write(src);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return sc.read(dst);
    }

    public Object getAttachment() {
        Poller pol = getPoller();
        Selector sel = pol != null ? pol.getSelector() : null;
        SelectionKey key = sel != null ? getIOChannel().keyFor(sel) : null;
        Object att = key != null ? key.attachment() : null;
        return att;
    }

    public ApplicationBufferHandler getBufHandler() {
        return bufHandler;
    }

    public Poller getPoller() {
        return poller;
    }

    public SocketChannel getIOChannel() {
        return sc;
    }

    public boolean isClosing() {
        return false;
    }

    public boolean isHandshakeComplete() {
        return true;
    }

    public int handshake(boolean read, boolean write) throws IOException {
        return 0;
    }

    public void setPoller(Poller poller) {
        this.poller = poller;
    }

    public void setIOChannel(SocketChannel IOChannel) {
        this.sc = IOChannel;
    }

    @Override
    public String toString() {
        return super.toString() + ":" + this.sc.toString();
    }

    public int getOutboundRemaining() {
        return 0;
    }

    public boolean flushOutbound() throws IOException {
        return false;
    }

    protected void checkInterruptStatus() throws IOException {
        if (Thread.interrupted()) {
            throw new IOException(sm.getString("channel.nio.interrupted"));
        }
    }
}
