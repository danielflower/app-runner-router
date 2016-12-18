package com.danielflower.apprunner.router.monitoring;

import org.jmock.Expectations;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import javax.servlet.http.HttpServletRequest;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BlockingUdpSenderTest {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    @Rule public JUnitRuleMockery context = new JUnitRuleMockery();
    private final HttpServletRequest request = context.mock(HttpServletRequest.class);
    private final BlockingUdpSender sender = BlockingUdpSender.create("localhost", 14400);

    @Test
    public void sendsDataAboutTheRequestOverASocket() throws Exception {
        context.checking(new Expectations() {{
            allowing(request).getRemoteAddr(); will(returnValue("10.100.0.8"));
            allowing(request).getMethod(); will(returnValue("GET"));
        }});

        DatagramSocket serverSocket = new DatagramSocket(new InetSocketAddress("localhost", 14400));
        Future<String> result = executorService.submit(() -> {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            serverSocket.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), "UTF-8");
        });

        RequestInfo info = new RequestInfo();
        info.url = "http://localhost";
        info.method = "GET";
        sender.onRequestComplete(info);

        String actual = result.get(10, TimeUnit.SECONDS);
        JSONObject expected = new JSONObject()
            .put("url", "http://localhost")
            .put("method", "GET");

        JSONAssert.assertEquals(expected, new JSONObject(actual), JSONCompareMode.LENIENT);

    }
}
