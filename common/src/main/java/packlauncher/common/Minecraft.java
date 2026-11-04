package packlauncher.common;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Convenzioni condivise da launcher e pack-builder: id dei pack, tipo di accesso, indirizzi e UUID offline. */
public final class Minecraft {
    private Minecraft() {}

    /** authMode: serve un account Microsoft (online-mode=true). */
    public static final String PREMIUM = "premium";
    /** authMode: basta un nickname (online-mode=false). */
    public static final String OFFLINE = "offline";

    public static final int DEFAULT_PORT = 25565;

    /** Id di un pack: diventa il nome di una cartella, quindi niente "../" o separatori. */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    public record Address(String host, int port) {}

    /** "host" o "host:porta" (porta 25565 di default). Un IPv6 senza porta resta intero. */
    public static Address parseAddress(String server) {
        String host = server.trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            return new Address(host.substring(0, colon), Integer.parseInt(host.substring(colon + 1)));
        }
        return new Address(host, DEFAULT_PORT);
    }

    /** UUID offline, identico a quello che calcola il server in online-mode=false. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
