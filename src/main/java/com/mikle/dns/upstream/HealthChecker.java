package com.mikle.dns.upstream;

import com.mikle.dns.dns.DnsFormatException;
import com.mikle.dns.dns.DnsWire;
import com.mikle.dns.dns.ParsedQuery;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class HealthChecker implements AutoCloseable {
    private final UpstreamPool pool;
    private final UpstreamResolver resolver;
    private final ParsedQuery probe;
    private final Duration interval;
    private final Map<UpstreamEndpoint, CompletableFuture<UpstreamResponse>> active = new ConcurrentHashMap<>();
    private ScheduledFuture<?> task;

    public HealthChecker(UpstreamPool pool, UpstreamResolver resolver, String domain, Duration interval) {
        this.pool = pool;
        this.resolver = resolver;
        this.interval = interval;
        try {
            probe = DnsWire.parseQuery(DnsWire.buildQuery(domain));
        } catch (DnsFormatException impossible) {
            throw new IllegalArgumentException("Invalid health-check domain: " + domain, impossible);
        }
    }

    public void start() {
        task = resolver.eventLoop().scheduleWithFixedDelay(
            this::probeAll,
            interval.toNanos(),
            interval.toNanos(),
            TimeUnit.NANOSECONDS
        );
    }

    private void probeAll() {
        for (UpstreamEndpoint endpoint : pool.all()) {
            active.computeIfAbsent(endpoint, key -> {
                CompletableFuture<UpstreamResponse> future = resolver.probe(endpoint, probe);
                future.whenComplete((response, failure) -> active.remove(endpoint, future));
                return future;
            });
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false);
        }
        active.clear();
    }
}
