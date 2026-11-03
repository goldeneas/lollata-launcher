package packlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import packlauncher.common.Json;

/**
 * Installa Minecraft vanilla (client, librerie, asset) e NeoForge
 * nella cartella condivisa del launcher, e produce un {@link Profile}
 * pronto per l'avvio.
 *
 * Layout (identico a .minecraft, così l'installer di NeoForge lo riconosce):
 *   versions/<id>/<id>.json|.jar, libraries/..., assets/indexes|objects
 */
public final class GameInstaller {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSETS_URL = "https://resources.download.minecraft.net/";
    private static final String LIBRARIES_URL = "https://libraries.minecraft.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private final Path root;
    private final Downloader dl;
    private final Progress progress;

    public GameInstaller(Path root, Downloader dl, Progress progress) {
        this.root = root;
        this.dl = dl;
        this.progress = progress;
    }

    public Path librariesDir() { return root.resolve("libraries"); }
    public Path assetsDir() { return root.resolve("assets"); }
    public Path versionDir(String id) { return root.resolve("versions").resolve(id); }

    /** Profilo risolto (versione figlia unita alla versione vanilla da cui eredita). */
    public static final class Profile {
        public String id;
        public String vanillaId;
        public String mainClass;
        public String type;
        public int javaMajor;
        public Map<String, Object> assetIndex;
        public Map<String, Object> logging;
        public final List<Map<String, Object>> libraries = new ArrayList<>();
        public final List<Object> jvmArgs = new ArrayList<>();
        public final List<Object> gameArgs = new ArrayList<>();
        public Path clientJar;
    }

    // ------------------------------------------------------------------ vanilla

    public Map<String, Object> installVanilla(String mcVersion) throws IOException {
        Path jsonFile = versionDir(mcVersion).resolve(mcVersion + ".json");
        Map<String, Object> json;
        if (Files.exists(jsonFile)) {
            json = Json.parseObject(Files.readString(jsonFile));
        } else {
            progress.stage("Scarico informazioni Minecraft " + mcVersion);
            Map<String, Object> manifest = Json.parseObject(dl.getString(VERSION_MANIFEST));
            Object entry = Json.arr(manifest, "versions").stream()
                    .filter(v -> mcVersion.equals(Json.str(v, "id"))).findFirst()
                    .orElseThrow(() -> new IOException("Versione Minecraft sconosciuta: " + mcVersion));
            dl.downloadOne(new Downloader.Task(Json.str(entry, "url"), jsonFile, Json.str(entry, "sha1"), -1), null);
            json = Json.parseObject(Files.readString(jsonFile));
        }

        progress.stage("Scarico Minecraft " + mcVersion);
        Map<String, Object> client = Json.obj(Json.obj(json, "downloads"), "client");
        dl.downloadAll(List.of(new Downloader.Task(Json.str(client, "url"),
                versionDir(mcVersion).resolve(mcVersion + ".jar"), Json.str(client, "sha1"), Json.num(client, "size", -1))), progress);
        return json;
    }

    // ------------------------------------------------------------------ neoforge

