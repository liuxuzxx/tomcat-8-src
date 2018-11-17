package org.apache.tomcat.util.net;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

public class SocketWrapper<E> {

    private volatile E socket;
    private volatile long lastAccess = System.currentTimeMillis();
    private volatile long timeout = -1;
    private volatile boolean error = false;
    private volatile int keepAliveLeft = 100;
    private volatile boolean comet = false;
    private volatile boolean async = false;
    private boolean keptAlive = false;
    private volatile boolean upgraded = false;
    private boolean secure = false;
    private String localAddr = null;
    private String localName = null;
    private int localPort = -1;
    private String remoteAddr = null;
    private String remoteHost = null;
    private int remotePort = -1;
    private volatile boolean blockingStatus = true;
    private final Lock blockingStatusReadLock;
    private final WriteLock blockingStatusWriteLock;
    private final Object writeThreadLock = new Object();
    private Set<DispatchType> dispatches = new CopyOnWriteArraySet<>();

    public SocketWrapper(E socket) {
        this.socket = socket;
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        this.blockingStatusReadLock = lock.readLock();
        this.blockingStatusWriteLock = lock.writeLock();
    }

    public E getSocket() {
        return socket;
    }

    public boolean isComet() {
        return comet;
    }

    public void setComet(boolean comet) {
        this.comet = comet;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isUpgraded() {
        return upgraded;
    }

    public void setUpgraded(boolean upgraded) {
        this.upgraded = upgraded;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public long getLastAccess() {
        return lastAccess;
    }

    public void access() {
        if (!isAsync()) {
            access(System.currentTimeMillis());
        }
    }

    public void access(long access) {
        lastAccess = access;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public long getTimeout() {
        return this.timeout;
    }

    public boolean getError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public void setKeepAliveLeft(int keepAliveLeft) {
        this.keepAliveLeft = keepAliveLeft;
    }

    public int decrementKeepAlive() {
        return (--keepAliveLeft);
    }

    public boolean isKeptAlive() {
        return keptAlive;
    }

    public void setKeptAlive(boolean keptAlive) {
        this.keptAlive = keptAlive;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public String getLocalName() {
        return localName;
    }

    public void setLocalName(String localName) {
        this.localName = localName;
    }

    public String getLocalAddr() {
        return localAddr;
    }

    public void setLocalAddr(String localAddr) {
        this.localAddr = localAddr;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public void setRemoteAddr(String remoteAddr) {
        this.remoteAddr = remoteAddr;
    }

    public boolean getBlockingStatus() {
        return blockingStatus;
    }

    public void setBlockingStatus(boolean blockingStatus) {
        this.blockingStatus = blockingStatus;
    }

    public Lock getBlockingStatusReadLock() {
        return blockingStatusReadLock;
    }

    public WriteLock getBlockingStatusWriteLock() {
        return blockingStatusWriteLock;
    }

    public Object getWriteThreadLock() {
        return writeThreadLock;
    }

    public void addDispatch(DispatchType dispatchType) {
        synchronized (dispatches) {
            dispatches.add(dispatchType);
        }
    }

    public Iterator<DispatchType> getIteratorAndClearDispatches() {
        Iterator<DispatchType> result;
        synchronized (dispatches) {
            result = dispatches.iterator();
            if (result.hasNext()) {
                dispatches.clear();
            } else {
                result = null;
            }
        }
        return result;
    }

    public void clearDispatches() {
        synchronized (dispatches) {
            dispatches.clear();
        }
    }

    @Override
    public String toString() {
        return super.toString() + ":" + String.valueOf(socket);
    }

    public void registerforEvent(int timeout, boolean read, boolean write) {
    }
}
