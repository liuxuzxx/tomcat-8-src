package org.apache.coyote;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;
import org.apache.tomcat.util.res.StringManager;

import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * Provides functionality and attributes common to all supported protocols
 * (currently HTTP and AJP).
 */
public abstract class AbstractProcessor<S> implements ActionHook, Processor<S> {

    protected static final StringManager sm = StringManager.getManager(Constants.Package);

    protected Adapter adapter;
    protected final AsyncStateMachine asyncStateMachine;
    protected final AbstractEndpoint<S> endpoint;
    protected final Request request;
    protected final Response response;
    protected volatile SocketWrapper<S> socketWrapper = null;
    private ErrorState errorState = ErrorState.NONE;

    protected AbstractProcessor() {
        asyncStateMachine = null;
        endpoint = null;
        request = null;
        response = null;
    }

    public AbstractProcessor(AbstractEndpoint<S> endpoint) {
        this.endpoint = endpoint;
        asyncStateMachine = new AsyncStateMachine(this);
        request = new Request();
        response = new Response();
        response.setHook(this);
        request.setResponse(response);
        request.setHook(this);
    }

    protected void setErrorState(ErrorState errorState, Throwable t) {
        boolean blockIo = this.errorState.isIoAllowed() && !errorState.isIoAllowed();
        this.errorState = this.errorState.getMostSevere(errorState);
        if (blockIo && !ContainerThreadMarker.isContainerThread() && isAsync()) {
            if (response.getStatus() < 400) {
                response.setStatus(500);
            }
            getLog().info(sm.getString("abstractProcessor.nonContainerThreadError"), t);
            getEndpoint().processSocket(socketWrapper, SocketStatus.CLOSE_NOW, true);
        }
    }

    protected void resetErrorState() {
        errorState = ErrorState.NONE;
    }

    protected ErrorState getErrorState() {
        return errorState;
    }

    protected AbstractEndpoint<S> getEndpoint() {
        return endpoint;
    }

    @Override
    public Request getRequest() {
        return request;
    }

    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }

    public Adapter getAdapter() {
        return adapter;
    }

    protected final void setSocketWrapper(SocketWrapper<S> socketWrapper) {
        this.socketWrapper = socketWrapper;
    }

    protected final SocketWrapper<S> getSocketWrapper() {
        return socketWrapper;
    }

    @Override
    public Executor getExecutor() {
        return endpoint.getExecutor();
    }

    @Override
    public boolean isAsync() {
        return (asyncStateMachine != null && asyncStateMachine.isAsync());
    }

    @Override
    public SocketState asyncPostProcess() {
        return asyncStateMachine.asyncPostProcess();
    }

    @Override
    public void errorDispatch() {
        getAdapter().errorDispatch(request, response);
    }

    @Override
    public abstract boolean isComet();

    @Override
    public abstract boolean isUpgrade();

    @Override
    public abstract SocketState process(SocketWrapper<S> socket) throws IOException;

    @Override
    public abstract SocketState event(SocketStatus status) throws IOException;

    @Override
    public abstract SocketState asyncDispatch(SocketStatus status);

    @Override
    public abstract SocketState upgradeDispatch(SocketStatus status) throws IOException;

    @Override
    public abstract UpgradeToken getUpgradeToken();

    protected abstract void registerForEvent(boolean read, boolean write);

    protected abstract Log getLog();
}
