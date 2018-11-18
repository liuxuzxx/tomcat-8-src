package org.apache.tomcat.util.net;

public enum DispatchType {

    NON_BLOCKING_READ(SocketStatus.OPEN_READ),
    NON_BLOCKING_WRITE(SocketStatus.OPEN_WRITE);

    private final SocketStatus status;

    private DispatchType(SocketStatus status) {
        this.status = status;
    }

    public SocketStatus getSocketStatus() {
        return status;
    }
}
