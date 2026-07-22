package com.mikle.dns.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @Test
    void loadsTypedConfiguration(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config.ini");
        Files.writeString(file, """
            listen_ip=127.0.0.1
            listen_port=15353
            io_threads=2
            backend_dns=127.0.0.1:1053,[::1]:1054
            prefer_native_transport=false
            """);

        ServerConfig config = ServerConfig.load(file);

        assertEquals(15353, config.listenAddress().getPort());
        assertEquals(2, config.ioThreads());
        assertEquals(2, config.primaryUpstreams().size());
        assertFalse(config.preferNativeTransport());
    }
}
