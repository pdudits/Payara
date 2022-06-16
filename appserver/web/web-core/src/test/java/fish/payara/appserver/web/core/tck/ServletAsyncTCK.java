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

package fish.payara.appserver.web.core.tck;

import java.io.IOException;
import java.util.stream.Stream;

import com.sun.ts.tests.servlet.spec.async.*;
import com.sun.ts.tests.servlet.api.common.request.RequestClient;
import fish.payara.appserver.web.core.GrizzlyTestHarness;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.Test;

public class ServletAsyncTCK extends TCKBase {
    @Test
    public void testAsyncServletApi() throws IOException {
        context = harness.addContext("servlet_spec_async_web", b -> {
            // this definition is little less standard than usual
            var ctx = b.getContext();

            createServlet(ctx, Servlet1.class, true);
            createServlet(ctx, Servlet2.class, false);
            createServlet(ctx, Servlet3.class, false);
            createServlet(ctx, Servlet4.class, true);
            createServlet(ctx, Servlet5.class, true);
            createServlet(ctx, Servlet6.class, true);
            createServlet(ctx, Servlet7.class, false);
            createServlet(ctx, Servlet8.class, false);
            createServlet(ctx, Servlet9.class, false);
            createServlet(ctx, Servlet10.class, true);
            createServlet(ctx, TestServlet.class, true);

            createFilter(ctx, Filter4.class, true, "Servlet4");
            createFilter(ctx, Filter5.class, false, "Servlet5");
            createFilter(ctx, Filter6.class, false, "Servlet6");
            createFilter(ctx, Filter7.class, true, "Servlet7");
            createFilter(ctx, Filter8.class, false, "Servlet8");
            createFilter(ctx, Filter9.class, false, "Servlet9");
            createFilter(ctx, Filter10.class, true, "Servlet10");
            ctx.setSessionTimeout(54);
        });

        // exclude all inherited tests as there is no handler for those
        var exclusions = Stream.of(RequestClient.class.getDeclaredMethods())
                .filter(m -> m.getName().endsWith("Test") || m.getName().equals("setCharacterEncodingTest1") )
                .map(m -> m.getName())
                .toArray(String[]::new);

        harness.runTck(new URLClient(), exclusions);
    }

    static void createFilter(StandardContext context, Class<? extends Filter> filterClass, boolean asyncSupported, String mappedServlet) {
        var filterDef = new FilterDef();
        var filterName = filterClass.getSimpleName();
        filterDef.setFilterName(filterName);
        filterDef.setAsyncSupported(String.valueOf(asyncSupported));
        filterDef.setFilterClass(filterClass.getName());
        context.addFilterDef(filterDef);

        var map = new FilterMap();
        map.setFilterName(filterName);
        map.addServletName(mappedServlet);
        map.setDispatcher("INCLUDE");
        map.setDispatcher("FORWARD");
        map.setDispatcher("REQUEST");
        context.addFilterMap(map);


    }

    static void createServlet(StandardContext context, Class<? extends Servlet> servletClass, boolean asyncSupported) {
        var servletName = servletClass.getSimpleName();
        var wrapper = GrizzlyTestHarness.Catalina.addServlet(context, servletName, servletClass, "/"+ servletName);
        wrapper.setAsyncSupported(asyncSupported);
        // Potential bug or something in Tomcat... valve does not get its async support set and it defaults to true
        ((ValveBase)wrapper.getPipeline().getBasic()).setAsyncSupported(asyncSupported);
    }
}
