package com.mikle.dns;

import com.mikle.dns.config.ServerConfig;
import com.mikle.dns.server.DnsProxyServer;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DnsServer {
    private DnsServer() {
    }

    public static void main(String[] args) throws Exception {
        String environment = args.length == 1 ? args[0] : "development";
        if (!environment.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid environment name: " + environment);
        }

        Path configDirectory = Path.of("config", environment).toAbsolutePath().normalize();
        System.setProperty("logback.configurationFile", configDirectory.resolve("logback.xml").toString());
        Logger logger = LoggerFactory.getLogger(DnsServer.class);
        ServerConfig config = ServerConfig.load(configDirectory.resolve("config.ini"));

        DnsProxyServer server = new DnsProxyServer(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("dns-shutdown").unstarted(server::close));
        server.start();
        logger.info("DnsLB4J started with environment={} and Java={}", environment, Runtime.version());
        server.await();
    }
}
