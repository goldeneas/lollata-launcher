package packlauncher;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import packlauncher.common.Json;

/**
 * Descrizione di un modpack, letta da pack.json sul server.
 *
 * <pre>
 * {
 *   "id": "atm10", "name": "All The Mods 10", "version": "5.1",
 *   "minecraft": "1.21.1",
 *   "loader": { "type": "neoforge", "version": "21.1.265" },
 *   "server": "mc.lollata.xyz:25565",
 *   "authMode": "offline",
 *   "memoryMb": 8192,
 *   "jvmArgs": [],
 *   "files": [ { "path": "mods/x.jar", "sha1": "...", "size": 123, "once": false } ]
 * }
 * </pre>
 * I file stanno sul server in objects/&lt;sha1[0..2]&gt;/&lt;sha1&gt;, relativi a pack.json.
 *
 * index.json elenca tutti i server/modpack. Ogni voce ripete i dati principali del
 * pack, così il launcher può mostrare l'elenco senza scaricare ogni pack.json:
 * <pre>
 * { "formatVersion": 2,
 *   "packs": [ { "id": "atm10a", "name": "ATM10 Aeronautics", "url": "atm10a/pack.json",
 *                "version": "0.7.1", "minecraft": "1.21.1",
 *                "loader": { "type": "neoforge", "version": "21.1.228" },
 *                "server": "mc.lollata.xyz:25565", "authMode": "offline" } ] }
 * </pre>
 * authMode: "premium" (serve un account Microsoft), "offline" (basta un nickname),
 * assente = sconosciuto. I campi oltre a id/name/url sono facoltativi (index vecchi).
 */
public final class Pack {
    private static final java.util.regex.Pattern VALID_ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    public static final String AUTH_PREMIUM = "premium";
    public static final String AUTH_OFFLINE = "offline";

    /** Una voce di index.json: un server con il suo modpack. */
    public record Entry(String id, String name, String url, String version, String minecraft,
                        String loaderType, String loaderVersion, String server, String authMode) {
        /** Testo breve per l'elenco: "Minecraft 1.21.1 - neoforge 21.1.228". */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (minecraft != null) sb.append("Minecraft ").append(minecraft);
            if (loaderType != null && !loaderType.equals("vanilla")) {
                if (sb.length() > 0) sb.append(" - ");
                sb.append(loaderType);
                if (loaderVersion != null) sb.append(' ').append(loaderVersion);
            }
            return sb.toString();
        }
    }

    public record PackFile(String path, String sha1, long size, boolean once) {}

    public String id;
    public String name;
    public String version;
    public String minecraft;
    public String loaderType;
    public String loaderVersion;
    public String server;
    /** "premium", "offline" oppure null se non indicato. */
    public String authMode;
    public int memoryMb;
    public final List<String> jvmArgs = new ArrayList<>();
    public final List<PackFile> files = new ArrayList<>();
    public String baseUrl;

    /** Legge index.json: elenco dei modpack disponibili. */
    public static List<Entry> loadIndex(Downloader dl, String indexUrl) throws IOException {
        return parseIndex(dl.getString(indexUrl), indexUrl);
    }

    /** Analizza index.json; gli url relativi sono risolti rispetto a indexUrl. Voci con id non valido vengono scartate. */
    public static List<Entry> parseIndex(String jsonText, String indexUrl) {
        Map<String, Object> idx = Json.parseObject(jsonText);
        List<Entry> out = new ArrayList<>();
        for (Object o : Json.arr(idx, "packs")) {
            String id = Json.str(o, "id");
            if (id == null || !VALID_ID.matcher(id).matches() || Json.str(o, "url") == null) continue;
            String url = URI.create(indexUrl).resolve(Json.str(o, "url")).toString();
            Map<String, Object> loader = Json.obj(o, "loader");
            out.add(new Entry(id, Json.str(o, "name") != null ? Json.str(o, "name") : id, url,
                    Json.str(o, "version"), Json.str(o, "minecraft"),
                    Json.str(loader, "type"), Json.str(loader, "version"),
                    Json.str(o, "server"), normalizeAuth(Json.str(o, "authMode"))));
        }
        return out;
    }

    public static Pack load(Downloader dl, String packUrl) throws IOException {
        return parse(dl.getString(packUrl), packUrl);
    }

    public static Pack parse(String jsonText, String packUrl) {
        Map<String, Object> j = Json.parseObject(jsonText);
        Pack p = new Pack();
        p.id = Json.str(j, "id");
        p.name = Json.str(j, "name");
        p.version = Json.str(j, "version");
        p.minecraft = Json.str(j, "minecraft");
        p.loaderType = Json.str(Json.obj(j, "loader"), "type");
        p.loaderVersion = Json.str(Json.obj(j, "loader"), "version");
        p.server = Json.str(j, "server");
        p.authMode = normalizeAuth(Json.str(j, "authMode"));
        p.memoryMb = (int) Json.num(j, "memoryMb", 4096);
        for (Object a : Json.arr(j, "jvmArgs")) p.jvmArgs.add(a.toString());
        for (Object f : Json.arr(j, "files")) {
            p.files.add(new PackFile(Json.str(f, "path"), Json.str(f, "sha1"),
                    Json.num(f, "size", -1), Json.bool(f, "once", false)));
        }
        p.baseUrl = URI.create(packUrl).resolve("./").toString();
        if (p.id == null || p.minecraft == null) throw new IllegalArgumentException("pack.json incompleto (id/minecraft)");
        // l'id diventa il nome della cartella dell'istanza: niente "../" o separatori
        if (!VALID_ID.matcher(p.id).matches()) throw new IllegalArgumentException("Id del pack non valido: " + p.id);
        return p;
    }

    /** Accetta solo i valori noti; il resto (anche "auto" o vuoto) diventa null = sconosciuto. */
    public static String normalizeAuth(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals(AUTH_PREMIUM) || s.equals(AUTH_OFFLINE) ? s : null;
    }

    public boolean requiresPremium() {
        return AUTH_PREMIUM.equals(authMode);
    }

    public String objectUrl(String sha1) {
        return baseUrl + "objects/" + sha1.substring(0, 2) + "/" + sha1;
    }
}
