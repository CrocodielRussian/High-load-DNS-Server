package com.mikle.dns.upstream;

public record UpstreamResponse(byte[] wire, UpstreamTier tier) {
}
