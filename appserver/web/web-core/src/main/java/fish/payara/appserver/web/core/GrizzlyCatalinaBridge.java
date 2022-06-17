/*
 *
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *  Copyright (c) 2022 Payara Foundation and/or its affiliates. All rights reserved.
 *
 *  The contents of this file are subject to the terms of either the GNU
 *  General Public License Version 2 only ("GPL") or the Common Development
 *  and Distribution License("CDDL") (collectively, the "License").  You
 *  may not use this file except in compliance with the License.  You can
 *  obtain a copy of the License at
 *  https://github.com/payara/Payara/blob/master/LICENSE.txt
 *  See the License for the specific
 *  language governing permissions and limitations under the License.
 *
 *  When distributing the software, include this License Header Notice in each
 *  file and include the License file at glassfish/legal/LICENSE.txt.
 *
 *  GPL Classpath Exception:
 *  The Payara Foundation designates this particular file as subject to the "Classpath"
 *  exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *  file that accompanied this code.
 *
 *  Modifications:
 *  If applicable, add the following below the License Header, with the fields
 *  enclosed by brackets [] replaced by your own identifying information:
 *  "Portions Copyright [year] [name of copyright owner]"
 *
 *  Contributor(s):
 *  If you wish your version of this file to be governed by only the CDDL or
 *  only the GPL Version 2, indicate your decision by adding "[Contributor]
 *  elects to include this software in this distribution under the [CDDL or GPL
 *  Version 2] license."  If you don't indicate a single choice of license, a
 *  recipient has the option to distribute your version of this file under
 *  either the CDDL, the GPL Version 2 or to extend the choice of license to
 *  its licensees as provided above.  However, if you add GPL Version 2 code
 *  and therefore, elected the GPL Version 2 license, then the option applies
 *  only if the new code is made subject to such option by the copyright
 *  holder.
 *
 */

package fish.payara.appserver.web.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.WriteListener;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.AsyncContextImpl;
import org.apache.catalina.util.SessionConfig;
import org.apache.catalina.util.URLEncoder;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.juli.logging.Log;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Note;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.SessionManager;

import static org.apache.catalina.connector.CoyoteAdapter.ADAPTER_NOTES;

/**
 * Passes requests from Grizzly into Catalina engine
 */
public class GrizzlyCatalinaBridge extends HttpHandler {
    private static final Logger LOG = Logger.getLogger(GrizzlyCatalinaBridge.class.getName());

    /**
     * Position of grizzly request within coyote adapter notes
     */
    static final int GRIZZLY_NOTE = 9;

    static final Note<CatalinaRequest> CATALINA_REQUEST = Request.createNote(CatalinaRequest.class.getName());

    static final Note<Processor> PROCESSOR = Request.createNote(Processor.class.getName());

    private static final ThreadLocal<String> THREAD_NAME =
            ThreadLocal.withInitial(() -> Thread.currentThread().getName());

    private static final ThreadLocal<Processor> CACHED_PROCESSOR = new ThreadLocal<>();

    private final GrizzlyConnector connector;

    private final GrizzlyAdapter adapter = new GrizzlyAdapter();

    private final CatalinaSessionManagerBridge sessionManager = new CatalinaSessionManagerBridge();


    public GrizzlyCatalinaBridge(GrizzlyConnector grizzlyConnector) {
        this.connector = grizzlyConnector;
    }

    @Override
    public String getName() {
        return "CatalinaBridge";
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        var processor = getProcessor(request, response);

        var result = processor.process(null, SocketEvent.OPEN_READ);

        if (result != AbstractEndpoint.Handler.SocketState.LONG) {
            processor.recycle();
        }
    }

    private Processor getProcessor(Request request, Response response) {
        return cache.get(request, response);
    }

    private ProcessorCache cache = new ProcessorCache(16, (request, response) -> new Processor(adapter, request, response));

    static class ProcessorCache {
        private final BiFunction<Request, Response, Processor> instantiator;

