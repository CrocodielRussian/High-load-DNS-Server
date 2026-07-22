package com.mikle.dns.server;

import com.mikle.dns.cache.DnsResponseCache;
import com.mikle.dns.config.ServerConfig;
import com.mikle.dns.metrics.ServerMetrics;
import com.mikle.dns.upstream.HealthChecker;
import com.mikle.dns.upstream.UpstreamPool;
import com.mikle.dns.upstream.UpstreamResolver;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DnsProxyServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DnsProxyServer.class);

    private final ServerConfig config;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Channel> listeners = new ArrayList<>();
    private final List<UpstreamResolver> resolvers = new ArrayList<>();
    private NettyTransport transport;
    private HealthChecker healthChecker;
    private ScheduledFuture<?> metricsTask;
    private DnsResponseCache cache;
    private DnsProxyService service;
    private ServerMetrics metrics;

    public DnsProxyServer(ServerConfig config) {
        this.config = config;
    }

    public void start() {
        transport = NettyTransport.create(config.ioThreads(), config.preferNativeTransport());
        int listenerCount = transport.nativeEpoll() && config.reusePort() && config.listenAddress().getPort() != 0
            ? config.ioThreads()
            : 1;
        int perResolverInflight = Math.max(1, config.maxInflight() / listenerCount);

        UpstreamPool upstreamPool = new UpstreamPool(
            config.primaryUpstreams(), config.backupUpstreams(), config.healthFailureThreshold());
        cache = new DnsResponseCache(config.cacheMaximumWeightBytes());
        metrics = new ServerMetrics();
        service = new DnsProxyService(
            cache,
            config.cacheMaximumTtlSeconds(),
            config.maxAttempts(),
            config.maxInflight(),
            config.cachePrimary(),
            config.cacheBackup(),
            metrics
        );

        try {
            for (int index = 0; index < listenerCount; index++) {
                UpstreamResolver resolver = new UpstreamResolver(
                    transport.eventLoopGroup(),
                    transport.datagramChannelClass(),
                    upstreamPool,
                    config.requestTimeout(),
                    perResolverInflight,
                    config.sendBufferBytes(),
                    config.receiveBufferBytes(),
                    config.maxUdpPayloadBytes(),
                    metrics
                );
                resolver.start();
                resolvers.add(resolver);
                listeners.add(bindListener(resolver));
            }

            healthChecker = new HealthChecker(
                upstreamPool, resolvers.getFirst(), config.healthCheckDomain(), config.healthCheckInterval());
            healthChecker.start();
            startMetricsReporter();
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }

        LOGGER.info(
            "DNS server listening on {} with transport={}, listeners={}, ioThreads={}, upstreams={}",
            listeners.getFirst().localAddress(),
            transport.nativeEpoll() ? "epoll" : "nio",
            listeners.size(),
            config.ioThreads(),
            upstreamPool.all()
        );
    }

    public void await() {
        if (listeners.isEmpty()) {
            throw new IllegalStateException("Server is not started");
        }
        listeners.getFirst().closeFuture().syncUninterruptibly();
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) listeners.getFirst().localAddress();
    }

    private Channel bindListener(UpstreamResolver resolver) {
        Bootstrap bootstrap = new Bootstrap()
            .group(transport.eventLoopGroup())
            .channel(transport.datagramChannelClass())
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.SO_RCVBUF, config.receiveBufferBytes())
            .option(ChannelOption.SO_SNDBUF, config.sendBufferBytes())
            .option(ChannelOption.RECVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(config.maxUdpPayloadBytes()))
            .handler(new DnsProxyHandler(service, resolver, metrics));
        if (transport.nativeEpoll() && config.reusePort()) {
            bootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
        }
        return bootstrap.bind(config.listenAddress()).syncUninterruptibly().channel();
    }

    private void startMetricsReporter() {
        if (config.metricsInterval().isZero() || config.metricsInterval().isNegative()) {
            return;
        }
        long interval = config.metricsInterval().toSeconds();
        metricsTask = transport.eventLoopGroup().next().scheduleAtFixedRate(() -> {
            int resolverInflight = resolvers.stream().mapToInt(UpstreamResolver::inFlight).sum();
            ServerMetrics.Snapshot snapshot = metrics.snapshot(
                cache.stats(), cache.estimatedSize(), service.inFlightQueries() + resolverInflight);
            LOGGER.info("metrics={}", snapshot);
        }, interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (metricsTask != null) {
            metricsTask.cancel(false);
        }
        listeners.forEach(channel -> channel.close().syncUninterruptibly());
        if (healthChecker != null) {
            healthChecker.close();
        }
        resolvers.forEach(UpstreamResolver::close);
        if (transport != null) {
            transport.eventLoopGroup().shutdownGracefully().syncUninterruptibly();
        }
        LOGGER.info("DNS server stopped");
    }
}
