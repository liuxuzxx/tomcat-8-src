package org.apache.coyote.http11;

import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.AsyncContextCallback;
import org.apache.coyote.ErrorState;
import org.apache.coyote.RequestInfo;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.filters.BufferedInputFilter;
import org.apache.coyote.http11.filters.ChunkedInputFilter;
import org.apache.coyote.http11.filters.ChunkedOutputFilter;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.coyote.http11.filters.IdentityInputFilter;
import org.apache.coyote.http11.filters.IdentityOutputFilter;
import org.apache.coyote.http11.filters.SavedRequestInputFilter;
import org.apache.coyote.http11.filters.VoidInputFilter;
import org.apache.coyote.http11.filters.VoidOutputFilter;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.Ascii;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.log.UserDataHelper;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;
import org.apache.tomcat.util.res.StringManager;
import org.lx.tomcat.util.SystemUtil;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public abstract class AbstractHttp11Processor<S> extends AbstractProcessor<S> {

    private final UserDataHelper userDataHelper;
    protected static final StringManager sm = StringManager.getManager(Constants.Package);
    protected AbstractInputBuffer<S> inputBuffer;
    protected AbstractOutputBuffer<S> outputBuffer;
    private int pluggableFilterIndex = Integer.MAX_VALUE;
    protected volatile boolean keepAlive = true;
    protected boolean openSocket = false;
    protected boolean keptAlive;
    protected boolean sendfileInProgress = false;
    protected boolean readComplete = true;
    protected boolean http11 = true;
    protected boolean http09 = false;
    protected boolean contentDelimitation = true;
    protected boolean expectation = false;
    protected boolean comet = false;
    protected Pattern restrictedUserAgents = null;
    protected int maxKeepAliveRequests = -1;
    protected int keepAliveTimeout = -1;
    protected int connectionUploadTimeout = 300000;
    protected boolean disableUploadTimeout = false;
    protected int compressionLevel = 0;
    protected int compressionMinSize = 2048;
    protected int maxSavePostSize = 4 * 1024;
    protected Pattern noCompressionUserAgents = null;
    protected String[] compressableMimeTypes = {"text/html", "text/xml", "text/plain"};
    protected char[] hostNameC = new char[0];
    protected String server = null;
    protected UpgradeToken upgradeToken = null;

    public AbstractHttp11Processor(AbstractEndpoint<S> endpoint) {
        super(endpoint);
        userDataHelper = new UserDataHelper(getLog());
    }

    public void setCompression(String compression) {
        switch (compression) {
            case "on":
                this.compressionLevel = 1;
                break;
            case "force":
                this.compressionLevel = 2;
                break;
            case "off":
                this.compressionLevel = 0;
                break;
            default:
                try {
                    compressionMinSize = Integer.parseInt(compression);
                    this.compressionLevel = 1;
                } catch (Exception e) {
                    this.compressionLevel = 0;
                }
                break;
        }
    }

    public void setCompressionMinSize(int compressionMinSize) {
        this.compressionMinSize = compressionMinSize;
    }

    public void setNoCompressionUserAgents(String noCompressionUserAgents) {
        if (noCompressionUserAgents == null || noCompressionUserAgents.length() == 0) {
            this.noCompressionUserAgents = null;
        } else {
            this.noCompressionUserAgents =
                    Pattern.compile(noCompressionUserAgents);
        }
    }

    public void addCompressableMimeType(String mimeType) {
        compressableMimeTypes = addStringArray(compressableMimeTypes, mimeType);
    }

    public void setCompressableMimeTypes(String[] compressableMimeTypes) {
        this.compressableMimeTypes = compressableMimeTypes;
    }

    public void setCompressableMimeTypes(String compressableMimeTypes) {
        if (compressableMimeTypes != null) {
            this.compressableMimeTypes = null;
            StringTokenizer st = new StringTokenizer(compressableMimeTypes, ",");

            while (st.hasMoreTokens()) {
                addCompressableMimeType(st.nextToken().trim());
            }
        }
    }

    public String getCompression() {
        switch (compressionLevel) {
            case 0:
                return "off";
            case 1:
                return "on";
            case 2:
                return "force";
        }
        return "off";
    }

    private String[] addStringArray(String sArray[], String value) {
        String[] result = null;
        if (sArray == null) {
            result = new String[1];
            result[0] = value;
        } else {
            result = new String[sArray.length + 1];
            for (int i = 0; i < sArray.length; i++) {
                result[i] = sArray[i];
            }
            result[sArray.length] = value;
        }
        return result;
    }

    private boolean startsWithStringArray(String sArray[], String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < sArray.length; i++) {
            if (value.startsWith(sArray[i])) {
                return true;
            }
        }
        return false;
    }

    public void setRestrictedUserAgents(String restrictedUserAgents) {
        if (restrictedUserAgents == null || restrictedUserAgents.length() == 0) {
            this.restrictedUserAgents = null;
        } else {
            this.restrictedUserAgents = Pattern.compile(restrictedUserAgents);
        }
    }


    public void setMaxKeepAliveRequests(int mkar) {
        maxKeepAliveRequests = mkar;
    }

    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }

    public void setKeepAliveTimeout(int timeout) {
        keepAliveTimeout = timeout;
    }

    public int getKeepAliveTimeout() {
        return keepAliveTimeout;
    }

    public void setMaxSavePostSize(int msps) {
        maxSavePostSize = msps;
    }

    public int getMaxSavePostSize() {
        return maxSavePostSize;
    }

    public void setDisableUploadTimeout(boolean isDisabled) {
        disableUploadTimeout = isDisabled;
    }

    public boolean getDisableUploadTimeout() {
        return disableUploadTimeout;
    }

    public void setSocketBuffer(int socketBuffer) {
        outputBuffer.setSocketBuffer(socketBuffer);
    }

    public int getSocketBuffer() {
        return outputBuffer.getSocketBuffer();
    }

    public void setConnectionUploadTimeout(int timeout) {
        connectionUploadTimeout = timeout;
    }

    public int getConnectionUploadTimeout() {
        return connectionUploadTimeout;
    }

    public void setServer(String server) {
        if (server == null || server.equals("")) {
            this.server = null;
        } else {
            this.server = server;
        }
    }

    public String getServer() {
        return server;
    }

    private boolean isCompressable() {
        MessageBytes contentEncodingMB = response.getMimeHeaders().getValue("Content-Encoding");
        if ((contentEncodingMB != null)
                && (contentEncodingMB.indexOf("gzip") != -1)) {
            return false;
        }
        if (compressionLevel == 2) {
            return true;
        }
        long contentLength = response.getContentLengthLong();
        if ((contentLength == -1)
                || (contentLength > compressionMinSize)) {
            // Check for compatible MIME-TYPE
            if (compressableMimeTypes != null) {
                return (startsWithStringArray(compressableMimeTypes,
                        response.getContentType()));
            }
        }

        return false;
    }

    private boolean useCompression() {
        MessageBytes acceptEncodingMB = request.getMimeHeaders().getValue("accept-encoding");
        if ((acceptEncodingMB == null)
                || (acceptEncodingMB.indexOf("gzip") == -1)) {
            return false;
        }
        if (compressionLevel == 2) {
            return true;
        }
        if (noCompressionUserAgents != null) {
            MessageBytes userAgentValueMB =
                    request.getMimeHeaders().getValue("user-agent");
            if (userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();

                if (noCompressionUserAgents.matcher(userAgentValue).matches()) {
                    return false;
                }
            }
        }

        return true;
    }

    protected int findBytes(ByteChunk bc, byte[] b) {

        byte first = b[0];
        byte[] buff = bc.getBuffer();
        int start = bc.getStart();
        int end = bc.getEnd();
        int srcEnd = b.length;

        for (int i = start; i <= (end - srcEnd); i++) {
            if (Ascii.toLower(buff[i]) != first) {
                continue;
            }
            int myPos = i + 1;
            for (int srcPos = 1; srcPos < srcEnd; ) {
                if (Ascii.toLower(buff[myPos++]) != b[srcPos++]) {
                    break;
                }
                if (srcPos == srcEnd) {
                    return i - start;
                }
            }
        }
        return -1;
    }

    protected boolean statusDropsConnection(int status) {
        return status == 400 /* SC_BAD_REQUEST */ ||
                status == 408 /* SC_REQUEST_TIMEOUT */ ||
                status == 411 /* SC_LENGTH_REQUIRED */ ||
                status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
                status == 414 /* SC_REQUEST_URI_TOO_LONG */ ||
                status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
                status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
                status == 501 /* SC_NOT_IMPLEMENTED */;
    }

    protected abstract AbstractInputBuffer<S> getInputBuffer();

    protected abstract AbstractOutputBuffer<S> getOutputBuffer();

    protected void initializeFilters(int maxTrailerSize, Set<String> allowedTrailerHeaders,
                                     int maxExtensionSize, int maxSwallowSize) {
        getInputBuffer().addFilter(new IdentityInputFilter(maxSwallowSize));
        getOutputBuffer().addFilter(new IdentityOutputFilter());
        getInputBuffer().addFilter(new ChunkedInputFilter(maxTrailerSize, allowedTrailerHeaders, maxExtensionSize, maxSwallowSize));
        getOutputBuffer().addFilter(new ChunkedOutputFilter());
        getInputBuffer().addFilter(new VoidInputFilter());
        getOutputBuffer().addFilter(new VoidOutputFilter());
        getInputBuffer().addFilter(new BufferedInputFilter());
        getOutputBuffer().addFilter(new GzipOutputFilter());
        pluggableFilterIndex = getInputBuffer().getFilters().length;
    }

    private void addInputFilter(InputFilter[] inputFilters, String encodingName) {
        encodingName = encodingName.trim().toLowerCase(Locale.ENGLISH);
        if (encodingName.equals("identity")) {
            // Skip
        } else if (encodingName.equals("chunked")) {
            getInputBuffer().addActiveFilter(inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else {
            for (int i = pluggableFilterIndex; i < inputFilters.length; i++) {
                if (inputFilters[i].getEncodingName().toString().equals(encodingName)) {
                    getInputBuffer().addActiveFilter(inputFilters[i]);
                    return;
                }
            }
            response.setStatus(501);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("http11processor.request.prepare") + " Unsupported transfer encoding [" + encodingName + "]");
            }
        }
    }

    @Override
    public final void action(ActionCode actionCode, Object param) {

        switch (actionCode) {
            case CLOSE: {
                try {
                    getOutputBuffer().endRequest();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                }
                break;
            }
            case COMMIT: {
                if (response.isCommitted()) {
                    return;
                }
                try {
                    prepareResponse();
                    getOutputBuffer().commit();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                }
                break;
            }
            case ACK: {
                if ((response.isCommitted()) || !expectation) {
                    return;
                }
                getInputBuffer().setSwallowInput(true);
                try {
                    getOutputBuffer().sendAck();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                }
                break;
            }
            case CLIENT_FLUSH: {
                try {
                    getOutputBuffer().flush();
                } catch (IOException e) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                    response.setErrorException(e);
                }
                break;
            }
            case IS_ERROR: {
                ((AtomicBoolean) param).set(getErrorState().isError());
                break;
            }
            case DISABLE_SWALLOW_INPUT: {
                setErrorState(ErrorState.CLOSE_CLEAN, null);
                getInputBuffer().setSwallowInput(false);
                break;
            }
            case RESET: {
                getOutputBuffer().reset();
                break;
            }
            case REQ_SET_BODY_REPLAY: {
                ByteChunk body = (ByteChunk) param;
                InputFilter savedBody = new SavedRequestInputFilter(body);
                savedBody.setRequest(request);
                @SuppressWarnings("unchecked")
                AbstractInputBuffer<S> internalBuffer = (AbstractInputBuffer<S>) request.getInputBuffer();
                internalBuffer.addActiveFilter(savedBody);
                break;
            }
            case ASYNC_START: {
                asyncStateMachine.asyncStart((AsyncContextCallback) param);
                getSocketWrapper().access();
                break;
            }
            case ASYNC_DISPATCHED: {
                asyncStateMachine.asyncDispatched();
                break;
            }
            case ASYNC_TIMEOUT: {
                AtomicBoolean result = (AtomicBoolean) param;
                result.set(asyncStateMachine.asyncTimeout());
                break;
            }
            case ASYNC_RUN: {
                asyncStateMachine.asyncRun((Runnable) param);
                break;
            }
            case ASYNC_ERROR: {
                asyncStateMachine.asyncError();
                break;
            }
            case ASYNC_IS_STARTED: {
                ((AtomicBoolean) param).set(asyncStateMachine.isAsyncStarted());
                break;
            }
            case ASYNC_IS_COMPLETING: {
                ((AtomicBoolean) param).set(asyncStateMachine.isCompleting());
                break;
            }
            case ASYNC_IS_DISPATCHING: {
                ((AtomicBoolean) param).set(asyncStateMachine.isAsyncDispatching());
                break;
            }
            case ASYNC_IS_ASYNC: {
                ((AtomicBoolean) param).set(asyncStateMachine.isAsync());
                break;
            }
            case ASYNC_IS_TIMINGOUT: {
                ((AtomicBoolean) param).set(asyncStateMachine.isAsyncTimingOut());
                break;
            }
            case ASYNC_IS_ERROR: {
                ((AtomicBoolean) param).set(asyncStateMachine.isAsyncError());
                break;
            }
            case ASYNC_COMPLETE: {
                socketWrapper.clearDispatches();
                if (asyncStateMachine.asyncComplete()) {
                    endpoint.processSocket(this.socketWrapper, SocketStatus.OPEN_READ, true);
                }
                break;
            }
            case ASYNC_SETTIMEOUT: {
                if (param == null || socketWrapper == null) {
                    return;
                }
                long timeout = (Long) param;
                socketWrapper.setTimeout(timeout);
                break;
            }
            case ASYNC_DISPATCH: {
                if (asyncStateMachine.asyncDispatch()) {
                    endpoint.processSocket(this.socketWrapper, SocketStatus.OPEN_READ, true);
                }
                break;
            }
            case UPGRADE: {
                upgradeToken = (UpgradeToken) param;
                getOutputBuffer().finished = true;
                break;
            }
            case AVAILABLE: {
                request.setAvailable(inputBuffer.available(Boolean.TRUE.equals(param)));
                break;
            }
            case NB_WRITE_INTEREST: {
                AtomicBoolean isReady = (AtomicBoolean) param;
                try {
                    isReady.set(getOutputBuffer().isReady());
                } catch (IOException e) {
                    getLog().debug("isReady() failed", e);
                    setErrorState(ErrorState.CLOSE_NOW, e);
                }
                break;
            }
            case NB_READ_INTEREST: {
                registerForEvent(true, false);
                break;
            }
            case REQUEST_BODY_FULLY_READ: {
                AtomicBoolean result = (AtomicBoolean) param;
                result.set(getInputBuffer().isFinished());
                break;
            }
            case DISPATCH_READ: {
                socketWrapper.addDispatch(DispatchType.NON_BLOCKING_READ);
                break;
            }
            case DISPATCH_WRITE: {
                socketWrapper.addDispatch(DispatchType.NON_BLOCKING_WRITE);
                break;
            }
            case DISPATCH_EXECUTE: {
                SocketWrapper<S> wrapper = socketWrapper;
                if (wrapper != null) {
                    getEndpoint().executeNonBlockingDispatches(wrapper);
                }
                break;
            }
            case CLOSE_NOW: {
                getOutputBuffer().finished = true;
                setErrorState(ErrorState.CLOSE_NOW, null);
                break;
            }
            case END_REQUEST: {
                endRequest();
                break;
            }
            case IS_COMET: {
                AtomicBoolean result = (AtomicBoolean) param;
                result.set(isComet());
                break;
            }
            default: {
                actionInternal(actionCode, param);
                break;
            }
        }
    }

    protected abstract void actionInternal(ActionCode actionCode, Object param);

    protected abstract boolean disableKeepAlive();

    protected abstract void setRequestLineReadTimeout() throws IOException;

    protected abstract boolean handleIncompleteRequestLineRead();

    protected abstract void setSocketTimeout(int timeout) throws IOException;

    @Override
    public SocketState process(SocketWrapper<S> socketWrapper) throws IOException {
        SystemUtil.logInfo(this, "继续跟踪，果然是这句话对实际的文档信息写入做出了最终的判决");
        RequestInfo rp = request.getRequestProcessor();
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);
        setSocketWrapper(socketWrapper);
        getInputBuffer().init(socketWrapper, endpoint);
        getOutputBuffer().init(socketWrapper, endpoint);
        initFlag(socketWrapper);
        if (disableKeepAlive()) {
            socketWrapper.setKeepAliveLeft(0);
        }
        while (!getErrorState().isError() && keepAlive && !comet && !isAsync() && upgradeToken == null && !endpoint.isPaused()) {
            try {
                setRequestLineReadTimeout();
                if (!getInputBuffer().parseRequestLine(keptAlive)) {
                    if (handleIncompleteRequestLineRead()) {
                        break;
                    }
                }
                if (endpoint.isPaused()) {
                    response.setStatus(503);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                } else {
                    keptAlive = true;
                    request.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                    if (!getInputBuffer().parseHeaders()) {
                        openSocket = true;
                        readComplete = false;
                        break;
                    }
                    if (!disableUploadTimeout) {
                        setSocketTimeout(connectionUploadTimeout);
                    }
                }
            } catch (IOException e) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(sm.getString("http11processor.header.parse"), e);
                }
                setErrorState(ErrorState.CLOSE_NOW, e);
                break;
            } catch (Throwable t) {
                badRequestHandler(t);
            }

            if (!getErrorState().isError()) {
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try {
                    prepareRequest();
                } catch (Throwable t) {
                    filterRequestExceptionHandler(t);
                }
            }

            if (maxKeepAliveRequests == 1) {
                keepAlive = false;
            } else if (maxKeepAliveRequests > 0 &&
                    socketWrapper.decrementKeepAlive() <= 0) {
                keepAlive = false;
            }
            if (!getErrorState().isError()) {
                try {
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    getAdapter().service(request, response);
                    if (keepAlive && !getErrorState().isError() && (
                            response.getErrorException() != null ||
                                    (!isAsync() && statusDropsConnection(response.getStatus())))) {
                        setErrorState(ErrorState.CLOSE_CLEAN, null);
                    }
                    setCometTimeouts(socketWrapper);
                } catch (InterruptedIOException e) {
                    setErrorState(ErrorState.CLOSE_NOW, e);
                } catch (HeadersTooLargeException e) {
                    handlerHeaderTooLargeException(e);
                } catch (Throwable t) {
                    handlerHeaderException(t);
                }
            }
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDINPUT);
            if (!isAsync() && !comet) {
                if (getErrorState().isError()) {
                    getInputBuffer().setSwallowInput(false);
                } else {
                    checkExpectationAndResponseStatus();
                }
                endRequest();
            }
            rp.setStage(org.apache.coyote.Constants.STAGE_ENDOUTPUT);
            if (getErrorState().isError()) {
                response.setStatus(500);
            }
            if (!isAsync() && !comet || getErrorState().isError()) {
                request.updateCounters();
                if (getErrorState().isIoAllowed()) {
                    getInputBuffer().nextRequest();
                    getOutputBuffer().nextRequest();
                }
            }

            if (!disableUploadTimeout) {
                if (endpoint.getSoTimeout() > 0) {
                    setSocketTimeout(endpoint.getSoTimeout());
                } else {
                    setSocketTimeout(0);
                }
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

            if (breakKeepAliveLoop(socketWrapper)) {
                break;
            }
        }
        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);
        return ruleSocketStatus();
    }

    private void initFlag(SocketWrapper<S> socketWrapper) {
        keepAlive = true;
        comet = false;
        openSocket = false;
        sendfileInProgress = false;
        readComplete = true;
        if (endpoint.getUsePolling()) {
            keptAlive = false;
        } else {
            keptAlive = socketWrapper.isKeptAlive();
        }
    }

    /**
     * 根据上面的操作情况，裁定状态
     */
    private SocketState ruleSocketStatus() {
        if (getErrorState().isError() || endpoint.isPaused()) {
            return SocketState.CLOSED;
        } else if (isAsync() || comet) {
            return SocketState.LONG;
        } else if (isUpgrade()) {
            return SocketState.UPGRADING;
        } else {
            if (sendfileInProgress) {
                return SocketState.SENDFILE;
            } else {
                if (openSocket) {
                    if (readComplete) {
                        return SocketState.OPEN;
                    } else {
                        return SocketState.LONG;
                    }
                } else {
                    return SocketState.CLOSED;
                }
            }
        }
    }

    private void handlerHeaderException(Throwable t) {
        ExceptionUtils.handleThrowable(t);
        getLog().error(sm.getString("http11processor.request.process"), t);
        // 500 - Internal Server Error
        response.setStatus(500);
        setErrorState(ErrorState.CLOSE_CLEAN, t);
        getAdapter().log(request, response, 0);
    }

    private void handlerHeaderTooLargeException(HeadersTooLargeException e) {
        if (response.isCommitted()) {
            setErrorState(ErrorState.CLOSE_NOW, e);
        } else {
            response.reset();
            response.setStatus(500);
            setErrorState(ErrorState.CLOSE_CLEAN, e);
            response.setHeader("Connection", "close");
        }
    }

    private void filterRequestExceptionHandler(Throwable t) {
        ExceptionUtils.handleThrowable(t);
        if (getLog().isDebugEnabled()) {
            getLog().debug(sm.getString("http11processor.request.prepare"), t);
        }
        response.setStatus(500);
        setErrorState(ErrorState.CLOSE_CLEAN, t);
        getAdapter().log(request, response, 0);
    }

    private void badRequestHandler(Throwable t) {
        ExceptionUtils.handleThrowable(t);
        UserDataHelper.Mode logMode = userDataHelper.getNextMode();
        if (logMode != null) {
            String message = sm.getString("http11processor.header.parse");
            switch (logMode) {
                case INFO_THEN_DEBUG:
                    message += sm.getString("http11processor.fallToDebug");
                case INFO:
                    getLog().info(message, t);
                    break;
                case DEBUG:
                    getLog().debug(message, t);
            }
        }
        response.setStatus(400);
        setErrorState(ErrorState.CLOSE_CLEAN, t);
        getAdapter().log(request, response, 0);
    }


    private void checkExpectationAndResponseStatus() {
        if (expectation && (response.getStatus() < 200 || response.getStatus() > 299)) {
            getInputBuffer().setSwallowInput(false);
            keepAlive = false;
        }
    }

    protected void prepareRequest() {
        http11 = true;
        http09 = false;
        contentDelimitation = false;
        expectation = false;

        prepareRequestInternal();

        if (endpoint.isSSLEnabled()) {
            request.scheme().setString("https");
        }
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11)) {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10)) {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals("")) {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else {
            // Unsupported protocol
            http11 = false;
            // Send 505; Unsupported HTTP version
            response.setStatus(505);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("http11processor.request.prepare") + " Unsupported HTTP version \"" + protocolMB + "\"");
            }
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals(Constants.GET)) {
            methodMB.setString(Constants.GET);
        } else if (methodMB.equals(Constants.POST)) {
            methodMB.setString(Constants.POST);
        }

        MimeHeaders headers = request.getMimeHeaders();

        // Check connection header
        MessageBytes connectionValueMB = headers.getValue(Constants.CONNECTION);
        if (connectionValueMB != null) {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1) {
                keepAlive = false;
            } else if (findBytes(connectionValueBC,
                    Constants.KEEPALIVE_BYTES) != -1) {
                keepAlive = true;
            }
        }

        MessageBytes expectMB = null;
        if (http11) {
            expectMB = headers.getValue("expect");
        }
        if (expectMB != null) {
            if (expectMB.indexOfIgnoreCase("100-continue", 0) != -1) {
                getInputBuffer().setSwallowInput(false);
                expectation = true;
            } else {
                response.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                setErrorState(ErrorState.CLOSE_CLEAN, null);
            }
        }

        // Check user-agent header
        if (restrictedUserAgents != null && (http11 || keepAlive)) {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if (userAgentValueMB != null) {
                String userAgentValue = userAgentValueMB.toString();
                if (restrictedUserAgents != null &&
                        restrictedUserAgents.matcher(userAgentValue).matches()) {
                    http11 = false;
                    keepAlive = false;
                }
            }
        }

        // Check for a full URI (including protocol://host:port/)
        ByteChunk uriBC = request.requestURI().getByteChunk();
        if (uriBC.startsWithIgnoreCase("http", 0)) {

            int pos = uriBC.indexOf("://", 0, 3, 4);
            int uriBCStart = uriBC.getStart();
            int slashPos = -1;
            if (pos != -1) {
                byte[] uriB = uriBC.getBytes();
                slashPos = uriBC.indexOf('/', pos + 3);
                if (slashPos == -1) {
                    slashPos = uriBC.getLength();
                    // Set URI as "/"
                    request.requestURI().setBytes
                            (uriB, uriBCStart + pos + 1, 1);
                } else {
                    request.requestURI().setBytes
                            (uriB, uriBCStart + slashPos,
                                    uriBC.getLength() - slashPos);
                }
                MessageBytes hostMB = headers.setValue("host");
                hostMB.setBytes(uriB, uriBCStart + pos + 3,
                        slashPos - pos - 3);
            }
        }

        // Input filter setup
        InputFilter[] inputFilters = getInputBuffer().getFilters();

        // Parse transfer-encoding header
        MessageBytes transferEncodingValueMB = null;
        if (http11) {
            transferEncodingValueMB = headers.getValue("transfer-encoding");
        }
        if (transferEncodingValueMB != null) {
            String transferEncodingValue = transferEncodingValueMB.toString();
            // Parse the comma separated list. "identity" codings are ignored
            int startPos = 0;
            int commaPos = transferEncodingValue.indexOf(',');
            String encodingName = null;
            while (commaPos != -1) {
                encodingName = transferEncodingValue.substring(startPos, commaPos);
                addInputFilter(inputFilters, encodingName);
                startPos = commaPos + 1;
                commaPos = transferEncodingValue.indexOf(',', startPos);
            }
            encodingName = transferEncodingValue.substring(startPos);
            addInputFilter(inputFilters, encodingName);
        }

        // Parse content-length header
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0) {
            if (contentDelimitation) {
                headers.removeHeader("content-length");
                request.setContentLength(-1);
            } else {
                getInputBuffer().addActiveFilter
                        (inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }

        MessageBytes valueMB = headers.getValue("host");

        // Check host header
        if (http11 && (valueMB == null)) {
            // 400 - Bad request
            response.setStatus(400);
            setErrorState(ErrorState.CLOSE_CLEAN, null);
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("http11processor.request.prepare") +
                        " host header missing");
            }
        }

        parseHost(valueMB);

        if (!contentDelimitation) {
            // If there's no content length
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            getInputBuffer().addActiveFilter
                    (inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        if (getErrorState().isError()) {
            getAdapter().log(request, response, 0);
        }
    }


    /**
     * Connector implementation specific request preparation. Ideally, this will
     * go away in the future.
     */
    protected abstract void prepareRequestInternal();

    /**
     * When committing the response, we have to validate the set of headers, as
     * well as setup the response filters.
     */
    private void prepareResponse() {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = getOutputBuffer().getFilters();

        if (http09 == true) {
            // HTTP/0.9
            getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.IDENTITY_FILTER]);
            return;
        }

        int statusCode = response.getStatus();
        if (statusCode < 200 || statusCode == 204 || statusCode == 205 ||
                statusCode == 304) {
            // No entity body
            getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals("HEAD")) {
            // No entity body
            getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Sendfile support
        boolean sendingWithSendfile = false;
        if (getEndpoint().getUseSendfile()) {
            sendingWithSendfile = prepareSendfile(outputFilters);
        }

        // Check for compression
        boolean isCompressable = false;
        boolean useCompression = false;
        if (entityBody && (compressionLevel > 0) && !sendingWithSendfile) {
            isCompressable = isCompressable();
            if (isCompressable) {
                useCompression = useCompression();
            }
            // Change content-length to -1 to force chunking
            if (useCompression) {
                response.setContentLength(-1);
            }
        }

        MimeHeaders headers = response.getMimeHeaders();
        if (!entityBody) {
            response.setContentLength(-1);
        }
        // A SC_NO_CONTENT response may include entity headers
        if (entityBody || statusCode == HttpServletResponse.SC_NO_CONTENT) {
            String contentType = response.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = response.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language")
                        .setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        boolean connectionClosePresent = false;
        if (contentLength != -1) {
            headers.setValue("Content-Length").setLong(contentLength);
            getOutputBuffer().addActiveFilter
                    (outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else {
            // If the response code supports an entity body and we're on
            // HTTP 1.1 then we chunk unless we have a Connection: close header
            connectionClosePresent = isConnectionClose(headers);
            if (entityBody && http11 && !connectionClosePresent) {
                getOutputBuffer().addActiveFilter
                        (outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else {
                getOutputBuffer().addActiveFilter
                        (outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression) {
            getOutputBuffer().addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
            headers.setValue("Content-Encoding").setString("gzip");
        }
        // If it might be compressed, set the Vary header
        if (isCompressable) {
            // Make Proxies happy via Vary (from mod_deflate)
            MessageBytes vary = headers.getValue("Vary");
            if (vary == null) {
                // Add a new Vary header
                headers.setValue("Vary").setString("Accept-Encoding");
            } else if (vary.equals("*")) {
                // No action required
            } else {
                // Merge into current header
                headers.setValue("Vary").setString(
                        vary.getString() + ",Accept-Encoding");
            }
        }

        // Add date header unless application has already set one (e.g. in a
        // Caching Filter)
        if (headers.getValue("Date") == null) {
            headers.setValue("Date").setString(
                    FastHttpDateFormat.getCurrentDate());
        }

        // FIXME: Add transfer encoding header

        if ((entityBody) && (!contentDelimitation)) {
            // Mark as close the connection after the request, and add the
            // connection: close header
            keepAlive = false;
        }

        // This may disabled keep-alive to check before working out the
        // Connection header.
        checkExpectationAndResponseStatus();

        // If we know that the request is bad this early, add the
        // Connection: close header.
        if (keepAlive && statusDropsConnection(statusCode)) {
            keepAlive = false;
        }
        if (!keepAlive) {
            // Avoid adding the close header twice
            if (!connectionClosePresent) {
                headers.addValue(Constants.CONNECTION).setString(
                        Constants.CLOSE);
            }
        } else if (!http11 && !getErrorState().isError()) {
            headers.addValue(Constants.CONNECTION).setString(Constants.KEEPALIVE);
        }

        // Build the response header
        getOutputBuffer().sendStatus();

        // Add server header
        if (server != null) {
            // Always overrides anything the app might set
            headers.setValue("Server").setString(server);
        } else if (headers.getValue("Server") == null) {
            // If app didn't set the header, use the default
            getOutputBuffer().write(Constants.SERVER_BYTES);
        }

        int size = headers.size();
        for (int i = 0; i < size; i++) {
            getOutputBuffer().sendHeader(headers.getName(i), headers.getValue(i));
        }
        getOutputBuffer().endHeaders();

    }

    private boolean isConnectionClose(MimeHeaders headers) {
        MessageBytes connection = headers.getValue(Constants.CONNECTION);
        if (connection == null) {
            return false;
        }
        return connection.equals(Constants.CLOSE);
    }

    protected abstract boolean prepareSendfile(OutputFilter[] outputFilters);

    /**
     * Parse host.
     */
    protected void parseHost(MessageBytes valueMB) {

        if (valueMB == null || valueMB.isNull()) {
            // HTTP/1.0
            // If no host header, use the port info from the endpoint
            // The host will be obtained lazily from the socket if required
            // using ActionCode#REQ_LOCAL_NAME_ATTRIBUTE
            request.setServerPort(endpoint.getPort());
            return;
        }

        ByteChunk valueBC = valueMB.getByteChunk();
        byte[] valueB = valueBC.getBytes();
        int valueL = valueBC.getLength();
        int valueS = valueBC.getStart();
        int colonPos = -1;
        if (hostNameC.length < valueL) {
            hostNameC = new char[valueL];
        }

        boolean ipv6 = (valueB[valueS] == '[');
        boolean bracketClosed = false;
        for (int i = 0; i < valueL; i++) {
            char b = (char) valueB[i + valueS];
            hostNameC[i] = b;
            if (b == ']') {
                bracketClosed = true;
            } else if (b == ':') {
                if (!ipv6 || bracketClosed) {
                    colonPos = i;
                    break;
                }
            }
        }

        if (colonPos < 0) {
            if (!endpoint.isSSLEnabled()) {
                // 80 - Default HTTP port
                request.setServerPort(80);
            } else {
                // 443 - Default HTTPS port
                request.setServerPort(443);
            }
            request.serverName().setChars(hostNameC, 0, valueL);
        } else {
            request.serverName().setChars(hostNameC, 0, colonPos);

            int port = 0;
            int mult = 1;
            for (int i = valueL - 1; i > colonPos; i--) {
                int charValue = HexUtils.getDec(valueB[i + valueS]);
                if (charValue == -1 || charValue > 9) {
                    // Invalid character
                    // 400 - Bad request
                    response.setStatus(400);
                    setErrorState(ErrorState.CLOSE_CLEAN, null);
                    break;
                }
                port = port + (charValue * mult);
                mult = 10 * mult;
            }
            request.setServerPort(port);
        }

    }


    @Override
    public SocketState asyncDispatch(SocketStatus status) {

        if (status == SocketStatus.OPEN_WRITE && response.getWriteListener() != null) {
            try {
                asyncStateMachine.asyncOperation();

                if (outputBuffer.hasDataToWrite()) {
                    if (outputBuffer.flushBuffer(false)) {
                        // The buffer wasn't fully flushed so re-register the
                        // socket for write. Note this does not go via the
                        // Response since the write registration state at
                        // that level should remain unchanged. Once the buffer
                        // has been emptied then the code below will call
                        // Adaptor.asyncDispatch() which will enable the
                        // Response to respond to this event.
                        outputBuffer.registerWriteInterest();
                        return SocketState.LONG;
                    }
                }
            } catch (IOException | IllegalStateException x) {
                // IOE - Problem writing to socket
                // ISE - Request/Response not in correct state for async write
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Unable to write async data.", x);
                }
                status = SocketStatus.ASYNC_WRITE_ERROR;
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, x);
            }
        } else if (status == SocketStatus.OPEN_READ && request.getReadListener() != null) {
            try {
                // Check of asyncStateMachine.isAsyncStarted() is to avoid issue
                // with BIO. Because it can't do a non-blocking read, BIO always
                // returns available() == 1. This causes a problem here at the
                // end of a non-blocking read. See BZ 57481.
                if (asyncStateMachine.isAsyncStarted()) {
                    asyncStateMachine.asyncOperation();
                }
            } catch (IllegalStateException x) {
                // ISE - Request/Response not in correct state for async read
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Unable to read async data.", x);
                }
                status = SocketStatus.ASYNC_READ_ERROR;
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, x);
            }
        }

        RequestInfo rp = request.getRequestProcessor();
        try {
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            if (!getAdapter().asyncDispatch(request, response, status)) {
                setErrorState(ErrorState.CLOSE_NOW, null);
            }
            resetTimeouts();
        } catch (InterruptedIOException e) {
            setErrorState(ErrorState.CLOSE_NOW, e);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            setErrorState(ErrorState.CLOSE_NOW, t);
            getLog().error(sm.getString("http11processor.request.process"), t);
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (getErrorState().isError()) {
            request.updateCounters();
            return SocketState.CLOSED;
        } else if (isAsync()) {
            return SocketState.LONG;
        } else {
            request.updateCounters();
            if (!keepAlive) {
                return SocketState.CLOSED;
            } else {
                getInputBuffer().nextRequest();
                getOutputBuffer().nextRequest();
                return SocketState.OPEN;
            }
        }
    }


    @Override
    public boolean isComet() {
        return comet;
    }


    @Override
    public boolean isUpgrade() {
        return upgradeToken != null;
    }


    @Override
    public SocketState upgradeDispatch(SocketStatus status) throws IOException {
        // Should never reach this code but in case we do...
        throw new IllegalStateException(
                sm.getString("http11Processor.upgrade"));
    }


    @Override
    public UpgradeToken getUpgradeToken() {
        return upgradeToken;
    }

    protected abstract void resetTimeouts();

    protected abstract void setCometTimeouts(SocketWrapper<S> socketWrapper);

    public void endRequest() {

        // Finish the handling of the request
        if (getErrorState().isIoAllowed()) {
            try {
                getInputBuffer().endRequest();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                // 500 - Internal Server Error
                // Can't add a 500 to the access log since that has already been
                // written in the Adapter.service method.
                response.setStatus(500);
                setErrorState(ErrorState.CLOSE_NOW, t);
                getLog().error(sm.getString("http11processor.request.finish"), t);
            }
        }
        if (getErrorState().isIoAllowed()) {
            try {
                getOutputBuffer().endRequest();
            } catch (IOException e) {
                setErrorState(ErrorState.CLOSE_NOW, e);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                setErrorState(ErrorState.CLOSE_NOW, t);
                getLog().error(sm.getString("http11processor.response.finish"), t);
            }
        }
    }


    /**
     * Checks to see if the keep-alive loop should be broken, performing any
     * processing (e.g. sendfile handling) that may have an impact on whether
     * or not the keep-alive loop should be broken.
     *
     * @return true if the keep-alive loop should be broken
     */
    protected abstract boolean breakKeepAliveLoop(
            SocketWrapper<S> socketWrapper);


    @Override
    public final void recycle(boolean isSocketClosing) {
        getAdapter().checkRecycled(request, response);

        if (getInputBuffer() != null) {
            getInputBuffer().recycle();
        }
        if (getOutputBuffer() != null) {
            getOutputBuffer().recycle();
        }
        if (asyncStateMachine != null) {
            asyncStateMachine.recycle();
        }
        upgradeToken = null;
        comet = false;
        resetErrorState();
        recycleInternal();
    }

    protected abstract void recycleInternal();


    @Override
    public ByteBuffer getLeftoverInput() {
        return inputBuffer.getLeftover();
    }

}
