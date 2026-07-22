package com.mikle.dns.dns;

import java.util.Arrays;

public final class DnsTestPackets {
    private DnsTestPackets() {
    }

    public static byte[] query(String domain, int id) {
        byte[] query = DnsWire.buildQuery(domain);
        DnsWire.writeUnsignedShort(query, 0, id);
        return query;
    }

    public static byte[] aResponse(byte[] query, int ttlSeconds, byte[] address) {
        byte[] response = Arrays.copyOf(query, query.length + 16);
        DnsWire.writeUnsignedShort(response, 2, 0x8180);
        DnsWire.writeUnsignedShort(response, 4, 1);
        DnsWire.writeUnsignedShort(response, 6, 1);
        DnsWire.writeUnsignedShort(response, 8, 0);
        DnsWire.writeUnsignedShort(response, 10, 0);

        int cursor = query.length;
        response[cursor++] = (byte) 0xc0;
        response[cursor++] = 0x0c;
        DnsWire.writeUnsignedShort(response, cursor, 1);
        DnsWire.writeUnsignedShort(response, cursor + 2, 1);
        DnsWire.writeUnsignedInt(response, cursor + 4, ttlSeconds);
        DnsWire.writeUnsignedShort(response, cursor + 8, 4);
        System.arraycopy(address, 0, response, cursor + 10, 4);
        return response;
    }
}
