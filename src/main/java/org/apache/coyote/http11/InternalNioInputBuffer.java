package org.apache.coyote.http11;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioSelectorPool;
import org.apache.tomcat.util.net.SocketWrapper;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;

public class InternalNioInputBuffer extends AbstractNioInputBuffer<NioChannel> {
    private static final Log log = LogFactory.getLog(InternalNioInputBuffer.class);
    private NioChannel socket;
    private NioSelectorPool pool;

    public InternalNioInputBuffer(Request request, int headerBufferSize) {
        super(request, headerBufferSize);
        inputStreamInputBuffer = new SocketInputBuffer();
    }

    @Override
    protected final Log getLog() {
        return log;
    }

    @Override
    public void recycle() {
        super.recycle();
        socket = null;
    }

    @Override
    protected void init(SocketWrapper<NioChannel> socketWrapper, AbstractEndpoint<NioChannel> endpoint) throws IOException {
        socket = socketWrapper.getSocket();
        if (socket == null) {
            throw new IOException(sm.getString("iib.socketClosed"));
        }
        socketReadBufferSize = socket.getBufHandler().getReadBuffer().capacity();
        int bufLength = headerBufferSize + socketReadBufferSize;
        if (buf == null || buf.length < bufLength) {
            buf = new byte[bufLength];
        }
        pool = ((NioEndpoint)endpoint).getSelectorPool();
    }

    @Override
    protected boolean fill(boolean block) throws IOException, EOFException {
        if (parsingHeader) {
            if (lastValid > headerBufferSize) {
                throw new IllegalArgumentException(sm.getString("iib.requestheadertoolarge.error"));
            }
        } else {
            lastValid = pos = end;
        }
        int nRead = 0;
        ByteBuffer readBuffer = socket.getBufHandler().getReadBuffer();
        readBuffer.clear();
        if ( block ) {
            Selector selector = null;
            try {
                selector = pool.get();
            } catch ( IOException x ) {
                // Ignore
            }
            try {
                NioEndpoint.KeyAttachment att = (NioEndpoint.KeyAttachment) socket.getAttachment();
                if (att == null) {
                    throw new IOException("Key must be cancelled.");
                }
                nRead = pool.read(readBuffer,
                        socket, selector,
                        socket.getIOChannel().socket().getSoTimeout());
            } catch ( EOFException eof ) {
                nRead = -1;
            } finally {
                if ( selector != null ) pool.put(selector);
            }
        } else {
            nRead = socket.read(readBuffer);
        }
        if (nRead > 0) {
            readBuffer.flip();
            readBuffer.limit(nRead);
            expand(nRead + pos);
            readBuffer.get(buf, pos, nRead);
            lastValid = pos + nRead;
        } else if (nRead == -1) {
            throw new EOFException(sm.getString("iib.eof.error"));
        }
        return nRead > 0;
    }

    protected class SocketInputBuffer implements InputBuffer {

        @Override
        public int doRead(ByteChunk chunk, Request req )
            throws IOException {
            if (pos >= lastValid) {
                if (!fill(true))
                    return -1;
            }
            int length = lastValid - pos;
            chunk.setBytes(buf, pos, length);
            pos = lastValid;
            return (length);
        }
    }
}
