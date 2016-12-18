package com.danielflower.apprunner.router.monitoring;

import com.danielflower.apprunner.router.problems.AppRunnerException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;

public class BlockingUdpSender implements AppRequestListener {
    private static final Logger log = LoggerFactory.getLogger(BlockingUdpSender.class);

    private final Charset encoding = Charset.forName("UTF-8");
    private final DatagramChannel clientSocket;

    private BlockingUdpSender(DatagramChannel clientSocket) {
        this.clientSocket = clientSocket;
    }

    public static BlockingUdpSender create(String hostname, int port) {
        log.info("Creating UDP sender on " + hostname + ":" + port);
        DatagramChannel clientSocket;
        try {
            clientSocket = DatagramChannel.open();
            clientSocket.connect(new InetSocketAddress(hostname, port));
        } catch (IOException e) {
            throw new AppRunnerException("Error while starting udp sender on " + hostname + ":" + port, e);
        }
        return new BlockingUdpSender(clientSocket);
    }

    @Override
    public void onRequestComplete(RequestInfo info) {
        try {
            byte[] sendData = info.toJSON().getBytes(encoding);
            clientSocket.write(ByteBuffer.wrap(sendData));
        } catch (Exception e) {
            log.info("Error sending message: " + e.getMessage());
        }
    }


    @Override
    public void stop() {
        IOUtils.closeQuietly(clientSocket);
    }
}
