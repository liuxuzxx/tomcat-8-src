package org.apache.coyote;

import org.apache.tomcat.util.buf.ByteChunk;

import java.io.IOException;

public interface InputBuffer {

    int doRead(ByteChunk chunk, Request request) throws IOException;
}
