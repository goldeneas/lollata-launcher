package packlauncher.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ServerProbeTest {
    private static final int PROTO_1_21_1 = 767;
    private final ServerProbe probe = new ServerProbe(3000);

    private ServerProbe.Result probe(FakeMinecraftServer s) {
        return probe.probe("127.0.0.1:" + s.port());
    }

    @Test
    void onlineModeIsPremium() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(PROTO_1_21_1, ""),
                l -> FakeMinecraftServer.encryptionRequest())) {
            ServerProbe.Result r = probe(s);
            assertEquals(ServerProbe.PREMIUM, r.authMode());
            assertEquals(PROTO_1_21_1, r.protocol());
            assertEquals("1.21.1", r.version());
            // il login usa la versione di protocollo letta dallo status
            FakeMinecraftServer.Login l = s.logins.get(0);
            assertEquals(PROTO_1_21_1, l.protocol());
            assertEquals(ServerProbe.PROBE_NAME, l.name());
            assertEquals("127.0.0.1", l.address());
            assertEquals(s.port(), l.port());
        }
    }

    @Test
    void compressionMeansOffline() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(PROTO_1_21_1, ""),
                l -> FakeMinecraftServer.setCompression())) {
            assertEquals(ServerProbe.OFFLINE, probe(s).authMode());
        }
    }

    @Test
    void loginSuccessMeansOffline() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(340, ""),
                l -> FakeMinecraftServer.loginSuccess())) {
            assertEquals(ServerProbe.OFFLINE, probe(s).authMode());
        }
    }

    @Test
    void whitelistKickMeansOffline() throws IOException {
        String kick = "{\"translate\":\"multiplayer.disconnect.not_whitelisted\"}";
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(PROTO_1_21_1, ""),
                l -> FakeMinecraftServer.disconnect(kick))) {
            ServerProbe.Result r = probe(s);
            assertEquals(ServerProbe.OFFLINE, r.authMode());
            assertTrue(r.detail().contains("whitelist"), r.detail());
        }
    }

    @Test
    void otherKickIsUnknown() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(PROTO_1_21_1, ""),
                l -> FakeMinecraftServer.disconnect("{\"text\":\"Server in manutenzione\"}"))) {
            ServerProbe.Result r = probe(s);
            assertNull(r.authMode());
            assertTrue(r.detail().contains("manutenzione"), r.detail());
        }
    }

    @Test
    void closedConnectionIsUnknown() throws IOException {
        try (FakeMinecraftServer s = new FakeMinecraftServer(FakeMinecraftServer.status(PROTO_1_21_1, ""), l -> null)) {
            assertNull(probe(s).authMode());
        }
    }

    @Test
    void unreachableServerIsUnknown() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = free.getLocalPort(); }
        ServerProbe.Result r = probe.probe("127.0.0.1", port);
        assertNull(r.authMode());
        assertTrue(r.detail().contains("non raggiungibile"), r.detail());
    }

    @Test
    void forgeServerGetsFmlMarker() throws IOException {
        // Forge 1.20.1: senza "\0FML3\0" nell'indirizzo rifiuta il client
        String status = FakeMinecraftServer.status(763, ",\"forgeData\":{\"fmlNetworkVersion\":3,\"channels\":[],\"mods\":[]}");
        try (FakeMinecraftServer s = new FakeMinecraftServer(status, l -> l.address().endsWith("\0FML3\0")
                ? FakeMinecraftServer.encryptionRequest()
                : FakeMinecraftServer.disconnect("{\"text\":\"This server has mods that require Forge\"}"))) {
            assertEquals(ServerProbe.PREMIUM, probe(s).authMode());
            assertEquals("127.0.0.1\0FML3\0", s.logins.get(0).address());
        }
    }

    @Test
    void retriesWithoutMarkerIfRejected() throws IOException {
        String status = FakeMinecraftServer.status(763, ",\"forgeData\":{\"fmlNetworkVersion\":3}");
        try (FakeMinecraftServer s = new FakeMinecraftServer(status, l -> l.address().contains("FML")
                ? FakeMinecraftServer.disconnect("{\"text\":\"bad marker\"}")
                : FakeMinecraftServer.setCompression())) {
            assertEquals(ServerProbe.OFFLINE, probe(s).authMode());
            assertEquals(2, s.logins.size());
        }
    }

    @Test
    void forgeMarkerFromStatus() {
        assertEquals("", ServerProbe.forgeMarker(Map.of()));
        assertEquals("\0FML2\0", ServerProbe.forgeMarker(Json.parseObject("{\"forgeData\":{\"fmlNetworkVersion\":2}}")));
        assertEquals("\0FML\0", ServerProbe.forgeMarker(Json.parseObject("{\"modinfo\":{\"type\":\"FML\",\"modList\":[]}}")));
    }

    @Test
    void loginStartFormatDependsOnVersion() {
        int base = 1 + 1 + ServerProbe.PROBE_NAME.length();   // id + lunghezza + nome
        assertEquals(base, ServerProbe.loginStart(340, ServerProbe.PROBE_NAME).length);       // 1.12.2
        assertEquals(base + 1, ServerProbe.loginStart(759, ServerProbe.PROBE_NAME).length);   // 1.19
        assertEquals(base + 2, ServerProbe.loginStart(760, ServerProbe.PROBE_NAME).length);   // 1.19.2
        assertEquals(base + 17, ServerProbe.loginStart(763, ServerProbe.PROBE_NAME).length);  // 1.20.1
        assertEquals(base + 16, ServerProbe.loginStart(767, ServerProbe.PROBE_NAME).length);  // 1.21.1
    }

    @Test
    void varIntRoundTrip() throws IOException {
        for (int v : new int[] {0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1}) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ServerProbe.writeVarInt(b, v);
            assertEquals(v, ServerProbe.readVarInt(new ByteArrayInputStream(b.toByteArray())));
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ServerProbe.writeVarInt(b, 300);
        assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, b.toByteArray());
    }

    @Test
    void packetFraming() throws IOException {
        byte[] body = {1, 2, 3};
        byte[] framed = ServerProbe.packet(body);
        assertArrayEquals(body, ServerProbe.readPacket(new DataInputStream(new ByteArrayInputStream(framed))));
    }
}
