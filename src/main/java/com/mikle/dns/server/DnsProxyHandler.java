package com.mikle.dns.server;

import com.mikle.dns.dns.DnsFormatException;
import com.mikle.dns.dns.DnsWire;
import com.mikle.dns.dns.ParsedQuery;
import com.mikle.dns.metrics.ServerMetrics;
import com.mikle.dns.upstream.UpstreamResolver;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DnsProxyHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DnsProxyHandler.class);

    private final DnsProxyService service;
    private final UpstreamResolver resolver;
    private final ServerMetrics metrics;

    public DnsProxyHandler(DnsProxyService service, UpstreamResolver resolver, ServerMetrics metrics) {
        this.service = service;
        this.resolver = resolver;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
        metrics.request();
        byte[] wire = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), wire);
        InetSocketAddress client = packet.sender();

        final ParsedQuery query;
        try {
            query = DnsWire.parseQuery(wire);
        } catch (DnsFormatException exception) {
            metrics.invalidRequest();
            write(context, client, DnsWire.formatError(wire));
            return;
        }

        service.resolve(query, resolver, context.executor()).whenCompleteAsync((response, failure) -> {
            if (failure != null) {
                metrics.failure();
                write(context, client, DnsWire.errorResponse(query, DnsWire.RCODE_SERVER_FAILURE));
            } else {
                write(context, client, response);
            }
        }, context.executor());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.warn("DNS listener pipeline failure", cause);
    }

    private static void write(ChannelHandlerContext context, InetSocketAddress recipient, byte[] response) {
        if (context.channel().isActive()) {
            context.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(response), recipient));
        }
    }
}
