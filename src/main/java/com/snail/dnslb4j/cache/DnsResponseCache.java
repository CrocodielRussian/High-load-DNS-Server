package com.snail.dnslb4j.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.snail.dnslb4j.dns.CachedDnsResponse;
import com.snail.dnslb4j.dns.ParsedQuery;
import com.snail.dnslb4j.dns.QueryKey;
import java.util.Optional;

public final class DnsResponseCache {
    private final Cache<QueryKey, CachedDnsResponse> cache;

    public DnsResponseCache(long maximumWeightBytes) {
        cache = Caffeine.newBuilder()
            .maximumWeight(maximumWeightBytes)
            .weigher((QueryKey key, CachedDnsResponse value) ->
                Math.max(1, Math.min(Integer.MAX_VALUE, key.estimatedWeight() + value.estimatedWeight())))
            .expireAfter(new Expiry<QueryKey, CachedDnsResponse>() {
                @Override
                public long expireAfterCreate(QueryKey key, CachedDnsResponse value, long currentTime) {
                    return value.lifetimeNanos();
                }

                @Override
                public long expireAfterUpdate(
                    QueryKey key,
                    CachedDnsResponse value,
                    long currentTime,
                    long currentDuration
                ) {
                    return value.lifetimeNanos();
                }

                @Override
                public long expireAfterRead(
                    QueryKey key,
                    CachedDnsResponse value,
                    long currentTime,
                    long currentDuration
                ) {
                    return currentDuration;
                }
            })
            .scheduler(Scheduler.systemScheduler())
            .recordStats()
            .build();
    }

    public Optional<byte[]> get(ParsedQuery query, long nowNanos) {
        CachedDnsResponse response = cache.getIfPresent(query.key());
        return response == null ? Optional.empty() : Optional.of(response.materialize(query, nowNanos));
    }

    public void put(QueryKey key, CachedDnsResponse response) {
        cache.put(key, response);
    }

    public CacheStats stats() {
        return cache.stats();
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
