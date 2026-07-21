package com.snail.dnslb4j.upstream;

import com.snail.dnslb4j.dns.DnsWire;
import com.snail.dnslb4j.dns.ParsedQuery;
import com.snail.dnslb4j.metrics.ServerMetrics;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class UpstreamResolver implements AutoCloseable {
    private final EventLoopGroup eventLoopGroup;
    private final Class<? extends DatagramChannel> channelClass;
    private final UpstreamPool pool;
    private final Duration timeout;
    private final int maxInflight;
    private final int sendBufferBytes;
    private final int receiveBufferBytes;
    private final int maxUdpPayloadBytes;
    private final ServerMetrics metrics;
    private final IntObjectMap<PendingAttempt> pending = new IntObjectHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private Channel channel;
    private int nextId;

    public UpstreamResolver(
        EventLoopGroup eventLoopGroup,
        Class<? extends DatagramChannel> channelClass,
        UpstreamPool pool,
        Duration timeout,
        int maxInflight,
        int sendBufferBytes,
        int receiveBufferBytes,
        int maxUdpPayloadBytes,
        ServerMetrics metrics
    ) {
        this.eventLoopGroup = eventLoopGroup;
        this.channelClass = channelClass;
        this.pool = pool;
        this.timeout = timeout;
        this.maxInflight = maxInflight;
        this.sendBufferBytes = sendBufferBytes;
        this.receiveBufferBytes = receiveBufferBytes;
        this.maxUdpPayloadBytes = maxUdpPayloadBytes;
        this.metrics = metrics;
    }

    public void start() {
        channel = new Bootstrap()
            .group(eventLoopGroup)
            .channel(channelClass)
            .option(io.netty.channel.ChannelOption.SO_SNDBUF, sendBufferBytes)
            .option(io.netty.channel.ChannelOption.SO_RCVBUF, receiveBufferBytes)
            .option(io.netty.channel.ChannelOption.RECVBUF_ALLOCATOR,
                new io.netty.channel.FixedRecvByteBufAllocator(maxUdpPayloadBytes))
            .handler(new ResponseHandler())
            .bind(new InetSocketAddress(0))
            .syncUninterruptibly()
            .channel();
    }

    public CompletableFuture<UpstreamResponse> resolve(ParsedQuery query, int maxAttempts) {
        RequestState state = new RequestState(query, maxAttempts, null);
        execute(state, () -> startAttempt(state));
        return state.result;
    }

    public CompletableFuture<UpstreamResponse> probe(UpstreamEndpoint endpoint, ParsedQuery query) {
        RequestState state = new RequestState(query, 1, endpoint);
        execute(state, () -> startAttempt(state));
        return state.result;
    }

    public int inFlight() {
        return inFlight.get();
    }

    io.netty.channel.EventLoop eventLoop() {
        return channel.eventLoop();
    }

    private void execute(RequestState state, Runnable task) {
        try {
            channel.eventLoop().execute(task);
        } catch (RejectedExecutionException exception) {
            state.result.completeExceptionally(exception);
        }
    }

    private void startAttempt(RequestState state) {
        if (state.result.isDone()) {
            return;
        }
        if (pending.size() >= maxInflight) {
            state.result.completeExceptionally(new RejectedExecutionException("Upstream in-flight limit reached"));
            return;
        }

        UpstreamEndpoint endpoint = state.target != null ? state.target : pool.select(state.attempted);
        if (endpoint == null || state.attempted.size() >= state.maxAttempts) {
            state.result.completeExceptionally(new TimeoutException("No upstream available"));
            return;
        }
        state.attempted.add(endpoint);

        int id = allocateId();
        if (id < 0) {
            state.result.completeExceptionally(new RejectedExecutionException("DNS transaction ID space exhausted"));
            return;
        }

        byte[] request = state.query.wire().clone();
        DnsWire.writeUnsignedShort(request, 0, id);
        ByteBuf content = channel.alloc().buffer(request.length).writeBytes(request);
        DatagramPacket packet = new DatagramPacket(content, endpoint.address());

        PendingAttempt attempt = new PendingAttempt(state, endpoint);
        pending.put(id, attempt);
        inFlight.incrementAndGet();
        endpoint.requestStarted();
        metrics.upstreamRequest();
        attempt.timeout = channel.eventLoop().schedule(() -> failAttempt(id, attempt, null), timeout.toNanos(),
            java.util.concurrent.TimeUnit.NANOSECONDS);

        ChannelFuture write = channel.writeAndFlush(packet);
        write.addListener(future -> {
            if (!future.isSuccess()) {
                failAttempt(id, attempt, future.cause());
            }
        });
    }

    private int allocateId() {
        for (int attempts = 0; attempts < 65_536; attempts++) {
            int candidate = nextId++ & 0xffff;
            if (!pending.containsKey(candidate)) {
                return candidate;
            }
        }
        return -1;
    }

    private void failAttempt(int id, PendingAttempt expected, Throwable cause) {
        PendingAttempt attempt = pending.get(id);
        if (attempt != expected) {
            return;
        }
        pending.remove(id);
        inFlight.decrementAndGet();
        attempt.timeout.cancel(false);
        attempt.endpoint.requestFinished();
        attempt.endpoint.markFailure();
        metrics.upstreamTimeout();

        RequestState state = attempt.state;
        if (state.target == null && state.attempted.size() < state.maxAttempts) {
            startAttempt(state);
        } else {
            Throwable failure = cause != null ? cause : new TimeoutException("DNS upstream timeout");
            state.result.completeExceptionally(failure);
        }
    }

    private void receive(DatagramPacket packet) {
        if (packet.content().readableBytes() < 2) {
            return;
        }
        int id = packet.content().getUnsignedShort(packet.content().readerIndex());
        PendingAttempt attempt = pending.get(id);
        if (attempt == null || !attempt.endpoint.address().equals(packet.sender())) {
            return;
        }

        byte[] response = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), response);
        if (!DnsWire.responseMatches(response, attempt.state.query.key())) {
            return;
        }

        pending.remove(id);
        inFlight.decrementAndGet();
        attempt.timeout.cancel(false);
        attempt.endpoint.requestFinished();
        attempt.endpoint.markSuccess();
        metrics.upstreamResponse();
        DnsWire.writeUnsignedShort(response, 0, 0);
        attempt.state.result.complete(new UpstreamResponse(response, attempt.endpoint.tier()));
    }

    @Override
    public void close() {
        if (channel == null) {
            return;
        }
        channel.eventLoop().submit(() -> {
            TimeoutException shutdown = new TimeoutException("Resolver is shutting down");
            for (IntObjectMap.PrimitiveEntry<PendingAttempt> entry : pending.entries()) {
                PendingAttempt attempt = entry.value();
                attempt.timeout.cancel(false);
                attempt.endpoint.requestFinished();
                attempt.state.result.completeExceptionally(shutdown);
            }
            pending.clear();
            inFlight.set(0);
        }).syncUninterruptibly();
        channel.close().syncUninterruptibly();
    }

    private final class ResponseHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
            receive(packet);
        }
    }

    private static final class RequestState {
        private final ParsedQuery query;
        private final int maxAttempts;
        private final UpstreamEndpoint target;
        private final Set<UpstreamEndpoint> attempted = Collections.newSetFromMap(new IdentityHashMap<>());
        private final CompletableFuture<UpstreamResponse> result = new CompletableFuture<>();

        private RequestState(ParsedQuery query, int maxAttempts, UpstreamEndpoint target) {
            this.query = query;
            this.maxAttempts = maxAttempts;
            this.target = target;
        }
    }

    private static final class PendingAttempt {
        private final RequestState state;
        private final UpstreamEndpoint endpoint;
        private ScheduledFuture<?> timeout;

        private PendingAttempt(RequestState state, UpstreamEndpoint endpoint) {
            this.state = state;
            this.endpoint = endpoint;
        }
    }
}
