package com.mikle.dns.dns;

public record ParsedQuery(int id, QueryKey key, byte[] wire, int questionEnd) {
}
