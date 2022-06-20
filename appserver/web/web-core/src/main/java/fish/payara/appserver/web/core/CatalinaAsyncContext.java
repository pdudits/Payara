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

import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.core.AsyncContextImpl;
import org.apache.coyote.ActionCode;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.http.server.Response;

public class CatalinaAsyncContext extends AsyncContextImpl {
    private volatile CatalinaRequest catalinaRequest;

    public CatalinaAsyncContext(CatalinaRequest request) {
        super(request);
        this.catalinaRequest = request;
    }

    @Override
    public void recycle() {
        super.recycle();
        this.catalinaRequest = null;
    }

    @Override
    public void complete() {
        super.complete();
        // we should probably check the state machine and have the response resume on correct thread
        if (!catalinaRequest.getCoyoteRequest().isRequestThread()) {
            // it's not safe to just mark response as resumed
            catalinaRequest.getGrizzlyRequest().getResponse().resume();
        } else {
            final Response.SuspendedContextImpl suspendContext = (Response.SuspendedContextImpl) catalinaRequest.getGrizzlyRequest().getResponse().getSuspendContext();

            suspendContext.markResumed();
            suspendContext.getSuspendStatus().reset();
        }
    }

    @Override
    public void setStarted(Context context, ServletRequest request, ServletResponse response, boolean originalRequestResponse) {
        super.setStarted(context, request, response, originalRequestResponse);
        catalinaRequest.getResponse().getGrizzlyResponse().suspend(-1, TimeUnit.MILLISECONDS,
                new EmptyCompletionHandler<>(){
                    @Override
                    public void completed(Response result) {
                        fireOnComplete();
                    }
                }, (x) -> fireTimeout());
    }

    private boolean fireTimeout() {
        // we want to notify listeners and timeout, even if the original server method didn't finish
        // (async machine state is starting). We have no way of finding that out, so let's just do
        try {
            return timeout();
        } catch (IllegalStateException iae) {
            if (isStarted()) {
                // we're in async mode STARTING, let's move it to STARTED mode with postAsync action
                catalinaRequest.getCoyoteRequest().action(ActionCode.ASYNC_POST_PROCESS, null);
                // and try again
                return timeout();
            }
            throw iae;
        }
    }

    @Override
    public void setTimeout(long timeout) {
        super.setTimeout(timeout);
        catalinaRequest.getGrizzlyRequest().getResponse().getSuspendContext().setTimeout(timeout, TimeUnit.MILLISECONDS);
    }
}
