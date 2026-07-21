package com.snail.dnslb4j.upstream;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpstreamEndpoint {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamEndpoint.class);

    private final InetSocketAddress address;
    private final UpstreamTier tier;
    private final int failureThreshold;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    public UpstreamEndpoint(InetSocketAddress address, UpstreamTier tier, int failureThreshold) {
        this.address = address;
        this.tier = tier;
        this.failureThreshold = failureThreshold;
    }

    public InetSocketAddress address() {
        return address;
    }

    public UpstreamTier tier() {
        return tier;
    }

    public boolean healthy() {
        return healthy.get();
    }

    public int outstanding() {
        return outstanding.get();
    }

    public void requestStarted() {
        outstanding.incrementAndGet();
    }

    public void requestFinished() {
        outstanding.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void markSuccess() {
        consecutiveFailures.set(0);
        if (healthy.compareAndSet(false, true)) {
            LOGGER.info("Upstream recovered: {} ({})", address, tier);
        }
    }

    public void markFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && healthy.compareAndSet(true, false)) {
            LOGGER.warn("Upstream marked unhealthy after {} failures: {} ({})", failures, address, tier);
        }
    }

    @Override
    public String toString() {
        return address + " (" + tier + ", healthy=" + healthy.get() + ", outstanding=" + outstanding + ')';
    }
}
