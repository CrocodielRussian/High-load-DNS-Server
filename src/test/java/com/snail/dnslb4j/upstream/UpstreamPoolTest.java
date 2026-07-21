package com.snail.dnslb4j.upstream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpstreamPoolTest {
    @Test
    void failsOverToHealthyBackup() {
        UpstreamPool pool = new UpstreamPool(
            java.util.List.of(new InetSocketAddress("127.0.0.1", 1053)),
            java.util.List.of(new InetSocketAddress("127.0.0.1", 2053)),
            1
        );
        pool.all().getFirst().markFailure();

        UpstreamEndpoint selected = pool.select(Set.of());

        assertEquals(UpstreamTier.BACKUP, selected.tier());
    }
}
