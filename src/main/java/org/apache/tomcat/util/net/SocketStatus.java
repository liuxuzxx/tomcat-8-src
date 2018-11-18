package org.apache.tomcat.util.net;

public enum SocketStatus {
    OPEN_READ, OPEN_WRITE, STOP, TIMEOUT, DISCONNECT, ERROR, ASYNC_WRITE_ERROR, ASYNC_READ_ERROR, CLOSE_NOW
}
