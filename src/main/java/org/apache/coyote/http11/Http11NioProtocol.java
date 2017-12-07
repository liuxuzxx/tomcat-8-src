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
package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.Processor;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.upgrade.NioProcessor;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioEndpoint.Handler;
import org.apache.tomcat.util.net.SSLImplementation;
import org.apache.tomcat.util.net.SecureNioChannel;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Abstract the protocol implementation, including threading, etc. Processor is
 * single threaded and specific to stream-based protocols, will not fit Jk
 * protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
/**
 * 通过这个debug可以看出来，是这个protocol接受了前台的访问信息，不是那个没有nio的protocl接受的
 * 这个是在tomcat启动的时候装配的，这个地方更正一下我们以前的认识和记忆
 * @author liuxu
 *
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {

	private static final Log log = LogFactory.getLog(Http11NioProtocol.class);

	/**
	 * 第一次见到这个log是一个protected的方式方法，这个方式，只能是继承的子类才能访问操作，
	 * 其实也是对的，毕竟这个是需要记录父类的log日志信息
	 */
	@Override
	protected Log getLog() {
		return log;
	}

	/**
	 * handler：处理，掌握，这个是endpoint提供的处理工具，不过是通过抽象类提供的，这个是一个做法
	 */
	@Override
	protected AbstractEndpoint.Handler getHandler() {
		return cHandler;
	}

	/**
	 * tomcat开始启动的时候，需要装配一个协议进行一个组建工作，这个httpprotocol协议就是一个组件
	 */
	public Http11NioProtocol() {
		endpoint = new NioEndpoint();
		cHandler = new Http11ConnectionHandler(this);
		/**
		 * 你们也是用强制类型转换啊
		 */
		((NioEndpoint) endpoint).setHandler(cHandler);
		setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
		setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
		setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
		System.out.println(this.getClass().getName() + "tomcat启动的时候装配这个组件，这个组件是protocol");
	}

	public NioEndpoint getEndpoint() {
		return ((NioEndpoint) endpoint);
	}

	// -------------------- Properties--------------------

	/**
	 * 你的属性放置在这个，我怎么看到啊，你应该放在这个使用这个变量的上边来着，作者。。。
	 */
	private final Http11ConnectionHandler cHandler;

	// -------------------- Pool setup --------------------

	public void setPollerThreadCount(int count) {
		((NioEndpoint) endpoint).setPollerThreadCount(count);
	}

	public int getPollerThreadCount() {
		return ((NioEndpoint) endpoint).getPollerThreadCount();
	}

	public void setSelectorTimeout(long timeout) {
		((NioEndpoint) endpoint).setSelectorTimeout(timeout);
	}

	public long getSelectorTimeout() {
		return ((NioEndpoint) endpoint).getSelectorTimeout();
	}

	public void setAcceptorThreadPriority(int threadPriority) {
		((NioEndpoint) endpoint).setAcceptorThreadPriority(threadPriority);
	}

	public void setPollerThreadPriority(int threadPriority) {
		((NioEndpoint) endpoint).setPollerThreadPriority(threadPriority);
	}

	public int getAcceptorThreadPriority() {
		return ((NioEndpoint) endpoint).getAcceptorThreadPriority();
	}

	public int getPollerThreadPriority() {
		return ((NioEndpoint) endpoint).getThreadPriority();
	}

	public boolean getUseSendfile() {
		return endpoint.getUseSendfile();
	}

	public void setUseSendfile(boolean useSendfile) {
		((NioEndpoint) endpoint).setUseSendfile(useSendfile);
	}

	// -------------------- Tcp setup --------------------
	public void setOomParachute(int oomParachute) {
		((NioEndpoint) endpoint).setOomParachute(oomParachute);
	}

	// ----------------------------------------------------- JMX related methods

	@Override
	protected String getNamePrefix() {
		return ("http-nio");
	}

	// -------------------- Connection handler --------------------

	/**
	 * HTTP请求通过的一个连接器的Handler
	 */
	protected static class Http11ConnectionHandler extends AbstractConnectionHandler<NioChannel, Http11NioProcessor> implements Handler {

		protected Http11NioProtocol proto;

		Http11ConnectionHandler(Http11NioProtocol proto) {
			this.proto = proto;
			System.out.println(this.getClass().getName() + "内部的COnnection进行一个资源的获取");
		}

		@Override
		protected AbstractProtocol<NioChannel> getProtocol() {
			return proto;
		}

		@Override
		protected Log getLog() {
			return log;
		}

		@Override
		public SSLImplementation getSslImplementation() {
			return proto.sslImplementation;
		}

		/**
		 * Expected to be used by the Poller to release resources on socket
		 * close, errors etc.
		 */
		@Override
		public void release(SocketChannel socket) {
			if (log.isDebugEnabled())
				log.debug("Iterating through our connections to release a socket channel:" + socket);
			boolean released = false;
			Iterator<java.util.Map.Entry<NioChannel, Processor<NioChannel>>> it = connections.entrySet().iterator();
			while (it.hasNext()) {
				java.util.Map.Entry<NioChannel, Processor<NioChannel>> entry = it.next();
				if (entry.getKey().getIOChannel() == socket) {
					it.remove();
					Processor<NioChannel> result = entry.getValue();
					result.recycle(true);
					unregister(result);
					released = true;
					break;
				}
			}
			if (log.isDebugEnabled())
				log.debug("Done iterating through our connections to release a socket channel:" + socket + " released:"
						+ released);
		}

		/**
		 * Expected to be used by the Poller to release resources on socket
		 * close, errors etc.
		 */
		@Override
		public void release(SocketWrapper<NioChannel> socket) {
			Processor<NioChannel> processor = connections.remove(socket.getSocket());
			if (processor != null) {
				processor.recycle(true);
				recycledProcessors.push(processor);
			}
		}

		/**
		 * Expected to be used by the handler once the processor is no longer
		 * required.
		 *
		 * @param socket
		 * @param processor
		 * @param isSocketClosing
		 *            Not used in HTTP
		 * @param addToPoller
		 */
		@Override
		public void release(SocketWrapper<NioChannel> socket, Processor<NioChannel> processor, boolean isSocketClosing,
				boolean addToPoller) {
			processor.recycle(isSocketClosing);
			recycledProcessors.push(processor);
			if (addToPoller) {
				// The only time this method is called with addToPoller == true
				// is when the socket is in keep-alive so set the appropriate
				// timeout.
				socket.setTimeout(getProtocol().getKeepAliveTimeout());
				socket.getSocket().getPoller().add(socket.getSocket());
			}
		}

		@Override
		protected void initSsl(SocketWrapper<NioChannel> socket, Processor<NioChannel> processor) {
			if (proto.isSSLEnabled() && (proto.sslImplementation != null)
					&& (socket.getSocket() instanceof SecureNioChannel)) {
				SecureNioChannel ch = (SecureNioChannel) socket.getSocket();
				processor.setSslSupport(proto.sslImplementation.getSSLSupport(ch.getSslEngine().getSession()));
			} else {
				processor.setSslSupport(null);
			}

		}

		@Override
		protected void longPoll(SocketWrapper<NioChannel> socket, Processor<NioChannel> processor) {

			if (processor.isAsync()) {
				socket.setAsync(true);
			} else {
				// Either:
				// - this is comet request
				// - this is an upgraded connection
				// - the request line/headers have not been completely
				// read
				socket.getSocket().getPoller().add(socket.getSocket());
			}
		}

		/**
		 * 我们首先需要明白两个单词的意思：protocol：协议，processor：加工机，加工，数据处理的意思
		 * 当我们第一次访问的时候，就是首先是从这个地方开始的， 我的策略就是一种笨蛋方法，就是一步一步的进行一条调试性质的工作
		 * 
		 * @return
		 */
		@Override
		public Http11NioProcessor createProcessor() {
			System.out.println(this.getClass().getName() + "当第一次访问的时候，产生一个processor出来");
			Http11NioProcessor processor = new Http11NioProcessor(proto.getMaxHttpHeaderSize(),
					(NioEndpoint) proto.endpoint, proto.getMaxTrailerSize(), proto.getAllowedTrailerHeadersAsSet(),
					proto.getMaxExtensionSize(), proto.getMaxSwallowSize());
			proto.configureProcessor(processor);
			register(processor);
			return processor;
		}

		@Override
		protected Processor<NioChannel> createUpgradeProcessor(SocketWrapper<NioChannel> socket,
				ByteBuffer leftoverInput, UpgradeToken upgradeToken) throws IOException {
			return new NioProcessor(socket, leftoverInput, upgradeToken, proto.getEndpoint().getSelectorPool(),
					proto.getUpgradeAsyncWriteBufferSize());
		}
	}
}