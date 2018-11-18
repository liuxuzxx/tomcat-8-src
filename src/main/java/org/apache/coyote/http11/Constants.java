package org.apache.coyote.http11;

import org.apache.tomcat.util.buf.ByteChunk;

public final class Constants {
    public static final String Package = "org.apache.coyote.http11";
    public static final int DEFAULT_CONNECTION_LINGER = -1;
    public static final int DEFAULT_CONNECTION_TIMEOUT = 60000;
    public static final boolean DEFAULT_TCP_NO_DELAY = true;
    public static final String CRLF = "\r\n";
    public static final byte[] SERVER_BYTES = ByteChunk.convertToBytes("Server: Apache-Coyote/1.1" + CRLF);
    public static final byte CR = (byte) '\r';
    public static final byte LF = (byte) '\n';
    public static final byte SP = (byte) ' ';
    public static final byte HT = (byte) '\t';
    public static final byte COLON = (byte) ':';
    public static final byte SEMI_COLON = (byte) ';';
    public static final byte A = (byte) 'A';
    public static final byte a = (byte) 'a';
    public static final byte Z = (byte) 'Z';
    public static final byte QUESTION = (byte) '?';
    public static final byte LC_OFFSET = A - a;
    public static final String CONNECTION = "Connection";
    public static final String CLOSE = "close";
    public static final byte[] CLOSE_BYTES = ByteChunk.convertToBytes(CLOSE);
    public static final String KEEPALIVE = "keep-alive";
    public static final byte[] KEEPALIVE_BYTES = ByteChunk.convertToBytes(KEEPALIVE);
    public static final String CHUNKED = "chunked";
    public static final byte[] ACK_BYTES = ByteChunk.convertToBytes("HTTP/1.1 100 Continue" + CRLF + CRLF);
    public static final String TRANSFERENCODING = "Transfer-Encoding";
    public static final byte[] _200_BYTES = ByteChunk.convertToBytes("200");
    public static final byte[] _400_BYTES = ByteChunk.convertToBytes("400");
    public static final byte[] _404_BYTES = ByteChunk.convertToBytes("404");
    public static final int IDENTITY_FILTER = 0;
    public static final int CHUNKED_FILTER = 1;
    public static final int VOID_FILTER = 2;
    public static final int GZIP_FILTER = 3;
    public static final int BUFFERED_FILTER = 3;
    public static final String HTTP_10 = "HTTP/1.0";
    public static final String HTTP_11 = "HTTP/1.1";
    public static final byte[] HTTP_11_BYTES = ByteChunk.convertToBytes(HTTP_11);
    public static final String GET = "GET";
    public static final String HEAD = "HEAD";
    public static final String POST = "POST";
}
