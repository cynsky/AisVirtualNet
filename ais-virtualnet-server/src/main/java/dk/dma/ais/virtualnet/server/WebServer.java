/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.dma.ais.virtualnet.server;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Builder;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.virtualnet.server.rest.RestService;


/**
 * 
 * @author Kasper Nielsen
 */
public class WebServer {

    /** The logger */
    static final Logger LOG = LoggerFactory.getLogger(WebServer.class);

    final Server server;

    final AisVirtualNetServer aserver;

    public WebServer(AisVirtualNetServer aserver, int port) {
        server = new Server(port);
        this.aserver = aserver;
    }

    public void stop() throws Exception {
        server.stop();
    }

    public void join() throws InterruptedException {
        server.join();
    }


    void start() throws Exception {
        ((ServerConnector) server.getConnectors()[0]).setReuseAddress(true);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        ResourceConfig config = new ResourceConfig();
        config.register(new RestService(aserver));
        ServletHolder sho = new ServletHolder(new ServletContainer(config));
        sho.setClassName("org.glassfish.jersey.servlet.ServletContainer");
        context.addServlet(sho, "/rest/*");

        // Enable javax.websocket configuration for the context
        // Jetty needs to have at least 1 servlet, so we add this dummy servlet

        ServerContainer wsContainer = WebSocketServerContainerInitializer.configureContext(context);
        // Add our default endpoint.

        Builder b = ServerEndpointConfig.Builder.create(WebSocketServerSession.class, "/ws/");
        b.configurator(new ServerEndpointConfig.Configurator() {
            @SuppressWarnings({ "unchecked" })
            public <S> S getEndpointInstance(Class<S> endpointClass) throws InstantiationException {
                return (S) new WebSocketServerSession(aserver);
            }
        });

        try {
            wsContainer.addEndpoint(b.build());
        } catch (DeploymentException e) {
            throw new RuntimeException("Could not start server", e);
        }

        //
        // HandlerWrapper hw = new HandlerWrapper() {
        //
        // /** {@inheritDoc} */
        // @Override
        // public void handle(String target, Request baseRequest, HttpServletRequest request,
        // HttpServletResponse response) throws IOException, ServletException {
        // long start = System.nanoTime();
        // String queryString = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        // LOG.info("Received connection from " + request.getRemoteHost() + " (" + request.getRemoteAddr() + ":"
        // + request.getRemotePort() + ") request = " + request.getRequestURI() + queryString);
        // super.handle(target, baseRequest, request, response);
        // LOG.info("Connection closed from " + request.getRemoteHost() + " (" + request.getRemoteAddr() + ":"
        // + request.getRemotePort() + ") request = " + request.getRequestURI() + queryString
        // + ", Duration = " + (System.nanoTime() - start) / 1000000 + " ms");
        // }
        // };
        // hw.setHandler(context);
        // server.setHandler(hw);
        server.start();
    }
}
