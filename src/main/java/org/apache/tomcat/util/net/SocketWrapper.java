/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * Wrapper：包装的意思，其实从这个名字就应该看出来，这个其实是对socket的包装的一个类
 * 让我不是很明白的就是，既然是对socket的包装，那么为什么，还用一个泛型进行一个使用啊，是不是怕以后 是否是有其他的通信的方式
 * 应该也是一个实体数据对象类，其实就是对这个socket的一层的包装类
 * 从查看这个继承体系上能看出来，其实都是EndPoint的内部有一个继承了这个类进行了一个内容的使用
 * 
 * @author liuxu
 *
 * @param <E>
 */
public class SocketWrapper<E> {

	private volatile E socket;

	// Volatile because I/O and setting the timeout values occurs on a different
	// thread to the thread checking the timeout.
	private volatile long lastAccess = System.currentTimeMillis();
	private volatile long timeout = -1;

	private volatile boolean error = false;
	private volatile int keepAliveLeft = 100;
	private volatile boolean comet = false;
	private volatile boolean async = false;
	private boolean keptAlive = false;
	private volatile boolean upgraded = false;
	private boolean secure = false;
	/*
	 * Following cached for speed / reduced GC
	 */
	private String localAddr = null;
	private String localName = null;
	private int localPort = -1;
	private String remoteAddr = null;
	private String remoteHost = null;
	private int remotePort = -1;
	/*
	 * Used if block/non-blocking is set at the socket level. The client is
	 * responsible for the thread-safe use of this field via the locks provided.
	 */
	private volatile boolean blockingStatus = true;
	private final Lock blockingStatusReadLock;
	private final WriteLock blockingStatusWriteLock;

	/*
	 * In normal servlet processing only one thread is allowed to access the
	 * socket at a time. That is controlled by a lock on the socket for both
	 * read and writes). When HTTP upgrade is used, one read thread and one
	 * write thread are allowed to access the socket concurrently. In this case
	 * the lock on the socket is used for reads and the lock below is used for
	 * writes.
	 */
	private final Object writeThreadLock = new Object();

	private Set<DispatchType> dispatches = new CopyOnWriteArraySet<>();

	public SocketWrapper(E socket) {
		this.socket = socket;
		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		this.blockingStatusReadLock = lock.readLock();
		this.blockingStatusWriteLock = lock.writeLock();
	}

	/**
	 * 一直到250行左右都是这个get-set方法，就是一个javabean的形式
	 * 
	 */
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
		// Async timeouts are based on the time between the call to startAsync()
		// and complete() / dispatch() so don't update the last access time
		// (that drives the timeout) on every read and write when using async
		// processing.
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

	// error is used by NIO2 - will move to Nio2SocketWraper in Tomcat 9
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
		/**
		 * 同步的时候尽可能使用这个代码块同步，这样子的资源争夺才不是多么的厉害
		 */
		synchronized (dispatches) {
			dispatches.add(dispatchType);
		}
	}

	public Iterator<DispatchType> getIteratorAndClearDispatches() {
		// Note: Logic in AbstractProtocol depends on this method only returning
		// a non-null value if the iterator is non-empty. i.e. it should never
		// return an empty iterator.
		Iterator<DispatchType> result;
		synchronized (dispatches) {
			// Synchronized as the generation of the iterator and the clearing
			// of dispatches needs to be an atomic operation.
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

	/**
	 * Overridden for debug purposes. No guarantees are made about the format of
	 * this message which may vary significantly between point releases.
	 * <p>
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		/**
		 * java.lang.String的valueOf方法是一个static方法，这个方法首先是测试这个object是否时null的，
		 * 之后进行一个toString方法调用
		 */
		return super.toString() + ":" + String.valueOf(socket);
	}

	/**
	 * Register the associated socket for the requested events.
	 *
	 * @param timeout
	 *            The time to wait for the event(s) to occur
	 * @param read
	 *            Should the socket be register for read?
	 * @param write
	 *            Should the socket be register for write?
	 */
	public void registerforEvent(int timeout, boolean read, boolean write) {
		// NO-OP by default.
		// Currently only implemented by APR.
	}
}
