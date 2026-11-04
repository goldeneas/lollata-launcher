package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import packlauncher.common.FakeMinecraftServer;
import packlauncher.common.ServerProbe;

/** Il launcher chiede al server Minecraft se è premium a ogni avvio: vale più del manifest. */
class LiveAuthModeTest {
    private static final String STATUS = FakeMinecraftServer.status(767, "");
    private final ServerProbe probe = new ServerProbe(2000);

    private static Pack pack(String server, String authMode) {
        Pack p = new Pack();
        p.id = "test";
        p.name = "Server di prova";
        p.minecraft = "1.21.1";
        p.server = server;
        p.authMode = authMode;
        return p;
    }

    @Test
    void serverPassatoAPremiumDopoLaPubblicazione() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.encryptionRequest())) {
            Pack p = pack("127.0.0.1:" + s.port(), Pack.AUTH_OFFLINE);   // manifest vecchio
            ServerProbe.Result r = LaunchFlow.refreshAuthMode(p, probe);
            assertEquals(Pack.AUTH_PREMIUM, r.authMode());
            assertEquals(Pack.AUTH_PREMIUM, p.authMode);
            assertThrows(IOException.class, () -> LaunchFlow.checkAccess(p, Session.offline("Steve")),
                    "con il solo nickname non si entra più");
            assertTrue(LaunchFlow.describeProbe(p, r).contains("premium"));
        }
    }

    @Test
    void serverPassatoAOfflineDopoLaPubblicazione() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.setCompression())) {
            Pack p = pack("127.0.0.1:" + s.port(), Pack.AUTH_PREMIUM);
            LaunchFlow.refreshAuthMode(p, probe);
            assertEquals(Pack.AUTH_OFFLINE, p.authMode);
            LaunchFlow.checkAccess(p, Session.offline("Steve"));
        }
    }

    @Test
    void serverSpentoUsaIlManifest() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = free.getLocalPort(); }
        Pack p = pack("127.0.0.1:" + port, Pack.AUTH_PREMIUM);
        ServerProbe.Result r = LaunchFlow.refreshAuthMode(p, probe);
        assertNull(r.authMode());
        assertEquals(Pack.AUTH_PREMIUM, p.authMode, "resta il valore del manifest");
        assertFalse(r.online());
        assertFalse(LaunchFlow.serverOnline(r), "server spento: niente Gioca");
        assertTrue(LaunchFlow.describeProbe(p, r).contains("spento"));
    }

    @Test
    void serverAccesoMaRispostaStranaEComunqueRaggiungibile() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(STATUS, l -> FakeMinecraftServer.disconnect("{\"text\":\"manutenzione\"}"))) {
            Pack p = pack("127.0.0.1:" + s.port(), Pack.AUTH_PREMIUM);
            ServerProbe.Result r = LaunchFlow.refreshAuthMode(p, probe);
            assertTrue(LaunchFlow.serverOnline(r));
            assertEquals(Pack.AUTH_PREMIUM, p.authMode, "risposta non chiara: resta il manifest");
            assertTrue(LaunchFlow.describeProbe(p, r).contains("uso il manifest"));
        }
    }

    @Test
    void prepareRifiutaUnServerSpento(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws IOException {
        String old = System.getProperty("launcher.home");
        System.setProperty("launcher.home", home.toString());
        Config cfg;
        try { cfg = new Config(); } finally {
            if (old == null) System.clearProperty("launcher.home"); else System.setProperty("launcher.home", old);
        }
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = free.getLocalPort(); }
        Pack p = pack("127.0.0.1:" + port, Pack.AUTH_OFFLINE);
        IOException e = assertThrows(IOException.class, () -> LaunchFlow.prepare(cfg, null, p,
                Session.offline("Steve"), 2048, Progress.CONSOLE));
        assertTrue(e.getMessage().contains("spento"), e.getMessage());
    }

    @Test
    void senzaServerNonSondaNiente() {
        Pack p = pack(null, Pack.AUTH_OFFLINE);
        assertNull(LaunchFlow.refreshAuthMode(p, probe));
        assertTrue(LaunchFlow.serverOnline(null), "pack senza server: si gioca comunque");
        assertEquals(Pack.AUTH_OFFLINE, p.authMode);
    }
}
