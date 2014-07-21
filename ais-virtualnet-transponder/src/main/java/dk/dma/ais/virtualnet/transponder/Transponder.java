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
package dk.dma.ais.virtualnet.transponder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

import net.jcip.annotations.ThreadSafe;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.binary.SixbitException;
import dk.dma.ais.message.AisMessage;
import dk.dma.ais.message.AisMessage6;
import dk.dma.ais.message.AisMessage7;
import dk.dma.ais.message.AisMessageException;
import dk.dma.ais.message.AisStaticCommon;
import dk.dma.ais.message.IVesselPositionMessage;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.sentence.Abk;
import dk.dma.ais.sentence.Abm;
import dk.dma.ais.sentence.Bbm;
import dk.dma.ais.sentence.Sentence;
import dk.dma.ais.sentence.SentenceException;
import dk.dma.ais.sentence.Vdm;
import dk.dma.ais.transform.CropVdmTransformer;
import dk.dma.ais.transform.VdmVdoTransformer;
import dk.dma.ais.virtualnet.common.message.TargetTableMessage;
import dk.dma.enav.model.geometry.Position;

/**
 * Virtual transponder
 */
@ThreadSafe
public class Transponder extends Thread {

    private static final Logger LOG = LoggerFactory.getLogger(Transponder.class);

    private final TransponderConfiguration conf;
    private final TransponderStatus status;
    private final ServerConnection serverConnection;
    private final ServerSocket serverSocket;
    private final TransponderOwnMessage ownMessage;
    private final StreamTime psttSender;
    private final VdmVdoTransformer vdoTransformer;
    private final CropVdmTransformer cropTransformer;

    private final ConcurrentHashMap<Integer, Position> positions = new ConcurrentHashMap<>();

    private volatile Socket socket;
    private volatile PrintWriter out;
    private Abm abm = new Abm();
    private Bbm bbm = new Bbm();
    private Abk abk = new Abk();
    private int sequence;

    public Transponder(TransponderConfiguration conf) throws IOException {
        this.conf = conf;
        status = new TransponderStatus();
        serverConnection = new ServerConnection(this, conf);
        serverSocket = new ServerSocket(conf.getPort());
        ownMessage = new TransponderOwnMessage(this, conf.getOwnPosInterval());
        vdoTransformer = new VdmVdoTransformer(conf.getOwnMmsi(), "AI");
        cropTransformer = new CropVdmTransformer();
        if (conf.isSendPsttSentence()) {
            psttSender = new StreamTime();
        } else {
            psttSender = null;
        }
    }

    /**
     * Data received from network
     * 
     * @param packet
     */
    public void receive(String strPacket) {
        // Make packet and get ais message
        AisPacket packet = AisPacket.from(strPacket);
        AisMessage message;
        try {
            message = packet.getAisMessage();
        } catch (AisMessageException | SixbitException e) {
            LOG.debug("Failed to parse message: " + e.getMessage());
            return;
        }

        // Try to get timestamp and maybe send PSTT time sentence
        Date timestamp = packet.getTimestamp();
        if (psttSender != null && timestamp != null) {
            psttSender.setStreamTime(timestamp.getTime());
            if (psttSender.isDue()) {
                send(psttSender.createPstt());
            }
        }

        // Determine own
        boolean own = message.getUserId() == conf.getOwnMmsi();

        // Convert to VDO or VDM
        packet = vdoTransformer.transform(packet);
        if (packet == null) {
            LOG.error("Failed to convert packet " + strPacket);
            return;
        }

        // Crop to VDM/VDO
        packet = cropTransformer.transform(packet);
        if (packet == null) {
            LOG.error("Failed to crop packet " + strPacket);
            return;
        }

        // Maybe the transponder needs to send a binary acknowledge back to the network
        if (message.getMsgId() == 6) {
            AisMessage6 msg6 = (AisMessage6) message;
            if (msg6.getDestination() == conf.getOwnMmsi()) {
                sendBinAck(msg6);
            }
        }

        // Get name from own static
        if (own && message instanceof AisStaticCommon) {
            String name = ((AisStaticCommon) message).getName();
            if (name != null) {
                status.setShipName(AisMessage.trimText(name));
            }
        }

        // Position of current target
        Position position = null;

        // Handle position
        if (message instanceof IVesselPositionMessage) {
            IVesselPositionMessage posMsg = (IVesselPositionMessage) message;
            if (own) {
                // Save own position message
                ownMessage.setOwnMessage(packet);
                // Save own position if valid
                if (posMsg.isPositionValid()) {
                    status.setOwnPos(posMsg.getPos().getGeoLocation());
                }
            } else {
                // Is this message valid and within radius
                if (!posMsg.isPositionValid()) {
                    return;
                }
                // Save position
                position = posMsg.getPos().getGeoLocation();
                positions.put(message.getUserId(), position);
            }
        }

        // Maybe filter away message
        if (!own && conf.getReceiveRadius() > 0) {
            if (status.getOwnPos() == null) {
                return;
            }
            // Try to get position
            if (position == null) {
                position = positions.get(message.getUserId());
            }
            if (position == null) {
                return;
            }
            if (position.rhumbLineDistanceTo(status.getOwnPos()) > conf.getReceiveRadius()) {
                return;
            }
        }

        if (status.isClientConnected()) {
            send(packet.getStringMessage());
        }

    }