    /** Installa NeoForge (se serve) e restituisce l'id della versione installata. */
    public String installNeoForge(String neoVersion, String mcVersion) throws IOException {
        String id = "neoforge-" + neoVersion;
        Path jsonFile = versionDir(id).resolve(id + ".json");
        Path marker = versionDir(id).resolve(".installed");
        if (Files.exists(jsonFile) && Files.exists(marker)) return id;

        progress.stage("Installo NeoForge " + neoVersion + " (può richiedere qualche minuto)");
        // L'installer pretende un launcher_profiles.json nella cartella di destinazione.
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Files.writeString(profiles, "{\"profiles\":{}}");

        Path installer = root.resolve("cache").resolve(id + "-installer.jar");
        dl.downloadOne(new Downloader.Task(NEOFORGE_MAVEN + neoVersion + "/" + id + "-installer.jar", installer, null, -1), null);

        Path log = root.resolve("cache").resolve(id + "-installer.log");
        ProcessBuilder pb = new ProcessBuilder(javaExecutable(), "-jar", installer.toString(),
                "--install-client", root.toString())
                .directory(root.resolve("cache").toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Process p = pb.start();
        try {
            // l'installer non dà un avanzamento affidabile: barra indeterminata
            progress.progress(0, 0);
            if (!p.waitFor(20, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Installer NeoForge bloccato. Log: " + log);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        }
        if (p.exitValue() != 0 || !Files.exists(jsonFile)) {
            throw new IOException("Installazione NeoForge fallita (codice " + p.exitValue() + "). Log: " + log);
        }
        Files.writeString(marker, mcVersion);
        return id;
    }

    // ------------------------------------------------------------------ profilo

    /** Carica la versione (eventualmente figlia via inheritsFrom) e la unisce al genitore. */
    public Profile resolve(String versionId) throws IOException {
        Map<String, Object> child = Json.parseObject(Files.readString(versionDir(versionId).resolve(versionId + ".json"), StandardCharsets.UTF_8));
        String parentId = Json.str(child, "inheritsFrom");
        Map<String, Object> parent = parentId != null ? installVanilla(parentId) : null;
        Map<String, Object> base = parent != null ? parent : child;

        Profile p = new Profile();
        p.id = versionId;
        p.vanillaId = parentId != null ? parentId : versionId;
        p.mainClass = Json.str(child, "mainClass");
        p.type = Json.str(child, "type") != null ? Json.str(child, "type") : Json.str(base, "type");
        p.assetIndex = Json.obj(base, "assetIndex");
        p.logging = Json.obj(Json.obj(base, "logging"), "client");
        Map<String, Object> jv = Json.obj(base, "javaVersion");
        p.javaMajor = (int) Json.num(jv, "majorVersion", 21);

        // Librerie: prima quelle della versione figlia, poi quelle vanilla non duplicate.
        Map<String, Map<String, Object>> libs = new LinkedHashMap<>();
        addLibraries(libs, child);
        if (parent != null) addLibraries(libs, parent);
        p.libraries.addAll(libs.values());

        if (parent != null) {
            p.jvmArgs.addAll(Json.arr(Json.obj(parent, "arguments"), "jvm"));
            p.gameArgs.addAll(Json.arr(Json.obj(parent, "arguments"), "game"));
        }
        p.jvmArgs.addAll(Json.arr(Json.obj(child, "arguments"), "jvm"));
        p.gameArgs.addAll(Json.arr(Json.obj(child, "arguments"), "game"));

        // Come il launcher ufficiale: il jar della versione figlia è una copia di quello vanilla.
        Path vanillaJar = versionDir(p.vanillaId).resolve(p.vanillaId + ".jar");
        p.clientJar = versionDir(versionId).resolve(versionId + ".jar");
        if (!p.clientJar.equals(vanillaJar)
                && (!Files.exists(p.clientJar) || Files.size(p.clientJar) != Files.size(vanillaJar))) {
            Files.copy(vanillaJar, p.clientJar, StandardCopyOption.REPLACE_EXISTING);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private void addLibraries(Map<String, Map<String, Object>> out, Map<String, Object> version) {
        for (Object o : Json.arr(version, "libraries")) {
            Map<String, Object> lib = (Map<String, Object>) o;
            if (!OS.allowed(Json.arr(lib, "rules"), Map.of())) continue;
            String[] parts = Json.str(lib, "name").split(":");
            // chiave senza versione: group:artifact[:classifier]
            String key = parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
            out.putIfAbsent(key, lib);
        }
    }

    /** Percorso locale di una libreria. */
    public Path libraryPath(Map<String, Object> lib) {
        Map<String, Object> art = Json.obj(Json.obj(lib, "downloads"), "artifact");
        String path = art != null ? Json.str(art, "path") : null;
        if (path == null) path = mavenPath(Json.str(lib, "name"));
        return librariesDir().resolve(path);
    }

    static String mavenPath(String name) {
        String[] p = name.split(":");
        String ext = "jar";
        String last = p[p.length - 1];
        if (last.contains("@")) {
            ext = last.substring(last.indexOf('@') + 1);
            p[p.length - 1] = last.substring(0, last.indexOf('@'));
        }
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + "." + ext;
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    // ------------------------------------------------------------------ download

    public void downloadLibraries(Profile p) throws IOException {
        progress.stage("Controllo librerie");
        List<Downloader.Task> tasks = new ArrayList<>();
        for (Map<String, Object> lib : p.libraries) {
            Path dest = libraryPath(lib);
            Map<String, Object> art = Json.obj(Json.obj(lib, "downloads"), "artifact");
            String url = art != null ? Json.str(art, "url") : null;
            if (art == null && Json.obj(lib, "downloads") == null) {
                String base = Json.str(lib, "url") != null ? Json.str(lib, "url") : LIBRARIES_URL;
                url = base + mavenPath(Json.str(lib, "name"));
            }
            if (url == null || url.isEmpty()) {
                // generata dall'installer (es. client patchato di NeoForge)
                if (!Files.exists(dest)) throw new IOException("Libreria mancante: " + Json.str(lib, "name"));
                continue;
            }
            tasks.add(new Downloader.Task(url, dest, art != null ? Json.str(art, "sha1") : null,
                    art != null ? Json.num(art, "size", -1) : -1));
        }
        dl.downloadAll(tasks, progress);
    }

    public void downloadAssets(Profile p) throws IOException {
        progress.stage("Controllo risorse di gioco");
        String indexId = Json.str(p.assetIndex, "id");
        Path indexFile = assetsDir().resolve("indexes").resolve(indexId + ".json");
        dl.downloadAll(List.of(new Downloader.Task(Json.str(p.assetIndex, "url"), indexFile,
                Json.str(p.assetIndex, "sha1"), Json.num(p.assetIndex, "size", -1))), progress);

        Map<String, Object> objects = Json.obj(Json.parseObject(Files.readString(indexFile)), "objects");
        List<Downloader.Task> tasks = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : objects.values()) {
            String hash = Json.str(o, "hash");
            if (!seen.add(hash)) continue;
            String sub = hash.substring(0, 2) + "/" + hash;
            Path dest = assetsDir().resolve("objects").resolve(sub);
            // per gli asset basta controllare la dimensione (sono migliaia: niente hash a ogni avvio)
            long size = Json.num(o, "size", -1);
            if (Files.exists(dest) && Files.size(dest) == size) continue;
            tasks.add(new Downloader.Task(ASSETS_URL + sub, dest, hash, size));
        }
        dl.downloadAll(tasks, progress);

        if (p.logging != null) {
            Map<String, Object> f = Json.obj(p.logging, "file");
            dl.downloadAll(List.of(new Downloader.Task(Json.str(f, "url"),
                    assetsDir().resolve("log_configs").resolve(Json.str(f, "id")),
                    Json.str(f, "sha1"), Json.num(f, "size", -1))), progress);
        }
    }

    /** Java usato sia per l'installer che per il gioco: quello che sta eseguendo il launcher. */
    public static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin");
        if (OS.NAME.equals("windows")) {
            Path javaw = bin.resolve("javaw.exe");
            return (Files.exists(javaw) ? javaw : bin.resolve("java.exe")).toString();
        }
        return bin.resolve("java").toString();
    }
}
