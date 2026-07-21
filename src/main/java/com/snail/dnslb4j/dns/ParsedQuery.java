package com.snail.dnslb4j.dns;

public record ParsedQuery(int id, QueryKey key, byte[] wire, int questionEnd) {
}
