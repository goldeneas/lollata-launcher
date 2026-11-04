package packlauncher.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Capisce se un server Minecraft è in online-mode (serve un account Microsoft "premium")
 * o in offline-mode (basta un nickname), parlando il protocollo del gioco.
 *
 * <ol>
 *   <li>Status ping: legge la versione del protocollo del server (e se è Forge, per il marker FML).</li>
 *   <li>Handshake di login + Login Start con un nome di prova.</li>
 *   <li>Il server risponde con:
 *     <ul>
 *       <li>Encryption Request (0x01): online-mode, il client deve autenticarsi con Mojang → {@code premium};</li>
 *       <li>Set Compression (0x03), Login Success (0x02) o Plugin Request (0x04): il login è andato avanti
 *           senza autenticazione → {@code offline};</li>
 *       <li>Disconnect (0x00) per whitelist/ban: anche questo arriva solo dopo la fase di autenticazione,
 *           quindi → {@code offline}. Altri Disconnect (es. versione o mod mancanti) → sconosciuto.</li>
 *     </ul>
 *   </li>
 * </ol>
 * La connessione viene chiusa subito dopo la risposta: il nome di prova non entra mai davvero nel mondo
 * (dalla 1.20.2 il server aspetta ancora il Login Acknowledged).
 */
public final class ServerProbe {
    /** Nome usato nel Login Start (deve essere un nome valido: 3-16 caratteri). */
    public static final String PROBE_NAME = "LollataProbe";

    private final int timeoutMs;

    public ServerProbe(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public ServerProbe() { this(5000); }

    /**
     * @param authMode {@link Minecraft#PREMIUM}, {@link Minecraft#OFFLINE} o null se non è stato possibile capirlo
     * @param detail   spiegazione leggibile (cosa ha risposto il server)
     * @param online   false se il server non risponde nemmeno allo status ping (spento o irraggiungibile)
     */
    public record Result(String authMode, String detail, int protocol, String version, boolean online) {}

    /** "host" o "host:porta" (porta 25565 di default). */
    public Result probe(String server) {
        Minecraft.Address a = Minecraft.parseAddress(server);
        return probe(a.host(), a.port());
    }

    public Result probe(String host, int port) {
        int protocol;
        String version;
        String marker;
        try {
            Map<String, Object> status = status(host, port);
            Map<String, Object> v = Json.obj(status, "version");
            protocol = (int) Json.num(v, "protocol", -1);
            version = Json.str(v, "name");
            marker = forgeMarker(status);
        } catch (IOException | RuntimeException e) {
            return new Result(null, "server non raggiungibile (" + host + ":" + port + "): " + e.getMessage(), -1, null, false);
        }
        if (protocol < 0) return new Result(null, "il server non ha indicato la versione del protocollo", protocol, version, true);

        // Con il marker Forge prima; se la risposta non è chiara si riprova senza.
        List<String> markers = marker.isEmpty() ? List.of("") : List.of(marker, "");
        String last = null;
        for (String m : markers) {
            try {
                Answer a = login(host, port, protocol, m);
                if (a.authMode != null) return new Result(a.authMode, a.detail, protocol, version, true);
                last = a.detail;
            } catch (IOException e) {
                last = "errore durante il login di prova: " + e.getMessage();
            }
        }
        return new Result(null, last, protocol, version, true);
    }

    // ---------------------------------------------------------------- status

    Map<String, Object> status(String host, int port) throws IOException {
        try (Socket s = connect(host, port)) {
            OutputStream out = s.getOutputStream();
            out.write(packet(handshake(-1, host, port, 1, "")));
            out.write(packet(new byte[] {0x00}));
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] body = readPacket(in);
            ByteBuffer b = ByteBuffer.wrap(body);
            int id = readVarInt(b);
            if (id != 0x00) throw new IOException("risposta di status inattesa (pacchetto " + id + ")");
            return Json.parseObject(readString(b));
        }
    }

    /** Marker FML da aggiungere all'indirizzo nell'handshake, altrimenti Forge rifiuta i client "vanilla". */
    static String forgeMarker(Map<String, Object> status) {
        Map<String, Object> forge = Json.obj(status, "forgeData");
        if (forge != null) {
            long n = Json.num(forge, "fmlNetworkVersion", -1);
            if (n > 0) return "\0FML" + n + "\0";
        }
        Map<String, Object> modinfo = Json.obj(status, "modinfo");
        if (modinfo != null && "FML".equals(Json.str(modinfo, "type"))) return "\0FML\0";
        return "";
    }

