package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public class SecureNioChannel extends NioChannel {

    protected static final Log log = LogFactory.getLog(SecureNioChannel.class);
    protected ByteBuffer netInBuffer;
    protected ByteBuffer netOutBuffer;
    protected SSLEngine sslEngine;
    protected boolean handshakeComplete = false;
    protected HandshakeStatus handshakeStatus;
    protected boolean closed = false;
    protected boolean closing = false;
    protected NioSelectorPool pool;

    public SecureNioChannel(SocketChannel channel, SSLEngine engine, ApplicationBufferHandler bufHandler, NioSelectorPool pool) throws IOException {
        super(channel, bufHandler);
        this.sslEngine = engine;
        int appBufSize = sslEngine.getSession().getApplicationBufferSize();
        int netBufSize = sslEngine.getSession().getPacketBufferSize();
        if (netInBuffer == null) netInBuffer = ByteBuffer.allocateDirect(netBufSize);
        if (netOutBuffer == null) netOutBuffer = ByteBuffer.allocateDirect(netBufSize);
        this.pool = pool;
        bufHandler.expand(bufHandler.getReadBuffer(), appBufSize);
        bufHandler.expand(bufHandler.getWriteBuffer(), appBufSize);
        reset();
    }

    public void reset(SSLEngine engine) throws IOException {
        this.sslEngine = engine;
        reset();
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        netOutBuffer.position(0);
        netOutBuffer.limit(0);
        netInBuffer.position(0);
        netInBuffer.limit(0);
        handshakeComplete = false;
        closed = false;
        closing = false;
        sslEngine.beginHandshake();
        handshakeStatus = sslEngine.getHandshakeStatus();
    }

    @Override
    public int getBufferSize() {
        int size = super.getBufferSize();
        size += netInBuffer != null ? netInBuffer.capacity() : 0;
        size += netOutBuffer != null ? netOutBuffer.capacity() : 0;
        return size;
    }

    @Override
    public boolean flush(boolean block, Selector s, long timeout)
            throws IOException {
        if (!block) {
            flush(netOutBuffer);
        } else {
            pool.write(netOutBuffer, this, s, timeout, block);
        }
        return !netOutBuffer.hasRemaining();
    }

    protected boolean flush(ByteBuffer buf) throws IOException {
        int remaining = buf.remaining();
        if (remaining > 0) {
            int written = sc.write(buf);
            return written >= remaining;
        } else {
            return true;
        }
    }

    @Override
    public int handshake(boolean read, boolean write) throws IOException {
        if (handshakeComplete) {
            return 0;
        }
        if (!flush(netOutBuffer)) {
            return SelectionKey.OP_WRITE;
        }
        SSLEngineResult handshake = null;
        while (!handshakeComplete) {
            switch (handshakeStatus) {
                case NOT_HANDSHAKING: {
                    throw new IOException(sm.getString("channel.nio.ssl.notHandshaking"));
                }
                case FINISHED: {
                    handshakeComplete = !netOutBuffer.hasRemaining();
                    return handshakeComplete ? 0 : SelectionKey.OP_WRITE;
                }
                case NEED_WRAP: {
                    try {
                        handshake = handshakeWrap(write);
                    } catch (SSLException e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("channel.nio.ssl.wrapException"), e);
                        }
                        handshake = handshakeWrap(write);
                    }
                    if (handshake.getStatus() == Status.OK) {
                        if (handshakeStatus == HandshakeStatus.NEED_TASK)
                            handshakeStatus = tasks();
                    } else if (handshake.getStatus() == Status.CLOSED) {
                        flush(netOutBuffer);
                        return -1;
                    } else {
                        throw new IOException(sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap", handshake.getStatus()));
                    }
                    if (handshakeStatus != HandshakeStatus.NEED_UNWRAP || (!flush(netOutBuffer))) {
                        return SelectionKey.OP_WRITE;
                    }
                }
                case NEED_UNWRAP: {
                    handshake = handshakeUnwrap(read);
                    if (handshake.getStatus() == Status.OK) {
                        if (handshakeStatus == HandshakeStatus.NEED_TASK)
                            handshakeStatus = tasks();
                    } else if (handshake.getStatus() == Status.BUFFER_UNDERFLOW) {
                        return SelectionKey.OP_READ;
                    } else {
                        throw new IOException(sm.getString("channel.nio.ssl.unexpectedStatusDuringWrap", handshakeStatus));
                    }
                    break;
                }
                case NEED_TASK: {
                    handshakeStatus = tasks();
                    break;
                }
                default:
                    throw new IllegalStateException(sm.getString("channel.nio.ssl.invalidStatus", handshakeStatus));
            }
        }
        return 0;
    }

    public void rehandshake(long timeout) throws IOException {
        if (netInBuffer.position() > 0 && netInBuffer.position() < netInBuffer.limit())
            throw new IOException(sm.getString("channel.nio.ssl.netInputNotEmpty"));
        if (netOutBuffer.position() > 0 && netOutBuffer.position() < netOutBuffer.limit())
            throw new IOException(sm.getString("channel.nio.ssl.netOutputNotEmpty"));
        if (getBufHandler().getReadBuffer().position() > 0 && getBufHandler().getReadBuffer().position() < getBufHandler().getReadBuffer().limit())
            throw new IOException(sm.getString("channel.nio.ssl.appInputNotEmpty"));
        if (getBufHandler().getWriteBuffer().position() > 0 && getBufHandler().getWriteBuffer().position() < getBufHandler().getWriteBuffer().limit())
            throw new IOException(sm.getString("channel.nio.ssl.appOutputNotEmpty"));
        reset();
        boolean isReadable = true;
        boolean isWriteable = true;
        boolean handshaking = true;
        Selector selector = null;
        SelectionKey key = null;
        try {
            while (handshaking) {
                int hsStatus = this.handshake(isReadable, isWriteable);
                switch (hsStatus) {
                    case -1:
                        throw new EOFException(sm.getString("channel.nio.ssl.eofDuringHandshake"));
                    case 0:
                        handshaking = false;
                        break;
                    default: {
                        long now = System.currentTimeMillis();
                        if (selector == null) {
                            synchronized (Selector.class) {
                                selector = Selector.open();
                            }
                            key = getIOChannel().register(selector, hsStatus);
                        } else {
                            key.interestOps(hsStatus);
                        }
                        int keyCount = selector.select(timeout);
                        if (keyCount == 0 && ((System.currentTimeMillis() - now) >= timeout)) {
                            throw new SocketTimeoutException(sm.getString("channel.nio.ssl.timeoutDuringHandshake"));
                        }
                        isReadable = key.isReadable();
                        isWriteable = key.isWritable();
                    }
                }
            }
        } catch (IOException x) {
            throw x;
        } catch (Exception cx) {
            IOException x = new IOException(cx);
            throw x;
        } finally {
            if (key != null) try {
                key.cancel();
            } catch (Exception ignore) {
            }
            if (selector != null) try {
                selector.close();
            } catch (Exception ignore) {
            }
        }
    }

    protected SSLEngineResult.HandshakeStatus tasks() {
        Runnable r = null;
        while ((r = sslEngine.getDelegatedTask()) != null) {
            r.run();
        }
        return sslEngine.getHandshakeStatus();
    }

    protected SSLEngineResult handshakeWrap(boolean doWrite) throws IOException {
        netOutBuffer.clear();
        SSLEngineResult result = sslEngine.wrap(bufHandler.getWriteBuffer(), netOutBuffer);
        netOutBuffer.flip();
        handshakeStatus = result.getHandshakeStatus();
        if (doWrite) flush(netOutBuffer);
        return result;
    }

    protected SSLEngineResult handshakeUnwrap(boolean doread) throws IOException {

        if (netInBuffer.position() == netInBuffer.limit()) {
            netInBuffer.clear();
        }
        if (doread) {
            int read = sc.read(netInBuffer);
            if (read == -1) throw new IOException(sm.getString("channel.nio.ssl.eofDuringHandshake"));
        }
        SSLEngineResult result;
        boolean cont = false;
        do {
            netInBuffer.flip();
            result = sslEngine.unwrap(netInBuffer, bufHandler.getReadBuffer());
            netInBuffer.compact();
            handshakeStatus = result.getHandshakeStatus();
            if (result.getStatus() == SSLEngineResult.Status.OK && result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
                handshakeStatus = tasks();
            }
            cont = result.getStatus() == SSLEngineResult.Status.OK && handshakeStatus == HandshakeStatus.NEED_UNWRAP;
        } while (cont);
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closing) return;
        closing = true;
        sslEngine.closeOutbound();
        if (!flush(netOutBuffer)) {
            throw new IOException(sm.getString("channel.nio.ssl.remainingDataDuringClose"));
        }
        netOutBuffer.clear();
        SSLEngineResult handshake = sslEngine.wrap(getEmptyBuf(), netOutBuffer);
        if (handshake.getStatus() != SSLEngineResult.Status.CLOSED) {
            throw new IOException(sm.getString("channel.nio.ssl.invalidCloseState"));
        }
        netOutBuffer.flip();
        flush(netOutBuffer);
        closed = (!netOutBuffer.hasRemaining() && (handshake.getHandshakeStatus() != HandshakeStatus.NEED_WRAP));
    }

    @Override
    public void close(boolean force) throws IOException {
        try {
            close();
        } finally {
            if (force || closed) {
                closed = true;
                sc.socket().close();
                sc.close();
            }
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (dst != bufHandler.getReadBuffer())
            throw new IllegalArgumentException(sm.getString("channel.nio.ssl.invalidBuffer"));
        if (closing || closed) return -1;
        if (!handshakeComplete) throw new IllegalStateException(sm.getString("channel.nio.ssl.incompleteHandshake"));
        int netread = sc.read(netInBuffer);
        if (netread == -1) return -1;
        int read = 0;
        SSLEngineResult unwrap;
        do {
            netInBuffer.flip();
            unwrap = sslEngine.unwrap(netInBuffer, dst);
            netInBuffer.compact();
            if (unwrap.getStatus() == Status.OK || unwrap.getStatus() == Status.BUFFER_UNDERFLOW) {
                read += unwrap.bytesProduced();
                if (unwrap.getHandshakeStatus() == HandshakeStatus.NEED_TASK) tasks();
                if (unwrap.getStatus() == Status.BUFFER_UNDERFLOW) break;
            } else if (unwrap.getStatus() == Status.BUFFER_OVERFLOW && read > 0) {
                break;
            } else {
                throw new IOException(sm.getString("channel.nio.ssl.unwrapFail", unwrap.getStatus()));
            }
        } while ((netInBuffer.position() != 0)); //continue to unwrapping as long as the input buffer has stuff
        return (read);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        checkInterruptStatus();
        if (src == this.netOutBuffer) {
            int written = sc.write(src);
            return written;
        } else {
            if (closing || closed) {
                throw new IOException(sm.getString("channel.nio.ssl.closing"));
            }
            if (!flush(netOutBuffer)) {
                return 0;
            }
            netOutBuffer.clear();
            SSLEngineResult result = sslEngine.wrap(src, netOutBuffer);
            int written = result.bytesConsumed();
            netOutBuffer.flip();
            if (result.getStatus() == Status.OK) {
                if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) tasks();
            } else {
                throw new IOException(sm.getString("channel.nio.ssl.wrapFail", result.getStatus()));
            }
            flush(netOutBuffer);

            return written;
        }
    }

    @Override
    public int getOutboundRemaining() {
        return netOutBuffer.remaining();
    }

    @Override
    public boolean flushOutbound() throws IOException {
        int remaining = netOutBuffer.remaining();
        flush(netOutBuffer);
        int remaining2 = netOutBuffer.remaining();
        return remaining2 < remaining;
    }

    public static interface ApplicationBufferHandler {
        ByteBuffer expand(ByteBuffer buffer, int remaining);

        ByteBuffer getReadBuffer();

        ByteBuffer getWriteBuffer();
    }

    @Override
    public ApplicationBufferHandler getBufHandler() {
        return bufHandler;
    }

    @Override
    public boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    @Override
    public boolean isClosing() {
        return closing;
    }

    public SSLEngine getSslEngine() {
        return sslEngine;
    }

    public ByteBuffer getEmptyBuf() {
        return emptyBuf;
    }

    public void setBufHandler(ApplicationBufferHandler bufHandler) {
        this.bufHandler = bufHandler;
    }

    @Override
    public SocketChannel getIOChannel() {
        return sc;
    }
}
