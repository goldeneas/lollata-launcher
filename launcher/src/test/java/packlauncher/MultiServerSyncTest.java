package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import packlauncher.common.Hashing;

/**
 * Due server/modpack serviti in HTTP: il launcher legge l'indice, installa ciascun pack nella
 * sua istanza e lo aggiorna in autonomia quando il manifest cambia.
 */
class MultiServerSyncTest {
    @TempDir Path tmp;
    private HttpServer http;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private String base;

    private static final Progress QUIET = new Progress() {
        @Override public void stage(String text) {}
        @Override public void progress(long done, long total) {}
        @Override public void log(String line) {}
    };

    @BeforeEach
    void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            byte[] body = files.get(ex.getRequestURI().getPath());
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() { http.stop(0); }

    /** Pubblica un file come oggetto e restituisce la voce per pack.json. */
    private String object(String packId, String path, String content, boolean once) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        Path t = Files.write(tmp.resolve("hash.tmp"), data);
        String sha1 = Hashing.sha1(t);
        files.put("/packs/" + packId + "/objects/" + sha1.substring(0, 2) + "/" + sha1, data);
        return "{\"path\":\"" + path + "\",\"sha1\":\"" + sha1 + "\",\"size\":" + data.length
                + (once ? ",\"once\":true" : "") + "}";
    }

    private void publish(String id, String mc, String auth, String version, String... entries) {
        String json = "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"" + version + "\",\"minecraft\":\"" + mc
                + "\",\"loader\":{\"type\":\"vanilla\"},\"authMode\":\"" + auth + "\",\"files\":["
                + String.join(",", entries) + "]}";
        files.put("/packs/" + id + "/pack.json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void installaEAggiornaOgniServerNellaSuaIstanza() throws Exception {
        files.put("/packs/index.json", """
                {"formatVersion":2,"packs":[
                 {"id":"alpha","name":"Alpha","url":"alpha/pack.json","minecraft":"1.21.1","authMode":"premium"},
                 {"id":"beta","name":"Beta","url":"beta/pack.json","minecraft":"1.20.1","authMode":"offline"}]}
                """.getBytes(StandardCharsets.UTF_8));
        publish("alpha", "1.21.1", "premium", "1",
                object("alpha", "mods/a.jar", "mod-a-v1", false),
                object("alpha", "mods/vecchia.jar", "da-togliere", false),
                object("alpha", "options.txt", "fov:70", true));
        publish("beta", "1.20.1", "offline", "1", object("beta", "mods/b.jar", "mod-b", false));

        Downloader dl = new Downloader("test");
        List<Pack.Entry> servers = Pack.loadIndex(dl, base + "/packs/index.json");
        assertEquals(List.of("alpha", "beta"), servers.stream().map(Pack.Entry::id).toList());

        Path instances = tmp.resolve("instances");
        for (Pack.Entry e : servers) {
            Pack p = Pack.load(dl, e.url());
            assertEquals(e.authMode(), p.authMode);
            new PackSync(instances.resolve(p.id), dl, QUIET).sync(p);
        }
        Path alpha = instances.resolve("alpha"), beta = instances.resolve("beta");
        assertEquals("mod-a-v1", Files.readString(alpha.resolve("mods/a.jar")));
        assertEquals("mod-b", Files.readString(beta.resolve("mods/b.jar")));
        assertFalse(Files.exists(alpha.resolve("mods/b.jar")), "i pack non si mescolano");
        assertFalse(Files.exists(beta.resolve("mods/a.jar")));

        // L'utente aggiunge una mod a mano e cambia le opzioni
        Files.writeString(alpha.resolve("mods/mia.jar"), "mia");
        Files.writeString(alpha.resolve("options.txt"), "fov:90");

        // Nuova versione di alpha: mod aggiornata, una tolta
        publish("alpha", "1.21.1", "premium", "2",
                object("alpha", "mods/a.jar", "mod-a-v2", false),
                object("alpha", "options.txt", "fov:70", true));
        new PackSync(alpha, dl, QUIET).sync(Pack.load(dl, base + "/packs/alpha/pack.json"));

        assertEquals("mod-a-v2", Files.readString(alpha.resolve("mods/a.jar")));
        assertFalse(Files.exists(alpha.resolve("mods/vecchia.jar")), "mod tolta dal pack viene rimossa");
        assertEquals("mia", Files.readString(alpha.resolve("mods/mia.jar")), "mod dell'utente non toccata");
        assertEquals("fov:90", Files.readString(alpha.resolve("options.txt")), "file once non sovrascritto");
        assertEquals("mod-b", Files.readString(beta.resolve("mods/b.jar")), "beta non cambia");
    }
}
