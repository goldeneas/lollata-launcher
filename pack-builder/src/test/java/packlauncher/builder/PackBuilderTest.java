package packlauncher.builder;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import packlauncher.common.Json;

class PackBuilderTest {
    @TempDir Path tmp;

    /** Crea un'istanza Prism minima. */
    private Path instance(Path dir, String mc, String loaderUid, String loaderVer, String props) throws Exception {
        Files.createDirectories(dir.resolve(".minecraft/mods"));
        Files.createDirectories(dir.resolve(".minecraft/logs"));
        String loader = loaderUid == null ? ""
                : ",{\"uid\":\"" + loaderUid + "\",\"version\":\"" + loaderVer + "\"}";
        Files.writeString(dir.resolve("mmc-pack.json"),
                "{\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"" + mc + "\"}" + loader + "]}");
        Files.writeString(dir.resolve("instance.cfg"), "[General]\nname=" + dir.getFileName() + " Pack\n");
        Files.writeString(dir.resolve(".minecraft/mods/a.jar"), "mod-" + dir.getFileName());
        Files.writeString(dir.resolve(".minecraft/options.txt"), "fov:70");
        Files.writeString(dir.resolve(".minecraft/logs/latest.log"), "log");
        if (props != null) Files.writeString(dir.resolve("pack.properties"), props);
        return dir;
    }

    private static Map<String, Object> read(Path p) throws Exception {
        return Json.parseObject(Files.readString(p));
    }

    @Test
    void manifestConVersioneLoaderServerEAuthMode() throws Exception {
        Path inst = instance(tmp.resolve("ATM 10"), "1.21.1", "net.neoforged", "21.1.228",
                "server=mc.example:25565\nauthMode=premium\nversion=1.0\n");
        Path www = tmp.resolve("www");
        new PackBuilder().build(inst, www.resolve("atm"), www.resolve("index.json"), false);

        Map<String, Object> pack = read(www.resolve("atm/pack.json"));
        assertEquals("atm-10", Json.str(pack, "id"), "id ricavato dal nome della cartella");
        assertEquals("1.21.1", Json.str(pack, "minecraft"));
        assertEquals("neoforge", Json.str(Json.obj(pack, "loader"), "type"));
        assertEquals("21.1.228", Json.str(Json.obj(pack, "loader"), "version"));
        assertEquals("mc.example:25565", Json.str(pack, "server"));
        assertEquals("premium", Json.str(pack, "authMode"));

        List<String> paths = Json.arr(pack, "files").stream().map(f -> Json.str(f, "path")).toList();
        assertEquals(List.of("mods/a.jar", "options.txt"), paths, "i log sono esclusi");
        Object options = Json.arr(pack, "files").get(1);
        assertTrue(Json.bool(options, "once", false));
        String sha1 = Json.str(Json.arr(pack, "files").get(0), "sha1");
        assertTrue(Files.exists(www.resolve("atm/objects/" + sha1.substring(0, 2) + "/" + sha1)));

        Map<String, Object> idx = read(www.resolve("index.json"));
        assertEquals(2, Json.num(idx, "formatVersion", 0));
        Object e = Json.arr(idx, "packs").get(0);
        assertEquals("atm/pack.json", Json.str(e, "url"));
        assertEquals("1.21.1", Json.str(e, "minecraft"));
        assertEquals("neoforge", Json.str(Json.obj(e, "loader"), "type"));
        assertEquals("mc.example:25565", Json.str(e, "server"));
        assertEquals("premium", Json.str(e, "authMode"));
        assertEquals("1.0", Json.str(e, "version"));
        assertNull(Json.obj(e, "files"), "l'indice non contiene l'elenco dei file");
    }

