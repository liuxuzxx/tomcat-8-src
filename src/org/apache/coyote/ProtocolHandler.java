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

package org.apache.coyote;

import java.util.concurrent.Executor;

/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * This is the main interface to be implemented by a coyote connector.
 * Adapter is the main interface to be implemented by a coyote servlet
 * container.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 * @see Adapter
 */
/**
 * 我们的tomcat的使用的connector就是从这个接口中实现的，但是从这个接口的方法看，好像就是 启动，停止，暂停，复苏和获取适配器的意思
 * 其实，我们在读取源代码的时候，不一定说，一下就把这个设计模式和这个很深的东西就给学习了，
 * 其实学习人家的命名、编码习惯、做法，我觉得是一种即战力，而且是很好学习的一种方式
 * @author liuxu
 *Handler:处理者，管理者; （动物） 驯化者; [自] （信息） 处理机; 拳击教练
 *以前，Handler，我总是认为是手柄的意思，但是从这个延伸的意思看，理解成手柄也是正确的，毕竟，只有抓住手柄，你才能
 *更加的省力
 */
public interface ProtocolHandler {

	/**
	 * The adapter, used to call the connector.
	 */
	public void setAdapter(Adapter adapter);

	public Adapter getAdapter();

	/**
	 * The executor, provide access to the underlying thread pool.
	 */
	public Executor getExecutor();

	/**
	 * 下面都是对这个协议的操作，比如说：stop、start、destory、init、pause、resume了
	 */
	/**
	 * Initialise the protocol.
	 * 我写接口的时候，从来没有一个方法抛出去异常，这个算是我学习到的第一个地方，学习了，
	 * 其实，我们的很多方案都是在需求出来以后确定的，所以，一定要设计好需求
	 */
	public void init() throws Exception;

	/**
	 * Start the protocol.
	 */
	public void start() throws Exception;

	/**
	 * Pause the protocol (optional).
	 */
	public void pause() throws Exception;

	/**
	 * Resume the protocol (optional).
	 */
	public void resume() throws Exception;

	/**
	 * Stop the protocol.
	 */
	public void stop() throws Exception;

	/**
	 * Destroy the protocol (optional).
	 */
	public void destroy() throws Exception;

	/**
	 * Requires APR/native library
	 */
	public boolean isAprRequired();

	/**
	 * Does this ProtocolHandler support Comet?
	 */
	public boolean isCometSupported();

	/**
	 * Does this ProtocolHandler support Comet timeouts?
	 */
	public boolean isCometTimeoutSupported();

	/**
	 * Does this ProtocolHandler support sendfile?
	 */
	public boolean isSendfileSupported();
}
