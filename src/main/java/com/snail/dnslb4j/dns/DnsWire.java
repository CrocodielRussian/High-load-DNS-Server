package com.snail.dnslb4j.dns;

import java.nio.charset.StandardCharsets;
import java.net.IDN;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public final class DnsWire {
    public static final int HEADER_BYTES = 12;
    public static final int RCODE_FORMAT_ERROR = 1;
    public static final int RCODE_SERVER_FAILURE = 2;

    private static final int FLAG_RESPONSE = 0x8000;
    private static final int FLAG_TRUNCATED = 0x0200;
    private static final int FLAG_RECURSION_DESIRED = 0x0100;
    private static final int FLAG_RECURSION_AVAILABLE = 0x0080;
    private static final int FLAG_CHECKING_DISABLED = 0x0010;
    private static final int TYPE_OPT = 41;

    private DnsWire() {
    }

    public static ParsedQuery parseQuery(byte[] wire) throws DnsFormatException {
        require(wire.length >= HEADER_BYTES, "DNS message is shorter than the header");
        int flags = unsignedShort(wire, 2);
        require((flags & FLAG_RESPONSE) == 0, "Expected a DNS query");
        require(unsignedShort(wire, 4) == 1, "Exactly one DNS question is required");
        require(unsignedShort(wire, 6) == 0 && unsignedShort(wire, 8) == 0,
            "Queries with answer or authority records are not supported");

        NameResult questionName = readName(wire, HEADER_BYTES);
        int cursor = questionName.nextOffset();
        require(cursor + 4 <= wire.length, "Truncated DNS question");
        int type = unsignedShort(wire, cursor);
        int dnsClass = unsignedShort(wire, cursor + 2);
        int questionEnd = cursor + 4;

        int udpPayloadSize = 512;
        boolean dnssecOk = false;
        int additionalCount = unsignedShort(wire, 10);
        cursor = questionEnd;
        for (int index = 0; index < additionalCount; index++) {
            cursor = skipName(wire, cursor);
            require(cursor + 10 <= wire.length, "Truncated additional record");
            int recordType = unsignedShort(wire, cursor);
            int recordClass = unsignedShort(wire, cursor + 2);
            long recordTtl = unsignedInt(wire, cursor + 4);
            int dataLength = unsignedShort(wire, cursor + 8);
            cursor += 10;
            require(cursor + dataLength <= wire.length, "Truncated additional record payload");
            if (recordType == TYPE_OPT) {
                udpPayloadSize = Math.max(512, recordClass);
                dnssecOk = (recordTtl & 0x8000) != 0;
            }
            cursor += dataLength;
        }
        require(cursor == wire.length, "Trailing bytes after DNS query");

        QueryKey key = new QueryKey(
            questionName.name().toLowerCase(Locale.ROOT),
            type,
            dnsClass,
            (flags >>> 11) & 0x0f,
            (flags & FLAG_RECURSION_DESIRED) != 0,
            (flags & FLAG_CHECKING_DISABLED) != 0,
            dnssecOk,
            udpPayloadSize,
            hashRange(wire, questionEnd, wire.length)
        );
        return new ParsedQuery(unsignedShort(wire, 0), key, wire, questionEnd);
    }

    public static boolean responseMatches(byte[] wire, QueryKey expected) {
        try {
            if (wire.length < HEADER_BYTES || (unsignedShort(wire, 2) & FLAG_RESPONSE) == 0
                || unsignedShort(wire, 4) != 1) {
                return false;
            }
            NameResult name = readName(wire, HEADER_BYTES);
            int cursor = name.nextOffset();
            return cursor + 4 <= wire.length
                && name.name().equalsIgnoreCase(expected.name())
                && unsignedShort(wire, cursor) == expected.type()
                && unsignedShort(wire, cursor + 2) == expected.dnsClass();
        } catch (DnsFormatException ignored) {
            return false;
        }
    }

    public static Optional<CachedDnsResponse> cacheableResponse(
        byte[] wire,
        QueryKey expected,
        int maximumTtlSeconds,
        long nowNanos
    ) {
        try {
            if (wire.length < HEADER_BYTES) {
                return Optional.empty();
            }
            int flags = unsignedShort(wire, 2);
            int responseCode = flags & 0x0f;
            int answerCount = unsignedShort(wire, 6);
            if ((flags & FLAG_RESPONSE) == 0 || (flags & FLAG_TRUNCATED) != 0
                || responseCode != 0 || answerCount == 0 || unsignedShort(wire, 4) != 1) {
                return Optional.empty();
            }

            NameResult name = readName(wire, HEADER_BYTES);
            int cursor = name.nextOffset();
            require(cursor + 4 <= wire.length, "Truncated response question");
            if (!name.name().equalsIgnoreCase(expected.name())
                || unsignedShort(wire, cursor) != expected.type()
                || unsignedShort(wire, cursor + 2) != expected.dnsClass()) {
                return Optional.empty();
            }
            int questionEnd = cursor + 4;
            cursor = questionEnd;

            int recordCount = answerCount + unsignedShort(wire, 8) + unsignedShort(wire, 10);
            if (recordCount > Math.max(0, (wire.length - questionEnd) / 11)) {
                return Optional.empty();
            }
            int[] ttlOffsets = new int[recordCount];
            int[] ttlValues = new int[recordCount];
            int ttlCount = 0;
            int minimumTtl = Integer.MAX_VALUE;

            for (int index = 0; index < recordCount; index++) {
                cursor = skipName(wire, cursor);
                require(cursor + 10 <= wire.length, "Truncated response record");
                int recordType = unsignedShort(wire, cursor);
                int ttlOffset = cursor + 4;
                long upstreamTtl = unsignedInt(wire, ttlOffset);
                int dataLength = unsignedShort(wire, cursor + 8);
                cursor += 10;
                require(cursor + dataLength <= wire.length, "Truncated response payload");

                if (recordType != TYPE_OPT && upstreamTtl > 0) {
                    int boundedTtl = (int) Math.min(upstreamTtl, maximumTtlSeconds);
                    ttlOffsets[ttlCount] = ttlOffset;
                    ttlValues[ttlCount] = boundedTtl;
                    ttlCount++;
                    minimumTtl = Math.min(minimumTtl, boundedTtl);
                }
                cursor += dataLength;
            }
            require(cursor == wire.length, "Trailing bytes after DNS response");
            if (ttlCount == 0 || minimumTtl <= 0) {
                return Optional.empty();
            }

            byte[] template = wire.clone();
            writeUnsignedShort(template, 0, 0);
            int[] offsets = Arrays.copyOf(ttlOffsets, ttlCount);
            int[] values = Arrays.copyOf(ttlValues, ttlCount);
            for (int index = 0; index < ttlCount; index++) {
                writeUnsignedInt(template, offsets[index], values[index]);
            }
            return Optional.of(new CachedDnsResponse(
                template, questionEnd, offsets, values, nowNanos, minimumTtl));
        } catch (DnsFormatException ignored) {
            return Optional.empty();
        }
    }

    public static byte[] errorResponse(ParsedQuery query, int responseCode) {
        byte[] response = Arrays.copyOf(query.wire(), query.questionEnd());
        int queryFlags = unsignedShort(response, 2);
        int responseFlags = FLAG_RESPONSE | FLAG_RECURSION_AVAILABLE | responseCode
            | (queryFlags & (0x7800 | FLAG_RECURSION_DESIRED | FLAG_CHECKING_DISABLED));
        writeUnsignedShort(response, 2, responseFlags);
        writeUnsignedShort(response, 4, 1);
        writeUnsignedShort(response, 6, 0);
        writeUnsignedShort(response, 8, 0);
        writeUnsignedShort(response, 10, 0);
        return response;
    }

    public static byte[] formatError(byte[] wire) {
        byte[] response = new byte[HEADER_BYTES];
        if (wire.length >= 2) {
            response[0] = wire[0];
            response[1] = wire[1];
        }
        writeUnsignedShort(response, 2, FLAG_RESPONSE | RCODE_FORMAT_ERROR);
        return response;
    }

    public static byte[] withId(byte[] wire, int id) {
        byte[] response = wire.clone();
        writeUnsignedShort(response, 0, id);
        return response;
    }

    public static byte[] buildQuery(String domain) {
        String withoutRoot = domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain;
        String normalized = IDN.toASCII(withoutRoot);
        byte[] name = encodeName(normalized);
        byte[] query = new byte[HEADER_BYTES + name.length + 4];
        writeUnsignedShort(query, 2, FLAG_RECURSION_DESIRED);
        writeUnsignedShort(query, 4, 1);
        System.arraycopy(name, 0, query, HEADER_BYTES, name.length);
        int cursor = HEADER_BYTES + name.length;
        writeUnsignedShort(query, cursor, 1);
        writeUnsignedShort(query, cursor + 2, 1);
        return query;
    }

    public static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    public static long unsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
            | ((long) (data[offset + 1] & 0xff) << 16)
            | ((long) (data[offset + 2] & 0xff) << 8)
            | (data[offset + 3] & 0xffL);
    }

    public static void writeUnsignedShort(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    public static void writeUnsignedInt(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static NameResult readName(byte[] data, int offset) throws DnsFormatException {
        StringBuilder name = new StringBuilder();
        int cursor = offset;
        int nextOffset = -1;
        int jumps = 0;

        while (true) {
            require(cursor < data.length, "Truncated DNS name");
            int length = data[cursor] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                require(cursor + 1 < data.length, "Truncated DNS compression pointer");
                int pointer = ((length & 0x3f) << 8) | (data[cursor + 1] & 0xff);
                require(pointer < data.length, "DNS compression pointer is out of bounds");
                if (nextOffset < 0) {
                    nextOffset = cursor + 2;
                }
                require(++jumps <= 128, "DNS compression pointer cycle");
                cursor = pointer;
                continue;
            }
            require((length & 0xc0) == 0, "Invalid DNS label type");
            cursor++;
            if (length == 0) {
                if (nextOffset < 0) {
                    nextOffset = cursor;
                }
                break;
            }
            require(length <= 63 && cursor + length <= data.length, "Invalid DNS label length");
            if (!name.isEmpty()) {
                name.append('.');
            }
            for (int index = 0; index < length; index++) {
                name.append((char) (data[cursor + index] & 0xff));
            }
            cursor += length;
            require(name.length() <= 253, "DNS name is too long");
        }
        return new NameResult(name.toString(), nextOffset);
    }

    private static int skipName(byte[] data, int offset) throws DnsFormatException {
        int cursor = offset;
        while (true) {
            require(cursor < data.length, "Truncated DNS name");
            int length = data[cursor] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                require(cursor + 1 < data.length, "Truncated DNS compression pointer");
                int pointer = ((length & 0x3f) << 8) | (data[cursor + 1] & 0xff);
                require(pointer < data.length, "DNS compression pointer is out of bounds");
                return cursor + 2;
            }
            require((length & 0xc0) == 0 && length <= 63, "Invalid DNS label");
            cursor++;
            if (length == 0) {
                return cursor;
            }
            require(cursor + length <= data.length, "Truncated DNS label");
            cursor += length;
        }
    }

    private static byte[] encodeName(String domain) {
        if (domain.isBlank()) {
            return new byte[] {0};
        }
        byte[][] labels = Arrays.stream(domain.split("\\."))
            .map(label -> label.getBytes(StandardCharsets.US_ASCII))
            .toArray(byte[][]::new);
        int length = 1;
        for (byte[] label : labels) {
            if (label.length == 0 || label.length > 63) {
                throw new IllegalArgumentException("Invalid DNS label in " + domain);
            }
            length += 1 + label.length;
        }
        if (length > 255) {
            throw new IllegalArgumentException("DNS name is too long: " + domain);
        }
        byte[] encoded = new byte[length];
        int cursor = 0;
        for (byte[] label : labels) {
            encoded[cursor++] = (byte) label.length;
            System.arraycopy(label, 0, encoded, cursor, label.length);
            cursor += label.length;
        }
        return encoded;
    }

    private static int hashRange(byte[] data, int start, int end) {
        int hash = 1;
        for (int index = start; index < end; index++) {
            hash = 31 * hash + data[index];
        }
        return hash;
    }

    private static void require(boolean condition, String message) throws DnsFormatException {
        if (!condition) {
            throw new DnsFormatException(message);
        }
    }

    private record NameResult(String name, int nextOffset) {
    }
}
