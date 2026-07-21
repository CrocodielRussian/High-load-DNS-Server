package com.snail.dnslb4j.server;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

public record NettyTransport(
    MultiThreadIoEventLoopGroup eventLoopGroup,
    Class<? extends DatagramChannel> datagramChannelClass,
    boolean nativeEpoll
) {
    public static NettyTransport create(int ioThreads, boolean preferNative) {
        DefaultThreadFactory threadFactory = new DefaultThreadFactory("dns-io", false);
        if (preferNative && Epoll.isAvailable()) {
            return new NettyTransport(
                new MultiThreadIoEventLoopGroup(ioThreads, threadFactory, EpollIoHandler.newFactory()),
                EpollDatagramChannel.class,
                true
            );
        }
        return new NettyTransport(
            new MultiThreadIoEventLoopGroup(ioThreads, threadFactory, NioIoHandler.newFactory()),
            NioDatagramChannel.class,
            false
        );
    }
}
