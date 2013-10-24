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
package dk.dma.ais.virtualnet.transponder;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.virtualnet.common.message.AuthenticationReplyMessage;
import dk.dma.ais.virtualnet.common.message.ReserveMmsiReplyMessage;
import dk.dma.ais.virtualnet.common.message.ReserveMmsiReplyMessage.ReserveResult;

/**
 * Class that maintains the connection to the server
 */
public class ServerConnection extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(ServerConnection.class);

    private final Transponder transponder;
    private final TransponderConfiguration conf;

    private volatile WebSocketClientSession session;

    public ServerConnection(Transponder transponder, TransponderConfiguration conf) {
        this.transponder = transponder;
        this.conf = conf;
    }

    /**
     * Send packet to server
     */
    public void send(AisPacket packet) {
        if (transponder.getStatus().isServerConnected()) {
            session.sendPacket(packet);
        }
    }

    /**
     * Receive message from the server
     * 
     * @param packet
     */
    public void receive(String packet) {
        transponder.receive(packet);

    }

    public void shutdown() {
        this.interrupt();
        if (session != null) {
            session.close();
        }
        try {
            this.join(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public TransponderConfiguration getConf() {
        return conf;
    }

    private String authenticate() {
        // Make rest client
        RestClient restClient = new RestClient(conf.getServerHost(), conf.getServerPort());
        // Try to authenticate
        AuthenticationReplyMessage authReply;
        try {
            authReply = restClient.authenticate(conf.getUsername(), conf.getPassword());
        } catch (RestException e) {
            LOG.error("Authentication failed: " + e.getMessage());
            transponder.getStatus().setServerError("No authentication response from server");
            return null;
        }
        if (authReply.getAuthToken() == null) {
            LOG.info("Authentication failed: " + authReply.getErrorMessage());
            transponder.getStatus().setServerError(authReply.getErrorMessage());
        }
        return authReply.getAuthToken();
    }

    public boolean reserveMmsi(int mmsi, String authToken) {
        // Make rest client
        RestClient restClient = new RestClient(conf.getServerHost(), conf.getServerPort());
        ReserveMmsiReplyMessage reply;
        try {
            reply = restClient.reserveMmsi(mmsi, authToken);
        } catch (RestException e) {
            LOG.error("Failed to reserver MMSI: " + e.getMessage());
            transponder.getStatus().setServerError("Failed to reserve mmsi: no response");
            return false;
        }
        if (reply.getResult() != ReserveResult.MMSI_RESERVED) {
            transponder.getStatus().setServerError(reply.getResult().name());
            LOG.info("Failed to reserver mmsi: " + transponder.getStatus().getServerError());
            return false;
        }
        transponder.getStatus().setServerError(null);
        return true;
    }

    private void makeSession(String authToken) {
        // Make session
        session = new WebSocketClientSession(this, authToken);
        // Make client and connect
        WebSocketClient client = new WebSocketClient();
        String serverUrl = conf.createServerUrl();
        try {
            client.start();
            client.connect(session, new URI(serverUrl)).get();
            if (!session.getConnected().await(10, TimeUnit.SECONDS)) {
                LOG.error("Connection timeout");
                transponder.getStatus().setServerError("Connection timeout");
                session.close();
            } else {
                transponder.getStatus().setServerConnected(true);
                transponder.getStatus().setServerError(null);
            }
        } catch (Exception e) {
            transponder.getStatus().setServerError("Failed to connect web socket: " + e.getMessage() + " url: " + serverUrl);
            LOG.error(transponder.getStatus().getServerError());
        }
    }

    @Override
    public void run() {
        while (true) {
            if (isInterrupted()) {
                return;
            }
            transponder.getStatus().setServerConnected(false);

            String authToken = authenticate();

            if (authToken != null) {
                // Try to reserver MMSI and make session
                if (reserveMmsi(conf.getOwnMmsi(), authToken)) {
                    // Make session
                    makeSession(authToken);
                }
            }

            if (!transponder.getStatus().isServerConnected()) {
                // Something went wrong, wait a while
                try {
                    LOG.info("Waiting to reconnect");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            // Wait for disconnect
            try {
                session.getClosed().await();
            } catch (InterruptedException e) {
                session.close();
                return;
            }

            session.close();

        }

    }

}
