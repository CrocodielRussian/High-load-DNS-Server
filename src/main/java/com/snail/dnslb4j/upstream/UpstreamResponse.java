package com.snail.dnslb4j.upstream;

public record UpstreamResponse(byte[] wire, UpstreamTier tier) {
}