        private BlockingQueue<Processor> cache;

        ProcessorCache(int capacity, BiFunction<Request, Response, Processor> instantiator) {
            this.cache = new ArrayBlockingQueue<>(capacity);
            this.instantiator = instantiator;
        }

        Processor get(Request request, Response response) {
            var cached = cache.poll();
            if (cached != null) {
                cached.initialize(request, response);
                return cached;
            }
            return instantiator.apply(request, response);
        }

        void recycled(Processor p) {
            cache.offer(p);
        }
    }

    // we bridge to catalina's session manager
    @Override
    protected SessionManager getSessionManager(Request request) {
        // Session manager is determined before service is called and therefore mapping data is filled in.
        return sessionManager;
    }


    /**
     * Adapter executes Coyote requests in servlet container.
     */
    final class GrizzlyAdapter implements Adapter {
        @Override
        public void service(org.apache.coyote.Request request, org.apache.coyote.Response response) throws Exception {
            throw new UnsupportedOperationException("This is not the entrypoint in our impl");
        }

        @Override
        public boolean prepare(org.apache.coyote.Request request, org.apache.coyote.Response response) throws Exception {
            throw new UnsupportedOperationException("Not implemented yet");
        }

        @Override
        public boolean asyncDispatch(org.apache.coyote.Request req, org.apache.coyote.Response res, SocketEvent status) throws Exception {
            org.apache.catalina.connector.Request request = (org.apache.catalina.connector.Request) req.getNote(ADAPTER_NOTES);
            org.apache.catalina.connector.Response response = (org.apache.catalina.connector.Response) res.getNote(ADAPTER_NOTES);

            // this is what CoyoteAdapter is doing
            if (request == null) {
                throw new IllegalStateException("No request found");
            }

            boolean success = true;
            AsyncContextImpl asyncConImpl = request.getAsyncContextInternal();

            req.getRequestProcessor().setWorkerThreadName(THREAD_NAME.get());
            req.setRequestThread();

            try {
                if (!request.isAsync()) {
                    // Error or timeout
                    // Lift any suspension (e.g. if sendError() was used by an async
                    // request) to allow the response to be written to the client
                    response.setSuspended(false);
                }

                if (status==SocketEvent.TIMEOUT) {
                    if (!asyncConImpl.timeout()) {
                        asyncConImpl.setErrorState(null, false);
                    }
                } else if (status==SocketEvent.ERROR) {
                    // An I/O error occurred on a non-container thread which means
                    // that the socket needs to be closed so set success to false to
                    // trigger a close
                    success = false;
                    Throwable t = (Throwable)req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                    Context context = request.getContext();
                    ClassLoader oldCL = null;
                    try {
                        oldCL = context.bind(false, null);
                        if (req.getReadListener() != null) {
                            req.getReadListener().onError(t);
                        }
                        if (res.getWriteListener() != null) {
                            res.getWriteListener().onError(t);
                        }
                        res.action(ActionCode.CLOSE_NOW, t);
                        asyncConImpl.setErrorState(t, true);
                    } finally {
                        context.unbind(false, oldCL);
                    }
                }

                // Check to see if non-blocking writes or reads are being used
                if (!request.isAsyncDispatching() && request.isAsync()) {
                    WriteListener writeListener = res.getWriteListener();
                    ReadListener readListener = req.getReadListener();
                    if (writeListener != null && status == SocketEvent.OPEN_WRITE) {
                        Context context = request.getContext();
                        ClassLoader oldCL = null;
                        try {
                            oldCL = context.bind(false, null);
                            res.onWritePossible();
                            if (request.isFinished() && req.sendAllDataReadEvent() &&
                                    readListener != null) {
                                readListener.onAllDataRead();
                            }
                            // User code may have swallowed an IOException
                            if (response.getCoyoteResponse().isExceptionPresent()) {
                                throw response.getCoyoteResponse().getErrorException();
                            }
                        } catch (Throwable t) {
                            ExceptionUtils.handleThrowable(t);
                            // Need to trigger the call to AbstractProcessor.setErrorState()
                            // before the listener is called so the listener can call complete
                            // Therefore no need to set success=false as that would trigger a
                            // second call to AbstractProcessor.setErrorState()
                            // https://bz.apache.org/bugzilla/show_bug.cgi?id=65001
                            writeListener.onError(t);
                            res.action(ActionCode.CLOSE_NOW, t);
                            asyncConImpl.setErrorState(t, true);
                        } finally {
                            context.unbind(false, oldCL);
                        }
                    } else if (readListener != null && status == SocketEvent.OPEN_READ) {
                        Context context = request.getContext();
                        ClassLoader oldCL = null;
                        try {
                            oldCL = context.bind(false, null);
                            // If data is being read on a non-container thread a
                            // dispatch with status OPEN_READ will be used to get
                            // execution back on a container thread for the
                            // onAllDataRead() event. Therefore, make sure
                            // onDataAvailable() is not called in this case.
                            if (!request.isFinished()) {
                                req.onDataAvailable();
                            }
                            if (request.isFinished() && req.sendAllDataReadEvent()) {
                                readListener.onAllDataRead();
                            }
                            // User code may have swallowed an IOException
                            if (request.getCoyoteRequest().isExceptionPresent()) {
                                throw request.getCoyoteRequest().getErrorException();
                            }
                        } catch (Throwable t) {
                            ExceptionUtils.handleThrowable(t);
                            // Need to trigger the call to AbstractProcessor.setErrorState()
                            // before the listener is called so the listener can call complete
                            // Therefore no need to set success=false as that would trigger a
                            // second call to AbstractProcessor.setErrorState()
                            // https://bz.apache.org/bugzilla/show_bug.cgi?id=65001
                            readListener.onError(t);
                            res.action(ActionCode.CLOSE_NOW, t);
                            asyncConImpl.setErrorState(t, true);
                        } finally {
                            context.unbind(false, oldCL);
                        }
                    }
                }

                // Has an error occurred during async processing that needs to be
                // processed by the application's error page mechanism (or Tomcat's
                // if the application doesn't define one)?
                if (!request.isAsyncDispatching() && request.isAsync() &&
                        response.isErrorReportRequired()) {
                    connector.getService().getContainer().getPipeline().getFirst().invoke(
                            request, response);
                }

                if (request.isAsyncDispatching()) {
                    connector.getService().getContainer().getPipeline().getFirst().invoke(
                            request, response);
                    Throwable t = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
                    if (t != null) {
                        asyncConImpl.setErrorState(t, true);
                    }
                }

                if (!request.isAsync()) {
                    request.finishRequest();
                    response.finishResponse();
                }

                // Check to see if the processor is in an error state. If it is,
                // bail out now.
                AtomicBoolean error = new AtomicBoolean(false);
                res.action(ActionCode.IS_ERROR, error);
                if (error.get()) {
                    if (request.isAsyncCompleting()) {
                        // Connection will be forcibly closed which will prevent
                        // completion happening at the usual point. Need to trigger
                        // call to onComplete() here.
                        res.action(ActionCode.ASYNC_POST_PROCESS,  null);
                    }
                    success = false;
                }
            } catch (IOException e) {
                success = false;
                // Ignore
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                success = false;
                LOG.log(Level.SEVERE, "Async Dispatch Failed", t);
            } finally {
                if (!success) {
                    res.setStatus(500);
                }

                // Access logging
                if (!success || !request.isAsync()) {
                    long time = 0;
                    if (req.getStartTimeNanos() != -1) {
                        time = System.nanoTime() - req.getStartTimeNanos();
                    }
                    Context context = request.getContext();
                    if (context != null) {
                        context.logAccess(request, response, time, false);
                    } else {
                        log(req, res, time);
                    }
                }

                req.getRequestProcessor().setWorkerThreadName(null);
                // Recycle the wrapper request and response
                if (!success || !request.isAsync()) {
                    request.recycle();
                    response.recycle();
                }
            }
            return success;
        }

