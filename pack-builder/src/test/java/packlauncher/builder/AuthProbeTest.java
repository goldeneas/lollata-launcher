package packlauncher.builder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import packlauncher.common.FakeMinecraftServer;

/** authMode=auto: il pack-builder chiede al server Minecraft se è premium o offline. */
class AuthProbeTest {
    private static final String STATUS = FakeMinecraftServer.status(767, "");

    @Test
    void serverOnlineModeDiventaPremium() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.encryptionRequest())) {
            assertEquals("premium", PackBuilder.resolveAuthMode("auto", "127.0.0.1:" + s.port()));
            assertEquals("premium", PackBuilder.resolveAuthMode(null, "127.0.0.1:" + s.port()), "auto è il default");
        }
    }

    @Test
    void serverOfflineModeDiventaOffline() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.setCompression())) {
            assertEquals("offline", PackBuilder.resolveAuthMode("auto", "127.0.0.1:" + s.port()));
        }
    }

    @Test
    void ilValoreScrittoAManoVinceSullaSonda() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.encryptionRequest())) {
            assertEquals("offline", PackBuilder.resolveAuthMode("offline", "127.0.0.1:" + s.port()));
            assertTrue(s.logins.isEmpty(), "con authMode esplicito il server non viene contattato");
        }
    }

    @Test
    void serverSpentoRestaSconosciuto() throws IOException {
        int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) { port = free.getLocalPort(); }
        assertNull(PackBuilder.resolveAuthMode("auto", "127.0.0.1:" + port));
    }
}
