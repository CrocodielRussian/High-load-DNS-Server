package com.mikle.dns.server;

import com.mikle.dns.cache.DnsResponseCache;
import com.mikle.dns.dns.CachedDnsResponse;
import com.mikle.dns.dns.DnsWire;
import com.mikle.dns.dns.ParsedQuery;
import com.mikle.dns.dns.QueryKey;
import com.mikle.dns.metrics.ServerMetrics;
import com.mikle.dns.upstream.UpstreamResolver;
import com.mikle.dns.upstream.UpstreamResponse;
import com.mikle.dns.upstream.UpstreamTier;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class DnsProxyService {
    private final DnsResponseCache cache;
    private final ConcurrentHashMap<QueryKey, CompletableFuture<SharedResponse>> singleFlight =
        new ConcurrentHashMap<>();
    private final int maximumTtlSeconds;
    private final int maxAttempts;
    private final int maxSingleFlight;
    private final boolean cachePrimary;
    private final boolean cacheBackup;
    private final ServerMetrics metrics;
    private final AtomicInteger singleFlightCount = new AtomicInteger();

    public DnsProxyService(
        DnsResponseCache cache,
        int maximumTtlSeconds,
        int maxAttempts,
        int maxSingleFlight,
        boolean cachePrimary,
        boolean cacheBackup,
        ServerMetrics metrics
    ) {
        this.cache = cache;
        this.maximumTtlSeconds = maximumTtlSeconds;
        this.maxAttempts = maxAttempts;
        this.maxSingleFlight = maxSingleFlight;
        this.cachePrimary = cachePrimary;
        this.cacheBackup = cacheBackup;
        this.metrics = metrics;
    }

    public CompletableFuture<byte[]> resolve(
        ParsedQuery query,
        UpstreamResolver resolver,
        Executor completionExecutor
    ) {
        long now = System.nanoTime();
        Optional<byte[]> cached = cache.get(query, now);
        if (cached.isPresent()) {
            metrics.cacheHit();
            return CompletableFuture.completedFuture(cached.orElseThrow());
        }
        metrics.cacheMiss();

        CompletableFuture<SharedResponse> existing = singleFlight.get(query.key());
        if (existing != null) {
            metrics.singleFlightJoin();
            return existing.thenApplyAsync(response -> response.materialize(query), completionExecutor);
        }
        if (!reserveSingleFlightSlot()) {
            return CompletableFuture.failedFuture(
                new RejectedExecutionException("Single-flight in-flight limit reached"));
        }

        CompletableFuture<SharedResponse> created = new CompletableFuture<>();
        CompletableFuture<SharedResponse> shared = singleFlight.putIfAbsent(query.key(), created);
        if (shared != null) {
            singleFlightCount.decrementAndGet();
            metrics.singleFlightJoin();
            return shared.thenApplyAsync(response -> response.materialize(query), completionExecutor);
        }

        resolver.resolve(query, maxAttempts).whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    created.completeExceptionally(failure);
                    return;
                }
                created.complete(toSharedResponse(query, response));
            } finally {
                singleFlight.remove(query.key(), created);
                singleFlightCount.decrementAndGet();
            }
        });
        return created.thenApplyAsync(response -> response.materialize(query), completionExecutor);
    }

    public int inFlightQueries() {
        return singleFlightCount.get();
    }

    private boolean reserveSingleFlightSlot() {
        int current;
        do {
            current = singleFlightCount.get();
            if (current >= maxSingleFlight) {
                return false;
            }
        } while (!singleFlightCount.compareAndSet(current, current + 1));
        return true;
    }

    private SharedResponse toSharedResponse(ParsedQuery query, UpstreamResponse response) {
        boolean cacheAllowed = response.tier() == UpstreamTier.PRIMARY ? cachePrimary : cacheBackup;
        if (cacheAllowed) {
            Optional<CachedDnsResponse> cacheable = DnsWire.cacheableResponse(
                response.wire(), query.key(), maximumTtlSeconds, System.nanoTime());
            if (cacheable.isPresent()) {
                CachedDnsResponse cached = cacheable.orElseThrow();
                cache.put(query.key(), cached);
                return new SharedResponse(cached, null);
            }
        }
        return new SharedResponse(null, response.wire());
    }

    private record SharedResponse(CachedDnsResponse cached, byte[] wire) {
        private byte[] materialize(ParsedQuery query) {
            return cached != null
                ? cached.materialize(query, System.nanoTime())
                : DnsWire.withId(wire, query.id());
        }
    }
}
