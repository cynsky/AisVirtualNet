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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.jcip.annotations.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.bus.AisBus;
import dk.dma.ais.bus.consumer.DistributerConsumer;
import dk.dma.ais.bus.provider.CollectorProvider;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.virtualnet.common.message.StatusMessage;
import dk.dma.ais.virtualnet.common.table.TargetTable;
import dk.dma.ais.virtualnet.server.rest.AisVirtualNetServerProvider;
import dk.dma.enav.util.function.Consumer;

/**
 * The virtual AIS network
 */
@ThreadSafe
public class AisVirtualNetServer extends Thread implements Consumer<AisPacket> {

    private static final Logger LOG = LoggerFactory.getLogger(AisVirtualNetServer.class);

    private final AisBus aisBus;

    private final WebServer server;

    private final CollectorProvider collector = new CollectorProvider();

    private final DistributerConsumer distributer = new DistributerConsumer();

    private final TargetTable targetTable = new TargetTable();

    private final Authenticator authenticator;

    private final MmsiBroker mmsiBroker;

    /**
     * Connected clients
     */
    private final Set<WebSocketServerSession> clients = Collections
            .newSetFromMap(new ConcurrentHashMap<WebSocketServerSession, Boolean>());

    public AisVirtualNetServer(ServerConfiguration conf, String usersFile) throws Exception {
        // Create web server
        server = new WebServer(this, conf.getPort());
        // Sets setReuseAddress
        // Create and register websocket handler


        // Create authenticator
        authenticator = new Authenticator(usersFile);

        // Create MMSI broker
        mmsiBroker = new MmsiBroker();

        // Create AisBus
        aisBus = conf.getAisbusConfiguration().getInstance();
        // Initialize distributer and register in aisbus
        distributer.getConsumers().add(this);
        distributer.init();
        aisBus.registerConsumer(distributer);
        // Initialize collector and register in aisbus
        collector.init();
        aisBus.registerProvider(collector);
    }

    public StatusMessage getStatus() {
        StatusMessage message = new StatusMessage();
        message.setMessageRate(distributer.getStatus().getInRate());
        message.setConnectedClients(clients.size());
        return message;
    }

    /**
     * Accept packet from AisBus
     */
    @Override
    public void accept(AisPacket packet) {
        LOG.debug("Accepted message from DistributerConsumer");
        // Maintain target table
        targetTable.update(packet);
        // Distribute packet to clients
        for (WebSocketServerSession client : clients) {
            LOG.debug("\tEnqueing at client");
            client.enqueuePacket(packet);
            LOG.debug("\t\tDone enqueing at client");
        }
    }

    /**
     * Distribute packet to AisBus
     * 
     * @param packet
     */
    public void distribute(AisPacket packet) {
        collector.accept(packet);
    }

    /**
     * Add a new client
     * 
     * @param session
     */
    public void addClient(WebSocketServerSession session) {
        LOG.info("Adding client");
        clients.add(session);
        LOG.info("Client count: " + clients.size());
    }

    /**
     * Remove client
     * 
     * @param session
     */
    public void removeClient(WebSocketServerSession session) {
        LOG.info("Removing client");
        clients.remove(session);
        LOG.info("Client count: " + clients.size());
    }


    @Override
    public void start() {
        // Register server in provider
        AisVirtualNetServerProvider.setServer(this);

        try {
            server.start();
            LOG.info("Ready to accept incoming sockets");
        } catch (Exception e) {
            LOG.error("Failed to start server", e);
            try {
                server.stop();
            } catch (Exception e1) {}
            return;
        }

        // Start aisbus
        aisBus.startConsumers();
        aisBus.startProviders();
        aisBus.start();

        super.start();
    }

    public void shutdown() {
        LOG.info("Stopping web server");
        try {
            server.stop();
        } catch (Exception e) {
            LOG.error("Failed to stop web server", e);
        }

        if (aisBus != null) {
            LOG.info("Cancelling AisBus");
            aisBus.cancel();
        }

        LOG.info("Closing open web sockets");
        for (WebSocketServerSession client : clients) {
            client.close();
        }

        LOG.info("Waiting for server to stop");
        this.interrupt();
        try {
            this.join(10000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
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
            targetTable.cleanup();
        }
    }

    /**
     * Get current target table
     * 
     * @return
     */
    public TargetTable getTargetTable() {
        return targetTable;
    }

    /**
     * Get authenticator
     * 
     * @return
     */
    public Authenticator getAuthenticator() {
        return authenticator;
    }

    /**
     * Get MMSI broker
     * 
     * @return
     */
    public MmsiBroker getMmsiBroker() {
        return mmsiBroker;
    }

    /**
     * Check token
     * 
     * @param authToken
     * @return
     */
    public boolean checkToken(String authToken) {
        return authenticator.validate(authToken);
    }


}
