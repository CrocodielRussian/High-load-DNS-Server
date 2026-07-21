package com.snail.dnslb4j.metrics;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.concurrent.atomic.LongAdder;

public final class ServerMetrics {
    private final LongAdder requests = new LongAdder();
    private final LongAdder invalidRequests = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder singleFlightJoins = new LongAdder();
    private final LongAdder upstreamRequests = new LongAdder();
    private final LongAdder upstreamResponses = new LongAdder();
    private final LongAdder upstreamTimeouts = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public void request() {
        requests.increment();
    }

    public void invalidRequest() {
        invalidRequests.increment();
    }

    public void cacheHit() {
        cacheHits.increment();
    }

    public void cacheMiss() {
        cacheMisses.increment();
    }

    public void singleFlightJoin() {
        singleFlightJoins.increment();
    }

    public void upstreamRequest() {
        upstreamRequests.increment();
    }

    public void upstreamResponse() {
        upstreamResponses.increment();
    }

    public void upstreamTimeout() {
        upstreamTimeouts.increment();
    }

    public void failure() {
        failures.increment();
    }

    public Snapshot snapshot(CacheStats cacheStats, long cacheSize, int inFlight) {
        return new Snapshot(
            requests.sum(),
            invalidRequests.sum(),
            cacheHits.sum(),
            cacheMisses.sum(),
            singleFlightJoins.sum(),
            upstreamRequests.sum(),
            upstreamResponses.sum(),
            upstreamTimeouts.sum(),
            failures.sum(),
            cacheStats.hitRate(),
            cacheStats.evictionCount(),
            cacheSize,
            inFlight
        );
    }

    public record Snapshot(
        long requests,
        long invalidRequests,
        long cacheHits,
        long cacheMisses,
        long singleFlightJoins,
        long upstreamRequests,
        long upstreamResponses,
        long upstreamTimeouts,
        long failures,
        double caffeineHitRate,
        long cacheEvictions,
        long cacheSize,
        int inFlight
    ) {
    }
}