    @Test
    void ripubblicareAggiornaLaVoceEMantieneLeAltre() throws Exception {
        Path www = tmp.resolve("www");
        Path index = www.resolve("index.json");
        Path a = instance(tmp.resolve("a"), "1.21.1", null, null, "version=1\n");
        Path b = instance(tmp.resolve("b"), "1.20.1", "net.fabricmc.fabric-loader", "0.16.0", "authMode=offline\n");
        PackBuilder pb = new PackBuilder();
        pb.build(a, www.resolve("a"), index, false);
        pb.build(b, www.resolve("b"), index, false);
        Files.writeString(a.resolve("pack.properties"), "version=2\n");
        pb.build(a, www.resolve("a"), index, false);

        List<Object> packs = Json.arr(read(index), "packs");
        assertEquals(List.of("a", "b"), packs.stream().map(p -> Json.str(p, "id")).toList(), "ordine mantenuto");
        assertEquals("2", Json.str(packs.get(0), "version"));
        assertEquals("fabric", Json.str(Json.obj(packs.get(1), "loader"), "type"));
        assertEquals("offline", Json.str(packs.get(1), "authMode"));
        assertNull(Json.str(packs.get(0), "authMode"), "senza server l'accesso resta sconosciuto");
    }

    @Test
    void buildAllPubblicaUnaCartellaPerServerERiscriveLIndice() throws Exception {
        Path root = tmp.resolve("istanze");
        instance(root.resolve("atm10"), "1.21.1", "net.neoforged", "21.1.228", "authMode=premium\n");
        instance(root.resolve("fabric"), "1.20.1", "net.fabricmc.fabric-loader", "0.16.0", "id=svago\nauthMode=offline\n");
        Files.createDirectories(root.resolve("non-un-istanza"));
        Path www = tmp.resolve("www");

        assertEquals(List.of("atm10", "svago"), new PackBuilder().buildAll(root, www, false));
        assertTrue(Files.exists(www.resolve("atm10/pack.json")));
        assertTrue(Files.exists(www.resolve("svago/pack.json")));
        assertEquals("mod-fabric", Files.readString(www.resolve("svago/objects").toFile().toPath()
                .resolve(sha1Dir(read(www.resolve("svago/pack.json"))))));

        // Tolgo un'istanza: sparisce dall'indice
        deleteTree(root.resolve("atm10"));
        new PackBuilder().buildAll(root, www, false);
        List<Object> packs = Json.arr(read(www.resolve("index.json")), "packs");
        assertEquals(List.of("svago"), packs.stream().map(p -> Json.str(p, "id")).toList());
        assertEquals("svago/pack.json", Json.str(packs.get(0), "url"));
    }

    @Test
    void buildAllRifiutaIdDuplicati() throws Exception {
        Path root = tmp.resolve("istanze");
        instance(root.resolve("uno"), "1.21.1", null, null, "id=stesso\n");
        instance(root.resolve("due"), "1.21.1", null, null, "id=stesso\n");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new PackBuilder().buildAll(root, tmp.resolve("www"), false));
        assertTrue(ex.getMessage().contains("stesso"));
        assertFalse(Files.exists(tmp.resolve("www/index.json")), "nessun indice scritto a metà");
    }

    @Test
    void idEAuthModeNonValidi() throws Exception {
        Path inst = instance(tmp.resolve("x"), "1.21.1", null, null, "id=../fuori\n");
        assertThrows(IllegalArgumentException.class, () -> new PackBuilder().build(inst, tmp.resolve("o"), null, false));

        assertThrows(IllegalArgumentException.class, () -> PackBuilder.resolveAuthMode("boh", "h:1"));
        assertEquals("premium", PackBuilder.resolveAuthMode(" Premium ", null));
        assertEquals("offline", PackBuilder.resolveAuthMode("offline", "h:1"));
        assertNull(PackBuilder.resolveAuthMode(null, null), "auto senza server = sconosciuto");
    }

    private static Path sha1Dir(Map<String, Object> pack) {
        String sha1 = Json.str(Json.arr(pack, "files").get(0), "sha1");
        return Path.of(sha1.substring(0, 2), sha1);
    }

    private static void deleteTree(Path p) throws Exception {
        try (var s = Files.walk(p)) {
            for (Path f : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