    /**
     * Send message to client
     * 
     * @param str
     */
    public void send(String str) {
        if (status.isClientConnected()) {
            out.print(str + "\r\n");
            out.flush();
        }
    }

    @Override
    public void start() {
        serverConnection.start();
        ownMessage.start();
        super.start();
    }

    public void shutdown() {
        ownMessage.interrupt();
        try {
            ownMessage.join(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.interrupt();
        serverConnection.shutdown();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
        }
        try {
            this.join(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (true) {
            status.setClientConnected(false);

            // Wait for connections
            LOG.info("Waiting for connection on port " + conf.getPort());
            try {
                socket = serverSocket.accept();
                LOG.info("Client connected");
            } catch (IOException e) {
                if (!isInterrupted()) {
                    LOG.error("Failed to accept client connection", e);
                }
                break;
            }

            try {
                out = new PrintWriter(socket.getOutputStream());
                status.setClientConnected(true);
                readFromAI();
            } catch (IOException e) {
            }

            try {
                socket.close();
            } catch (IOException e1) {
            }

            LOG.info("Lost connection to client");
        }
        LOG.info("Transponder stopped");
    }

    private void readFromAI() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            LOG.info("Read from client: " + line);

            // Ignore everything else than sentences
            if (!Sentence.hasSentence(line)) {
                continue;
            }

            try {
                if (Abm.isAbm(line)) {
                    int result = abm.parse(line);
                    if (result == 0) {
                        handleAbm();
                    } else {
                        continue;
                    }
                }
                if (Bbm.isBbm(line)) {
                    int result = bbm.parse(line);
                    if (result == 0) {
                        handleBbm();
                    } else {
                        continue;
                    }
                }
                if (Vdm.isVdm(line)) {
                    // TODO handle multi line vdm and send unaltered to the network
                }
                abm = new Abm();
                bbm = new Bbm();

            } catch (SixbitException | SentenceException e) {
                LOG.info("ABM or BBM failed: " + e.getMessage() + " line: " + line);
            }

        }

    }

    private void sendBinAck(AisMessage6 msg6) {
        AisMessage7 msg7 = new AisMessage7();
        msg7.setUserId(conf.getOwnMmsi());
        msg7.setDest1(msg6.getUserId());
        msg7.setSeq1(msg6.getSeqNum());
        LOG.info("Sending binary acknowledge: " + msg7);
        sendMessage(msg7, msg6.getSeqNum());
    }

    private void sendMessage(AisMessage message, Integer seq) {
        if (seq == null) {
            seq = sequence;
            sequence = (sequence + 1) % 4;
        }
        String[] sentences;
        try {
            sentences = Vdm.createSentences(message, seq);
        } catch (SixbitException e) {
            LOG.error("Failed to encode message: " + message, e);
            return;
        }
        AisPacket packet = AisPacket.from(StringUtils.join(sentences, "\r\n"));
        LOG.info("Sending VDM to network: " + packet.getStringMessage());
        serverConnection.send(packet);
    }

    private void handleBbm() {
        LOG.info("Reveived complete BBM");
        abk = new Abk();
        abk.setChannel(bbm.getChannel());
        abk.setMsgId(bbm.getMsgId());
        abk.setSequence(bbm.getSequence());

        // Send AisMessage from Bbm
        try {
            sendMessage(bbm.getAisMessage(conf.getOwnMmsi(), 0), bbm.getSequence());
            abk.setResult(Abk.Result.BROADCAST_SENT);
        } catch (Exception e) {
            LOG.info("Error decoding BBM: " + e.getMessage());
            // Something must be wrong with Bbm
            abk.setResult(Abk.Result.COULD_NOT_BROADCAST);
        }

        sendAbk();
    }

    private void handleAbm() {
        LOG.info("Reveived complete ABM");
        abk = new Abk();
        abk.setChannel(abm.getChannel());
        abk.setMsgId(abm.getMsgId());
        abk.setSequence(abm.getSequence());
        abk.setDestination(abm.getDestination());

        // Get AisMessage from Abm
        try {
            sendMessage(abm.getAisMessage(conf.getOwnMmsi(), 0, 0), abm.getSequence());
            abk.setResult(Abk.Result.ADDRESSED_SUCCESS);
        } catch (Exception e) {
            LOG.info("Error decoding ABM: " + e.getMessage());
            // Something must be wrong with Abm
            abk.setResult(Abk.Result.COULD_NOT_BROADCAST);
        }

        sendAbk();
    }

    private void sendAbk() {
        String encoded = abk.getEncoded() + "\r\n";
        LOG.info("Sending ABK: " + encoded);
        send(encoded);
    }

    public static TargetTableMessage getTargets(String host, int port, String username, String password) throws RestException {
        RestClient restClient = new RestClient(host, port);
        return restClient.getTargetTable(username, password);
    }

    public TransponderStatus getStatus() {
        return status;
    }

}