        @Override
        public void log(org.apache.coyote.Request request, org.apache.coyote.Response response, long time) {

        }

        @Override
        public void checkRecycled(org.apache.coyote.Request request, org.apache.coyote.Response response) {

        }

        @Override
        public String getDomain() {
            return connector.getDomain();
        }
    }


    /**
     * Processor is three-way junction between grizzly, coyote and Catalina, which keeps all classes bound together
     * in sync.
     * Grzilly doesn't do good job at limiting allocations, therefore processor can be recycled to serve other grizzly
     * request/response pair
     */
    final class Processor extends AbstractProcessor {
        private Request grizzlyRequest;

        private Response grizzlyResponse;

        private final CatalinaRequest catalinaRequest;

        private final CatalinaResponse catalinaResponse;


        protected Processor(Adapter adapter, Request grizzlyRequest, Response grizzlyResponse) {
            super(adapter);
            this.catalinaRequest = new CatalinaRequest(connector);
            this.catalinaResponse = new CatalinaResponse();
            request.setNote(ADAPTER_NOTES, catalinaRequest);
            response.setNote(ADAPTER_NOTES, catalinaResponse);
            catalinaResponse.setRequest(catalinaRequest);
            catalinaRequest.setResponse(catalinaResponse);
            initialize(grizzlyRequest, grizzlyResponse);
        }

