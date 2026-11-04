package packlauncher.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Server Minecraft finto per i test (condiviso con pack-builder come test fixture): risponde allo status ping con un JSON fisso e al Login Start
 * con il pacchetto deciso da {@code onLogin} (che riceve l'indirizzo dell'handshake, marker FML incluso).
 */
public final class FakeMinecraftServer implements AutoCloseable {
    /** Handshake + Login Start ricevuti. */
    public record Login(int protocol, String address, int port, String name, byte[] loginStart) {}

    private final ServerSocket socket;
    private final String statusJson;
    private final Function<Login, byte[]> onLogin;
    public final List<Login> logins = new CopyOnWriteArrayList<>();
    private final Thread thread;

    public FakeMinecraftServer(String statusJson, Function<Login, byte[]> onLogin) throws IOException {
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.statusJson = statusJson;
        this.onLogin = onLogin;
        this.thread = new Thread(this::loop, "fake-mc");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() { return socket.getLocalPort(); }

    private void loop() {
        while (!socket.isClosed()) {
            try (Socket s = socket.accept()) {
                handle(s);
            } catch (IOException ignored) {
                // connessione chiusa dal client o server fermato
            }
        }
    }

    private void handle(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        ByteBuffer hs = ByteBuffer.wrap(ServerProbe.readPacket(in));
        ServerProbe.readVarInt(hs);                       // id 0x00
        int protocol = ServerProbe.readVarInt(hs);
        String address = ServerProbe.readString(hs);
        int port = ((hs.get() & 0xFF) << 8) | (hs.get() & 0xFF);
        int next = ServerProbe.readVarInt(hs);
        if (next == 1) {
            ServerProbe.readPacket(in);                   // status request
            out.write(ServerProbe.packet(concat(new byte[] {0x00}, string(statusJson))));
            out.flush();
            return;
        }
        byte[] login = ServerProbe.readPacket(in);
        ByteBuffer lb = ByteBuffer.wrap(login);
        ServerProbe.readVarInt(lb);
        String name = ServerProbe.readString(lb);
        Login l = new Login(protocol, address, port, name, login);
        logins.add(l);
        byte[] reply = onLogin.apply(l);
        if (reply != null) {
            out.write(ServerProbe.packet(reply));
            out.flush();
        }
    }

    // ------------------------------------------------------- risposte pronte

    public static byte[] encryptionRequest() {
        return concat(new byte[] {0x01}, string(""), new byte[] {0x00, 0x00});
    }

    public static byte[] setCompression() { return new byte[] {0x03, 0x00, 0x02}; }

    public static byte[] loginSuccess() { return concat(new byte[] {0x02}, new byte[16], string(ServerProbe.PROBE_NAME)); }

    public static byte[] disconnect(String json) { return concat(new byte[] {0x00}, string(json)); }

    public static String status(int protocol, String extra) {
        return "{\"version\":{\"name\":\"1.21.1\",\"protocol\":" + protocol + "},"
                + "\"players\":{\"max\":20,\"online\":0},\"description\":\"test\"" + extra + "}";
    }

    public static byte[] string(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ServerProbe.writeString(b, s);
        return b.toByteArray();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.writeBytes(p);
        return b.toByteArray();
    }

    @Override public void close() throws IOException { socket.close(); }
}
