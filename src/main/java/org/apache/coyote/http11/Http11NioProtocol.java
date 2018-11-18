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
 * 通过这个debug可以看出来，是这个protocol接受了前台的访问信息，不是那个没有nio的protocl接受的
 * 这个是在tomcat启动的时候装配的，这个地方更正一下我们以前的认识和记忆
 */
public class Http11NioProtocol extends AbstractHttp11JsseProtocol<NioChannel> {
	private static final Log log = LogFactory.getLog(Http11NioProtocol.class);
	private final Http11ConnectionHandler cHandler;

	@Override
	protected Log getLog() {
		return log;
	}

	@Override
	protected AbstractEndpoint.Handler getHandler() {
		return cHandler;
	}

	public Http11NioProtocol() {
		endpoint = new NioEndpoint();
		cHandler = new Http11ConnectionHandler(this);
		((NioEndpoint) endpoint).setHandler(cHandler);
		setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
		setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
		setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
		System.out.println(this.getClass().getName() + "tomcat启动的时候装配这个组件，这个组件是protocol");
	}

	public NioEndpoint getEndpoint() {
		return ((NioEndpoint) endpoint);
	}

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

	public void setOomParachute(int oomParachute) {
		((NioEndpoint) endpoint).setOomParachute(oomParachute);
	}

	@Override
	protected String getNamePrefix() {
		return ("http-nio");
	}

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

		@Override
		public void release(SocketChannel socket) {
			if (log.isDebugEnabled()) {
				log.debug("Iterating through our connections to release a socket channel:" + socket);
			}
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

		@Override
		public void release(SocketWrapper<NioChannel> socket) {
			Processor<NioChannel> processor = connections.remove(socket.getSocket());
			if (processor != null) {
				processor.recycle(true);
				recycledProcessors.push(processor);
			}
		}

		@Override
		public void release(SocketWrapper<NioChannel> socket, Processor<NioChannel> processor, boolean isSocketClosing,
				boolean addToPoller) {
			processor.recycle(isSocketClosing);
			recycledProcessors.push(processor);
			if (addToPoller) {
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
				socket.getSocket().getPoller().add(socket.getSocket());
			}
		}

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
