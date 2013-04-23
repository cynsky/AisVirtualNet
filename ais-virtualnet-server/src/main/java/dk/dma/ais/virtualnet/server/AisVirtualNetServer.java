/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.virtualnet.server;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.jcip.annotations.ThreadSafe;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.bus.AisBus;
import dk.dma.ais.bus.consumer.DistributerConsumer;
import dk.dma.ais.packet.AisPacket;
import dk.dma.enav.util.function.Consumer;

/**
 * The virtual AIS network
 */
@ThreadSafe
public class AisVirtualNetServer extends Thread implements Consumer<AisPacket> {

    private static final Logger LOG = LoggerFactory.getLogger(AisVirtualNetServer.class);

    private final ServerConfiguration conf;
    private final AisBus aisBus;
    private final Server server;
    
    /**
     * Connected clients
     */
    private final Set<WebSocketServerSession> clients = Collections.newSetFromMap(new ConcurrentHashMap<WebSocketServerSession, Boolean>());

    public AisVirtualNetServer(ServerConfiguration conf) {
        this.conf = conf;
        // Create web server
        server = new Server(conf.getPort());
        // Sets setReuseAddress
        ((ServerConnector) server.getConnectors()[0]).setReuseAddress(true);
        // Create and register websocket handler
        final AisVirtualNetServer virtualNetServer = this;
        WebSocketHandler wsHandler = new WebSocketHandler() {            
            @Override
            public void configure(WebSocketServletFactory factory) {
                factory.setCreator(new WebSocketCreator() {
                    public Object createWebSocket(UpgradeRequest req, UpgradeResponse resp) {
                        return new WebSocketServerSession(virtualNetServer);
                    }
                });
            }
        };
        server.setHandler(wsHandler);
        // Create AisBus
        aisBus = conf.getAisbusConfiguration().getInstance();
        // Create distributor consumer and add to aisBus
        DistributerConsumer distributer = new DistributerConsumer();
        distributer.getConsumers().add(this);
        distributer.init();
        aisBus.registerConsumer(distributer);
    }
    
    /**
     * Accept packet from AisBus
     */
    @Override
    public void accept(AisPacket packet) {
        // Distribute packet to clients
        for (WebSocketServerSession client : clients) {
            client.sendPacket(packet);
        }
    }
    
    /**
     * Distribute packet to AisBus
     * @param packet
     */
    public void distribute(AisPacket packet) {
        // TODO
    }
    
    /**
     * Method to authenticate a user
     * @param username
     * @param password
     * @return 
     */
    public boolean authenticate(String username, String password) {
        LOG.info("Authenticating username: " + username + " password: " + password);
        // TODO implement
        return (username.equals("ole"));
    }

    /**
     * Add a new client
     * @param session
     */
    public void addClient(WebSocketServerSession session) {
        clients.add(session);
    }
    
    /**
     * Remove client
     * @param session
     */
    public void removeClient(WebSocketServerSession session) {
        clients.remove(session);
    }


    @Override
    public void start() {
        try {
            server.start();
            LOG.info("Ready to accept incoming sockets");
        } catch (Exception e) {
            LOG.error("Failed to start server", e);
            try {
                server.stop();
            } catch (Exception e1) {
            }
            return;
        }

        // Start aisbus
        aisBus.startConsumers();
        aisBus.startProviders();
        aisBus.start();

        super.start();
    }

    public void shutdown() {
        try {
            server.stop();
        } catch (Exception e) {
            LOG.error("Failed to stop web server", e);
        }
        aisBus.cancel();
        
        for (WebSocketServerSession client : clients) {
            client.close();
        }
        
        this.interrupt();
        try {
            this.join(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }

        }

    }

}
