package com.snail.dnslb4j.upstream;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class UpstreamPool {
    private final List<UpstreamEndpoint> primary;
    private final List<UpstreamEndpoint> backup;
    private final List<UpstreamEndpoint> all;
    private final AtomicInteger primaryCursor = new AtomicInteger();
    private final AtomicInteger backupCursor = new AtomicInteger();

    public UpstreamPool(
        List<InetSocketAddress> primaryAddresses,
        List<InetSocketAddress> backupAddresses,
        int failureThreshold
    ) {
        primary = endpoints(primaryAddresses, UpstreamTier.PRIMARY, failureThreshold);
        backup = endpoints(backupAddresses, UpstreamTier.BACKUP, failureThreshold);
        List<UpstreamEndpoint> combined = new ArrayList<>(primary.size() + backup.size());
        combined.addAll(primary);
        combined.addAll(backup);
        all = List.copyOf(combined);
    }

    public UpstreamEndpoint select(Set<UpstreamEndpoint> excluded) {
        UpstreamEndpoint selected = select(primary, primaryCursor, excluded, true);
        if (selected == null) {
            selected = select(backup, backupCursor, excluded, true);
        }
        if (selected == null) {
            selected = select(primary, primaryCursor, excluded, false);
        }
        if (selected == null) {
            selected = select(backup, backupCursor, excluded, false);
        }
        return selected;
    }

    public List<UpstreamEndpoint> all() {
        return all;
    }

    private static UpstreamEndpoint select(
        List<UpstreamEndpoint> candidates,
        AtomicInteger cursor,
        Set<UpstreamEndpoint> excluded,
        boolean healthyOnly
    ) {
        if (candidates.isEmpty()) {
            return null;
        }
        int start = Math.floorMod(cursor.getAndIncrement(), candidates.size());
        UpstreamEndpoint selected = null;
        for (int offset = 0; offset < candidates.size(); offset++) {
            UpstreamEndpoint candidate = candidates.get((start + offset) % candidates.size());
            if (excluded.contains(candidate) || (healthyOnly && !candidate.healthy())) {
                continue;
            }
            if (selected == null || candidate.outstanding() < selected.outstanding()) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static List<UpstreamEndpoint> endpoints(
        List<InetSocketAddress> addresses,
        UpstreamTier tier,
        int failureThreshold
    ) {
        return addresses.stream()
            .map(address -> new UpstreamEndpoint(address, tier, failureThreshold))
            .toList();
    }
}