        void initialize(Request grizzlyRequest, Response grizzlyResponse) {
            this.grizzlyRequest = grizzlyRequest;
            this.grizzlyResponse = grizzlyResponse;
            request.setNote(GRIZZLY_NOTE, grizzlyRequest);
            response.setNote(GRIZZLY_NOTE, grizzlyResponse);
            catalinaRequest.setRequests(request, grizzlyRequest);
            catalinaResponse.setResponses(response, grizzlyResponse);
            grizzlyRequest.setNote(CATALINA_REQUEST, catalinaRequest);
            request.setRequestThread();
        }

        @Override
        public void recycle() {
            super.recycle();
            getRequest().recycle();
            getResponse().recycle();
            catalinaRequest.recycle();
            catalinaResponse.recycle();
            request.setNote(GRIZZLY_NOTE, null);
            response.setNote(GRIZZLY_NOTE, null);
            catalinaRequest.setRequests(request, null);
            catalinaResponse.setResponses(response, null);
            cache.recycled(this);
        }

        private org.apache.coyote.Response getResponse() {
            return response;
        }

        @Override
        protected void prepareResponse() throws IOException {

        }

        @Override
        protected void finishResponse() throws IOException {
            grizzlyResponse.finish();
        }

        @Override
        protected void ack(ContinueResponseTiming continueResponseTiming) {
            try {
                grizzlyResponse.sendAcknowledgement();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void flush() throws IOException {
            grizzlyResponse.flush();
        }

        @Override
        protected int available(boolean read) {
            int available = grizzlyRequest.getInputBuffer().available();
            if (available <= 0 && read) {
                grizzlyRequest.getInputBuffer().readBuffer();
                return grizzlyRequest.getInputBuffer().available();
            }
            return available;
        }

        @Override
        protected void setRequestBody(ByteChunk byteChunk) {

        }

        @Override
        protected void setSwallowResponse() {

        }

        @Override
        protected void disableSwallowRequest() {

        }

        @Override
        protected boolean isRequestBodyFullyRead() {
            return false;
        }

        @Override
        protected void registerReadInterest() {

        }

        @Override
        protected boolean isReadyForWrite() {
            return grizzlyResponse.getOutputBuffer().canWrite();
        }

        @Override
        protected boolean isTrailerFieldsReady() {
            return grizzlyRequest.areTrailersAvailable();
        }

        @Override
        protected boolean flushBufferedWrite() throws IOException {
            return false;
        }

        @Override
        protected AbstractEndpoint.Handler.SocketState dispatchEndRequest() throws IOException {
            throw new UnsupportedOperationException("No idea what to do");
        }

        @Override
        protected AbstractEndpoint.Handler.SocketState service(SocketWrapperBase<?> socketWrapperBase) throws IOException {
            // this is what adapter normally does

            if (connector.getXpoweredBy()) {
                grizzlyResponse.addHeader("X-Powered-By", "Grizllyote");
            }

            boolean async = false;
            boolean postParseSuccess = false;

            getRequest().getRequestProcessor().setWorkerThreadName(THREAD_NAME.get());
            getRequest().setRequestThread();

            // this is from CoyoteAdapter.service
            try {
                // Parse and set Catalina and configuration specific
                // request parameters
                postParseSuccess = parseRequest();
                if (postParseSuccess) {
                    //check valves if we support async
                    catalinaRequest.setAsyncSupported(
                            connector.getService().getContainer().getPipeline().isAsyncSupported());
                    // Calling the container
                    connector.getService().getContainer().getPipeline().getFirst().invoke(
                            catalinaRequest, catalinaResponse);
                }
                if (catalinaRequest.isAsync()) {
                    async = true;
                    ReadListener readListener = getRequest().getReadListener();
                    if (readListener != null && catalinaRequest.isFinished()) {
                        // Possible the all data may have been read during service()
                        // method so this needs to be checked here
                        ClassLoader oldCL = null;
                        try {
                            oldCL = catalinaRequest.getContext().bind(false, null);
                            if (getRequest().sendAllDataReadEvent()) {
                                getRequest().getReadListener().onAllDataRead();
                            }
                        } finally {
                            catalinaRequest.getContext().unbind(false, oldCL);
                        }
                    }

                    Throwable throwable =
                            (Throwable) catalinaRequest.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

                    // If an async request was started, is not going to end once
                    // this container thread finishes and an error occurred, trigger
                    // the async error process
                    if (!catalinaRequest.isAsyncCompleting() && throwable != null) {
                        catalinaRequest.getAsyncContextInternal().setErrorState(throwable, true);
                    }
                } else {
                    catalinaRequest.finishRequest();
                    catalinaResponse.finishResponse();
                }

            } catch (IOException | ServletException e) {
                // Ignore
            } finally {
                AtomicBoolean error = new AtomicBoolean(false);
                getResponse().action(ActionCode.IS_ERROR, error);

                if (catalinaRequest.isAsyncCompleting() && error.get()) {
                    // Connection will be forcibly closed which will prevent
                    // completion happening at the usual point. Need to trigger
                    // call to onComplete() here.
                    getResponse().action(ActionCode.ASYNC_POST_PROCESS, null);
                    async = false;
                }

                // Access log
                if (!async && postParseSuccess) {
                    // Log only if processing was invoked.
                    // If postParseRequest() failed, it has already logged it.
                    Context context = catalinaRequest.getContext();
                    Host host = catalinaRequest.getHost();
                    // If the context is null, it is likely that the endpoint was
                    // shutdown, this connection closed and the request recycled in
                    // a different thread. That thread will have updated the access
                    // log so it is OK not to update the access log here in that
                    // case.
                    // The other possibility is that an error occurred early in
                    // processing and the request could not be mapped to a Context.
                    // Log via the host or engine in that case.
                    long time = System.nanoTime() - getRequest().getStartTimeNanos();
                    if (context != null) {
                        context.logAccess(catalinaRequest, catalinaResponse, time, false);
                    } else if (grizzlyResponse.isError()) {
                        if (host != null) {
                            host.logAccess(catalinaRequest, catalinaResponse, time, false);
                        } else {
                            connector.getService().getContainer().logAccess(
                                    catalinaRequest, catalinaResponse, time, false);
                        }
                    }
                }

                getRequest().getRequestProcessor().setWorkerThreadName(null);
            }
            if (async) {
                return AbstractEndpoint.Handler.SocketState.LONG;
            } else {
                return AbstractEndpoint.Handler.SocketState.CLOSED;
            }
        }

        @Override
        protected Log getLog() {
            return connector.getService().getContainer().getLogger();
        }

        @Override
        public void pause() {

        }

        // See CoyoteAdapter.postParseRequest
        boolean parseRequest() throws IOException, ServletException {
            // If the processor has set the scheme (AJP does this, HTTP does this if
            // SSL is enabled) use this to set the secure flag as well. If the
            // processor hasn't set it, use the settings from the connector
            catalinaRequest.setSecure(grizzlyRequest.isSecure());

            // At this point the Host header has been processed.
            // Override if the proxyPort/proxyHost are set
            String proxyName = connector.getProxyName();
            int proxyPort = connector.getProxyPort();
            getRequest().setServerPort(grizzlyRequest.getServerPort());
            if (proxyName != null) {
                getRequest().serverName().setString(proxyName);
            }

            String undecodedURI = grizzlyRequest.getRequestURI();

            // Check for ping OPTIONS * request
            if (undecodedURI.equals("*")) {
                if (grizzlyRequest.getMethod() == Method.OPTIONS) {
                    StringBuilder allow = new StringBuilder();
                    allow.append("GET, HEAD, POST, PUT, DELETE, OPTIONS");
                    // Trace if allowed
                    if (connector.getAllowTrace()) {
                        allow.append(", TRACE");
                    }
                    grizzlyResponse.setHeader("Allow", allow.toString());
                    // Access log entry as processing won't reach AccessLogValve
                    connector.getService().getContainer().logAccess(catalinaRequest, catalinaResponse, 0, true);
                    return false;
                } else {
                    grizzlyResponse.sendError(400, "Invalid URI");
                }
            }

            String decodedUriString = grizzlyRequest.getDecodedRequestURI();
            getRequest().decodedURI().setString(decodedUriString);

            // Request mapping.
            String decodedServerName;
            if (connector.getUseIPVHosts()) {
                decodedServerName = grizzlyRequest.getLocalName();
            } else {
                decodedServerName = grizzlyRequest.getServerName();
            }
            getRequest().serverName().setString(decodedServerName);


            // Version for the second mapping loop and
            // Context that we expect to get for that version
            String version = null;
            Context versionContext = null;
            boolean mapRequired = true;

            while (mapRequired) {

                // This will map the the latest version by default
                connector.getService().getMapper().map(getRequest().serverName(), getRequest().decodedURI(),
                        version, catalinaRequest.getMappingData());

                // If there is no context at this point, either this is a 404
                // because no ROOT context has been deployed or the URI was invalid
                // so no context could be mapped.
                if (catalinaRequest.getContext() == null) {
                    // Allow processing to continue.
                    // If present, the rewrite Valve may rewrite this to a valid
                    // request.
                    // The StandardEngineValve will handle the case of a missing
                    // Host and the StandardHostValve the case of a missing Context.
                    // If present, the error reporting valve will provide a response
                    // body.
                    return true;
                }

                // Now we have the context, we can parse the session ID from the URL
                // (if any). Need to do this before we redirect in case we need to
                // include the session id in the redirect
                String sessionID;
                if (catalinaRequest.getServletContext().getEffectiveSessionTrackingModes()
                        .contains(SessionTrackingMode.URL)) {

                    // TODO: Where are path (matrix) parameters processed?
                    // Get the session ID if there was one
                    sessionID = request.getPathParameter(
                            SessionConfig.getSessionUriParamName(
                                    catalinaRequest.getContext()));
                    if (sessionID != null) {
                        catalinaRequest.setRequestedSessionId(sessionID);
                        catalinaRequest.setRequestedSessionURL(true);
                    }
                }

                // Look for session ID in cookies and SSL session
//                try {
//                    parseSessionCookiesId(request);
//                } catch (IllegalArgumentException e) {
//                    // Too many cookies
//                    if (!response.isError()) {
//                        response.setError();
//                        response.sendError(400);
//                    }
//                    return true;
//                }
//                parseSessionSslId(request);

                sessionID = catalinaRequest.getRequestedSessionId();

                mapRequired = false;
                if (version != null && catalinaRequest.getContext() == versionContext) {
                    // We got the version that we asked for. That is it.
                } else {
                    version = null;
                    versionContext = null;

                    Context[] contexts = catalinaRequest.getMappingData().contexts;
                    // Single contextVersion means no need to remap
                    // No session ID means no possibility of remap
                    if (contexts != null && sessionID != null) {
                        // Find the context associated with the session
                        for (int i = contexts.length; i > 0; i--) {
                            Context ctxt = contexts[i - 1];
                            if (ctxt.getManager().findSession(sessionID) != null) {
                                // We found a context. Is it the one that has
                                // already been mapped?
                                if (!ctxt.equals(catalinaRequest.getMappingData().context)) {
                                    // Set version so second time through mapping
                                    // the correct context is found
                                    version = ctxt.getWebappVersion();
                                    versionContext = ctxt;
                                    // Reset mapping
                                    catalinaRequest.getMappingData().recycle();
                                    mapRequired = true;
                                    // Recycle cookies and session info in case the
                                    // correct context is configured with different
                                    // settings
                                    catalinaRequest.recycleSessionInfo();
                                    catalinaRequest.recycleCookieInfo(true);
                                }
                                break;
                            }
                        }
                    }
                }

                if (!mapRequired && catalinaRequest.getContext().getPaused()) {
                    // Found a matching context but it is paused. Mapping data will
                    // be wrong since some Wrappers may not be registered at this
                    // point.
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Should never happen
                    }
                    // Reset mapping
                    catalinaRequest.getMappingData().recycle();
                    mapRequired = true;
                }
            }

            // Possible redirect
            MessageBytes redirectPathMB = catalinaRequest.getMappingData().redirectPath;
            if (!redirectPathMB.isNull()) {
                String redirectPath = URLEncoder.DEFAULT.encode(
                        redirectPathMB.toString(), StandardCharsets.UTF_8);
                String query = catalinaRequest.getQueryString();
                if (catalinaRequest.isRequestedSessionIdFromURL()) {
                    // This is not optimal, but as this is not very common, it
                    // shouldn't matter
                    redirectPath = redirectPath + ";" +
                            SessionConfig.getSessionUriParamName(
                                    catalinaRequest.getContext()) +
                            "=" + catalinaRequest.getRequestedSessionId();
                }
                if (query != null) {
                    // This is not optimal, but as this is not very common, it
                    // shouldn't matter
                    redirectPath = redirectPath + "?" + query;
                }
                grizzlyResponse.sendRedirect(redirectPath);
                catalinaRequest.getContext().logAccess(catalinaRequest, catalinaResponse, 0, true);
                return false;
            }

            // Filter trace method
            if (!connector.getAllowTrace()
                    && grizzlyRequest.getMethod() == Method.TRACE) {
                Wrapper wrapper = catalinaRequest.getWrapper();
                String header = null;
                if (wrapper != null) {
                    String[] methods = wrapper.getServletMethods();
                    if (methods != null) {
                        for (String method : methods) {
                            if ("TRACE".equals(method)) {
                                continue;
                            }
                            if (header == null) {
                                header = method;
                            } else {
                                header += ", " + method;
                            }
                        }
                    }
                }
                if (header != null) {
                    grizzlyResponse.addHeader("Allow", header);
                }
                grizzlyResponse.sendError(405, "TRACE method is not allowed");
                // Safe to skip the remainder of this method.
                return true;
            }

            //doConnectorAuthenticationAuthorization(req, request);

            return true;
        }
    }
}