    // ----------------------------------------------------------------- login

    private record Answer(String authMode, String detail) {}

    private Answer login(String host, int port, int protocol, String marker) throws IOException {
        try (Socket s = connect(host, port)) {
            OutputStream out = s.getOutputStream();
            out.write(packet(handshake(protocol, host, port, 2, marker)));
            out.write(packet(loginStart(protocol, PROBE_NAME)));
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] body;
            try {
                body = readPacket(in);
            } catch (EOFException e) {
                return new Answer(null, "il server ha chiuso la connessione senza rispondere al login");
            }
            ByteBuffer b = ByteBuffer.wrap(body);
            int id = readVarInt(b);
            return switch (id) {
                case 0x01 -> new Answer(Minecraft.PREMIUM, "il server chiede la cifratura (online-mode=true)");
                case 0x02, 0x03 -> new Answer(Minecraft.OFFLINE, "login accettato senza autenticazione (online-mode=false)");
                case 0x04 -> new Answer(Minecraft.OFFLINE, "il server ha proseguito il login senza autenticazione (online-mode=false)");
                case 0x00 -> disconnect(readString(b));
                default -> new Answer(null, "risposta al login inattesa (pacchetto " + id + ")");
            };
        }
    }

    /** Un Disconnect per whitelist/ban arriva solo dopo la fase di autenticazione: il server è offline. */
    private static Answer disconnect(String reason) {
        String low = reason.toLowerCase(Locale.ROOT);
        if (low.contains("whitelist") || low.contains("white-list") || low.contains("white_list") || low.contains("banned")) {
            return new Answer(Minecraft.OFFLINE, "il server ha controllato la whitelist senza autenticazione "
                    + "(online-mode=false): " + reason);
        }
        return new Answer(null, "il server ha rifiutato il login di prova: " + reason);
    }

    private Socket connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        return s;
    }

    // ------------------------------------------------------------- encoding

    static byte[] handshake(int protocol, String host, int port, int nextState, String marker) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00);
        writeVarInt(b, protocol);
        writeString(b, host + marker);
        b.write((port >> 8) & 0xFF);
        b.write(port & 0xFF);
        writeVarInt(b, nextState);
        return b.toByteArray();
    }

    /** Login Start: il formato cambia con le versioni (1.19, 1.19.1, 1.19.3, 1.20.2). */
    static byte[] loginStart(int protocol, String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00);
        writeString(b, name);
        UUID uuid = Minecraft.offlineUuid(name);
        if (protocol >= 764) {            // 1.20.2+: UUID obbligatorio
            writeUuid(b, uuid);
        } else if (protocol >= 761) {     // 1.19.3 - 1.20.1: UUID opzionale
            b.write(1);
            writeUuid(b, uuid);
        } else if (protocol == 760) {     // 1.19.1/1.19.2: firma opzionale + UUID opzionale
            b.write(0);
            b.write(0);
        } else if (protocol == 759) {     // 1.19: firma opzionale
            b.write(0);
        }
        return b.toByteArray();
    }

    static byte[] packet(byte[] body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, body.length);
        b.writeBytes(body);
        return b.toByteArray();
    }

    static byte[] readPacket(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 2 * 1024 * 1024) throw new IOException("pacchetto troppo grande: " + len);
        byte[] body = new byte[len];
        in.readFully(body);
        return body;
    }

    static void writeVarInt(ByteArrayOutputStream b, int v) {
        while ((v & ~0x7F) != 0) {
            b.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b.write(v);
    }

    /** Un byte alla volta, -1 a fine dati. */
    private interface ByteSource { int next() throws IOException; }

    static int readVarInt(InputStream in) throws IOException { return readVarInt(in::read); }

    static int readVarInt(ByteBuffer b) throws IOException {
        return readVarInt(() -> b.hasRemaining() ? b.get() & 0xFF : -1);
    }

    private static int readVarInt(ByteSource src) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int r = src.next();
            if (r < 0) throw new EOFException();
            value |= (r & 0x7F) << (7 * i);
            if ((r & 0x80) == 0) return value;
        }
        throw new IOException("VarInt troppo lungo");
    }

    static void writeString(ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    static String readString(ByteBuffer b) throws IOException {
        int len = readVarInt(b);
        if (len < 0 || len > b.remaining()) throw new IOException("stringa non valida");
        byte[] bytes = new byte[len];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUuid(ByteArrayOutputStream b, UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        b.writeBytes(bb.array());
    }
}
