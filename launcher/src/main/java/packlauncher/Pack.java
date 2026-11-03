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
 *   "server": "mcfiles.lollata.xyz:25565",
 *   "memoryMb": 8192,
 *   "jvmArgs": [],
 *   "files": [ { "path": "mods/x.jar", "sha1": "...", "size": 123, "once": false } ]
 * }
 * </pre>
 * I file stanno sul server in objects/&lt;sha1[0..2]&gt;/&lt;sha1&gt;, relativi a pack.json.
 */
public final class Pack {
    public record Entry(String id, String name, String url) {}

    public record PackFile(String path, String sha1, long size, boolean once) {}

    public String id;
    public String name;
    public String version;
    public String minecraft;
    public String loaderType;
    public String loaderVersion;
    public String server;
    public int memoryMb;
    public final List<String> jvmArgs = new ArrayList<>();
    public final List<PackFile> files = new ArrayList<>();
    public String baseUrl;

    /** Legge index.json: elenco dei modpack disponibili. */
    public static List<Entry> loadIndex(Downloader dl, String indexUrl) throws IOException {
        Map<String, Object> idx = Json.parseObject(dl.getString(indexUrl));
        List<Entry> out = new ArrayList<>();
        for (Object o : Json.arr(idx, "packs")) {
            String url = URI.create(indexUrl).resolve(Json.str(o, "url")).toString();
            out.add(new Entry(Json.str(o, "id"), Json.str(o, "name"), url));
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
        p.memoryMb = (int) Json.num(j, "memoryMb", 4096);
        for (Object a : Json.arr(j, "jvmArgs")) p.jvmArgs.add(a.toString());
        for (Object f : Json.arr(j, "files")) {
            p.files.add(new PackFile(Json.str(f, "path"), Json.str(f, "sha1"),
                    Json.num(f, "size", -1), Json.bool(f, "once", false)));
        }
        p.baseUrl = URI.create(packUrl).resolve("./").toString();
        if (p.id == null || p.minecraft == null) throw new IllegalArgumentException("pack.json incompleto (id/minecraft)");
        return p;
    }

    public String objectUrl(String sha1) {
        return baseUrl + "objects/" + sha1.substring(0, 2) + "/" + sha1;
    }
}
