package com.snail.dnslb4j.config;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public record ServerConfig(
    InetSocketAddress listenAddress,
    int ioThreads,
    boolean preferNativeTransport,
    boolean reusePort,
    int receiveBufferBytes,
    int sendBufferBytes,
    int maxUdpPayloadBytes,
    List<InetSocketAddress> primaryUpstreams,
    List<InetSocketAddress> backupUpstreams,
    Duration requestTimeout,
    int maxAttempts,
    int maxInflight,
    String healthCheckDomain,
    Duration healthCheckInterval,
    int healthFailureThreshold,
    boolean cachePrimary,
    boolean cacheBackup,
    long cacheMaximumWeightBytes,
    int cacheMaximumTtlSeconds,
    Duration metricsInterval
) {
    public ServerConfig {
        primaryUpstreams = List.copyOf(primaryUpstreams);
        backupUpstreams = List.copyOf(backupUpstreams);
        if (primaryUpstreams.isEmpty()) {
            throw new IllegalArgumentException("backend_dns must contain at least one upstream");
        }
        if (ioThreads < 1 || receiveBufferBytes < 1 || sendBufferBytes < 1) {
            throw new IllegalArgumentException("thread and socket buffer settings must be positive");
        }
        if (maxUdpPayloadBytes < 512 || maxUdpPayloadBytes > 65_535) {
            throw new IllegalArgumentException("max_udp_payload_bytes must be between 512 and 65535");
        }
        if (maxInflight < 1 || maxInflight > 65_535) {
            throw new IllegalArgumentException("max_inflight must be between 1 and 65535");
        }
        if (maxAttempts < 1 || healthFailureThreshold < 1) {
            throw new IllegalArgumentException("attempt and health thresholds must be positive");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()
            || healthCheckInterval.isZero() || healthCheckInterval.isNegative()) {
            throw new IllegalArgumentException("request and health-check durations must be positive");
        }
        if (cacheMaximumWeightBytes < 1 || cacheMaximumTtlSeconds < 1) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
    }

    public static ServerConfig load(Path configFile) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        int processors = Runtime.getRuntime().availableProcessors();
        int configuredThreads = integer(properties, "io_threads", 0);
        int ioThreads = configuredThreads > 0 ? configuredThreads : Math.max(1, processors);
        List<InetSocketAddress> backups = new ArrayList<>(addresses(properties.getProperty("backup_dns", "")));
        backups.addAll(resolvConfAddresses(properties.getProperty("backup_dns_file", "")));

        return new ServerConfig(
            address(properties.getProperty("listen_ip", "0.0.0.0"), integer(properties, "listen_port", 53)),
            ioThreads,
            bool(properties, "prefer_native_transport", true),
            bool(properties, "reuse_port", true),
            integer(properties, "receive_buffer_bytes", 4 * 1024 * 1024),
            integer(properties, "send_buffer_bytes", 4 * 1024 * 1024),
            integer(properties, "max_udp_payload_bytes", 4096),
            addresses(required(properties, "backend_dns")),
            distinct(backups),
            Duration.ofMillis(integer(properties, "request_timeout_ms", 1500)),
            integer(properties, "max_attempts", 2),
            integer(properties, "max_inflight", 32_768),
            properties.getProperty("check_domain", "example.com").trim(),
            Duration.ofMillis(integer(properties, "check_interval_ms", 5000)),
            integer(properties, "health_failure_threshold", 3),
            bool(properties, "cache_backend", true),
            bool(properties, "cache_backup", false),
            longValue(properties, "cache_max_weight_bytes", 64L * 1024 * 1024),
            integer(properties, "cache_max_ttl_seconds", 1800),
            Duration.ofSeconds(integer(properties, "metrics_interval_seconds", 30))
        );
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing required configuration: " + key);
        }
        return value;
    }

    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
    }

    private static long longValue(Properties properties, String key, long fallback) {
        return Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)).trim());
    }

    private static List<InetSocketAddress> addresses(String value) throws UnknownHostException {
        if (value.isBlank()) {
            return List.of();
        }
        List<InetSocketAddress> result = new ArrayList<>();
        for (String token : value.split(",")) {
            result.add(parseAddress(token.trim()));
        }
        return distinct(result);
    }

    private static InetSocketAddress parseAddress(String value) throws UnknownHostException {
        if (value.startsWith("[")) {
            int bracket = value.indexOf(']');
            if (bracket < 0) {
                throw new IllegalArgumentException("Invalid IPv6 address: " + value);
            }
            String host = value.substring(1, bracket);
            if (bracket + 1 < value.length() && value.charAt(bracket + 1) != ':') {
                throw new IllegalArgumentException("Invalid IPv6 endpoint: " + value);
            }
            int port = bracket + 1 < value.length() ? Integer.parseInt(value.substring(bracket + 2)) : 53;
            return address(host, port);
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            return address(value.substring(0, firstColon), Integer.parseInt(value.substring(firstColon + 1)));
        }
        return address(value, 53);
    }

    private static InetSocketAddress address(String host, int port) throws UnknownHostException {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }

    private static List<InetSocketAddress> resolvConfAddresses(String value) throws IOException {
        if (value.isBlank()) {
            return List.of();
        }
        Path file = Path.of(value.trim());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("backup_dns_file does not exist: " + file);
        }
        List<InetSocketAddress> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String normalized = line.strip();
            if (normalized.startsWith("nameserver ")) {
                result.add(parseAddress(normalized.substring("nameserver ".length()).strip()));
            }
        }
        return result;
    }

    private static List<InetSocketAddress> distinct(List<InetSocketAddress> addresses) {
        Set<InetSocketAddress> result = new LinkedHashSet<>(addresses);
        return List.copyOf(result);
    }
}
