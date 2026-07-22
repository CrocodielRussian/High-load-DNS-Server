package com.mikle.dns.dns;

public record QueryKey(
    String name,
    int type,
    int dnsClass,
    int opcode,
    boolean recursionDesired,
    boolean checkingDisabled,
    boolean dnssecOk,
    int udpPayloadSize,
    int optionsHash
) {
    public int estimatedWeight() {
        return 48 + name.length() * Character.BYTES;
    }
}
