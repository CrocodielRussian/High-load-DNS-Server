package com.mikle.dns.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DnsWireTest {
    @Test
    void parsesCanonicalQuestionAndClientId() throws Exception {
        byte[] wire = DnsTestPackets.query("WWW.Example.COM", 0xbeef);

        ParsedQuery query = DnsWire.parseQuery(wire);

        assertEquals(0xbeef, query.id());
        assertEquals("www.example.com", query.key().name());
        assertEquals(1, query.key().type());
        assertEquals(1, query.key().dnsClass());
        assertTrue(query.key().recursionDesired());
    }

    @Test
    void agesEveryCachedTtlWithoutExtendingUpstreamTtl() throws Exception {
        ParsedQuery query = DnsWire.parseQuery(DnsTestPackets.query("example.com", 7));
        byte[] response = DnsTestPackets.aResponse(query.wire(), 60, new byte[] {1, 2, 3, 4});

        CachedDnsResponse cached = DnsWire.cacheableResponse(response, query.key(), 30, 0).orElseThrow();
        byte[] materialized = cached.materialize(query, TimeUnit.SECONDS.toNanos(10));

        assertEquals(20, DnsWire.unsignedInt(materialized, query.questionEnd() + 6));
        assertEquals(7, DnsWire.unsignedShort(materialized, 0));
    }

    @Test
    void rejectsTruncatedQuestion() {
        byte[] invalid = new byte[12];
        DnsWire.writeUnsignedShort(invalid, 4, 1);

        assertThrows(DnsFormatException.class, () -> DnsWire.parseQuery(invalid));
    }

    @Test
    void buildsServfailWithOriginalQuestion() throws Exception {
        ParsedQuery query = DnsWire.parseQuery(DnsTestPackets.query("example.com", 42));

        byte[] response = DnsWire.errorResponse(query, DnsWire.RCODE_SERVER_FAILURE);

        assertEquals(42, DnsWire.unsignedShort(response, 0));
        assertEquals(2, DnsWire.unsignedShort(response, 2) & 0x0f);
        assertEquals(1, DnsWire.unsignedShort(response, 4));
    }
}
