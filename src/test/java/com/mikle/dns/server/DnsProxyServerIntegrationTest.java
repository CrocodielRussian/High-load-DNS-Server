package com.mikle.dns.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mikle.dns.config.ServerConfig;
import com.mikle.dns.dns.DnsTestPackets;
import com.mikle.dns.dns.DnsWire;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DnsProxyServerIntegrationTest {
    @Test
    void proxiesAndCachesDnsResponse() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        AtomicInteger upstreamRequests = new AtomicInteger();

        try (DatagramSocket upstream = new DatagramSocket(0, loopback)) {
            Thread upstreamThread = Thread.ofVirtual().name("test-upstream").start(() -> serve(upstream, upstreamRequests));
            ServerConfig config = config(loopback, upstream.getLocalPort());

            try (DnsProxyServer server = new DnsProxyServer(config)) {
                server.start();
                try (DatagramSocket client = new DatagramSocket(0, loopback)) {
                    client.setSoTimeout(2000);
                    byte[] first = exchange(client, server.localAddress(), DnsTestPackets.query("example.com", 100));
                    byte[] second = exchange(client, server.localAddress(), DnsTestPackets.query("example.com", 200));

                    assertEquals(100, DnsWire.unsignedShort(first, 0));
                    assertEquals(200, DnsWire.unsignedShort(second, 0));
                    assertArrayEquals(new byte[] {1, 2, 3, 4}, java.util.Arrays.copyOfRange(first, first.length - 4, first.length));
                    assertEquals(1, upstreamRequests.get());
                }
            } finally {
                upstream.close();
                upstreamThread.join(Duration.ofSeconds(2));
            }
        }
    }

    private static void serve(DatagramSocket socket, AtomicInteger requests) {
        byte[] buffer = new byte[4096];
        while (!socket.isClosed()) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                requests.incrementAndGet();
                byte[] query = java.util.Arrays.copyOf(request.getData(), request.getLength());
                byte[] response = DnsTestPackets.aResponse(query, 60, new byte[] {1, 2, 3, 4});
                socket.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
            } catch (SocketException closed) {
                return;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static byte[] exchange(DatagramSocket socket, InetSocketAddress server, byte[] query) throws Exception {
        socket.send(new DatagramPacket(query, query.length, server));
        DatagramPacket response = new DatagramPacket(new byte[4096], 4096);
        socket.receive(response);
        return java.util.Arrays.copyOf(response.getData(), response.getLength());
    }

    private static ServerConfig config(InetAddress loopback, int upstreamPort) {
        return new ServerConfig(
            new InetSocketAddress(loopback, 0),
            1,
            false,
            false,
            1 << 20,
            1 << 20,
            4096,
            List.of(new InetSocketAddress(loopback, upstreamPort)),
            List.of(),
            Duration.ofSeconds(1),
            1,
            1024,
            "example.com",
            Duration.ofHours(1),
            2,
            true,
            false,
            1 << 20,
            300,
            Duration.ZERO
        );
    }
}
