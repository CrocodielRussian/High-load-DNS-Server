package com.mikle.dns.dns;

import java.util.concurrent.TimeUnit;

public final class CachedDnsResponse {
    private final byte[] template;
    private final int questionEnd;
    private final int[] ttlOffsets;
    private final int[] originalTtls;
    private final long storedAtNanos;
    private final long lifetimeNanos;

    CachedDnsResponse(
        byte[] template,
        int questionEnd,
        int[] ttlOffsets,
        int[] originalTtls,
        long storedAtNanos,
        int minimumTtlSeconds
    ) {
        this.template = template;
        this.questionEnd = questionEnd;
        this.ttlOffsets = ttlOffsets;
        this.originalTtls = originalTtls;
        this.storedAtNanos = storedAtNanos;
        this.lifetimeNanos = TimeUnit.SECONDS.toNanos(minimumTtlSeconds);
    }

    public long lifetimeNanos() {
        return lifetimeNanos;
    }

    public int estimatedWeight() {
        return template.length + ttlOffsets.length * Integer.BYTES * 2 + 64;
    }

    public byte[] materialize(ParsedQuery query, long nowNanos) {
        byte[] response = template.clone();
        DnsWire.writeUnsignedShort(response, 0, query.id());

        long elapsedSeconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(nowNanos - storedAtNanos));
        for (int index = 0; index < ttlOffsets.length; index++) {
            long remaining = Math.max(0, originalTtls[index] - elapsedSeconds);
            DnsWire.writeUnsignedInt(response, ttlOffsets[index], remaining);
        }

        if (questionEnd == query.questionEnd() && questionEnd <= response.length && questionEnd <= query.wire().length) {
            System.arraycopy(query.wire(), DnsWire.HEADER_BYTES, response, DnsWire.HEADER_BYTES,
                questionEnd - DnsWire.HEADER_BYTES);
        }
        return response;
    }
}
