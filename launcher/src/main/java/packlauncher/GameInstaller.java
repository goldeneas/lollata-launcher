package packlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import packlauncher.common.Json;

/**
 * Installa Minecraft vanilla (client, librerie, asset) e il mod loader
 * nella cartella condivisa del launcher, e produce un {@link Profile} pronto per l'avvio.
 *
 * Loader supportati: vanilla, neoforge, forge, fabric, quilt.
 * Versioni: tutte quelle con JSON Mojang moderno (1.6+), incluse quelle con
 * "minecraftArguments" e native da estrarre (fino a 1.12.2).
 *
 * Layout (identico a .minecraft, così gli installer di Forge/NeoForge lo riconoscono):
 *   versions/&lt;id&gt;/&lt;id&gt;.json|.jar, libraries/..., assets/indexes|objects|virtual, runtime/...
 */
public final class GameInstaller {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSETS_URL = "https://resources.download.minecraft.net/";
    private static final String LIBRARIES_URL = "https://libraries.minecraft.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/";

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
    private Path versionJson(String id) { return versionDir(id).resolve(id + ".json"); }

    /** Profilo risolto (versione figlia unita alla versione vanilla da cui eredita). */
    public static final class Profile {
        public String id;
        public String vanillaId;
        public String mainClass;
        public String type;
        public JavaRuntime.Version java;
        public Map<String, Object> assetIndex;
        public Map<String, Object> logging;
        public final List<Map<String, Object>> libraries = new ArrayList<>();
        /** Formato moderno (1.13+): liste con regole. */
        public final List<Object> jvmArgs = new ArrayList<>();
        public final List<Object> gameArgs = new ArrayList<>();
        /** Formato vecchio (fino a 1.12): stringa unica. Se non null, ha la precedenza. */
        public String legacyGameArgs;
        public Path clientJar;
        /** Cartella per ${game_assets} (asset "virtuali" delle versioni molto vecchie). */
        public Path gameAssets;
    }

    // ------------------------------------------------------------------ vanilla

    public Map<String, Object> installVanilla(String mcVersion) throws IOException {
        Path jsonFile = versionJson(mcVersion);
        Map<String, Object> json;
        if (Files.exists(jsonFile)) {
            json = Json.parseObject(Files.readString(jsonFile));
        } else {
            progress.stage("Scarico informazioni Minecraft " + mcVersion);
            Map<String, Object> manifest = Json.parseObject(dl.getString(VERSION_MANIFEST));
            Object entry = Json.arr(manifest, "versions").stream()
                    .filter(v -> mcVersion.equals(Json.str(v, "id"))).findFirst()
                    .orElseThrow(() -> new IOException("Versione Minecraft sconosciuta: " + mcVersion));
            dl.downloadOne(Downloader.Task.of(entry, jsonFile), null);
            json = Json.parseObject(Files.readString(jsonFile));
        }

        Downloader.Task client = Downloader.Task.of(Json.obj(Json.obj(json, "downloads"), "client"),
                versionDir(mcVersion).resolve(mcVersion + ".jar"));
        if (!Files.exists(client.dest()) || Files.size(client.dest()) != client.size()) {
            progress.stage("Scarico Minecraft " + mcVersion);
            dl.downloadAll(List.of(client), progress);
        }
        return json;
    }

    /** Java richiesto da una versione (campo javaVersion; senza, Java 8). */
    public static JavaRuntime.Version javaFor(Map<String, Object> versionJson) {
        Map<String, Object> jv = Json.obj(versionJson, "javaVersion");
        return new JavaRuntime.Version(Json.str(jv, "component"), (int) Json.num(jv, "majorVersion", 8));
    }

    // ------------------------------------------------------------------ loader

    /**
     * Installa il loader (se serve) e restituisce l'id della versione da avviare.
     * javaExe serve per gli installer di Forge/NeoForge.
     */
    public String installLoader(String type, String loaderVersion, String mcVersion, String javaExe) throws IOException {
        if (type == null || type.equals("vanilla")) return mcVersion;
        if (loaderVersion == null || loaderVersion.isBlank()) throw new IOException("Versione del loader mancante nel pack");
        switch (type) {
            case "neoforge": {
                // 1.20.1 usava l'artefatto "forge" con versione 1.20.1-47.1.x
                boolean legacy = mcVersion.equals("1.20.1");
                String artifact = legacy ? "forge" : "neoforge";
                String full = legacy && !loaderVersion.startsWith("1.20.1-") ? "1.20.1-" + loaderVersion : loaderVersion;
                String url = NEOFORGE_MAVEN + artifact + "/" + full + "/" + artifact + "-" + full + "-installer.jar";
                return runInstaller("neoforge-" + full, url, "--installClient", javaExe);
            }
            case "forge": {
                String full = loaderVersion.startsWith(mcVersion + "-") ? loaderVersion : mcVersion + "-" + loaderVersion;
                String url = FORGE_MAVEN + full + "/forge-" + full + "-installer.jar";
                return runInstaller("forge-" + full, url, "--installClient", javaExe);
            }
            case "fabric":
                return installProfileJson(FABRIC_META + mcVersion + "/" + loaderVersion + "/profile/json");
            case "quilt":
                return installProfileJson(QUILT_META + mcVersion + "/" + loaderVersion + "/profile/json");
            default:
                throw new IOException("Loader non supportato: " + type);
        }
    }

    /** Fabric/Quilt: basta salvare il JSON del profilo (eredita dalla versione vanilla). */
    private String installProfileJson(String url) throws IOException {
        String text = dl.getString(url);
        String id = Json.str(Json.parseObject(text), "id");
        Path file = versionJson(id);
        if (!Files.exists(file)) {
            progress.stage("Installo " + id);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }
        return id;
    }

    /** Forge/NeoForge: scarica l'installer ufficiale e lo esegue senza interfaccia. */
    private String runInstaller(String name, String url, String flag, String javaExe) throws IOException {
        Path cache = root.resolve("cache");
        Path markerFile = cache.resolve(name + ".installed");
        if (Files.exists(markerFile)) {
            String id = Files.readString(markerFile).trim();
            if (Files.exists(versionJson(id))) return id;
        }

        progress.stage("Installo " + name + " (può richiedere qualche minuto)");
        progress.progress(0, 0);
        Path profiles = root.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Files.writeString(profiles, "{\"profiles\":{}}");

        Path installer = cache.resolve(name + "-installer.jar");
        dl.downloadOne(new Downloader.Task(url, installer, null, -1), null);
        String id = installerVersionId(installer);

        Path log = cache.resolve(name + "-installer.log");
        Process p = new ProcessBuilder(javaExe, "-jar", installer.toString(), flag, root.toString())
                .directory(cache.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("Installer bloccato. Log: " + log);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw Downloader.interrupted(e);
        }
        if (p.exitValue() != 0 || !Files.exists(versionJson(id))) {
            throw new IOException("Installazione di " + name + " fallita (codice " + p.exitValue() + "). Log: " + log);
        }
        Files.writeString(markerFile, id);
        return id;
    }

    /** Legge dall'installer l'id della versione che creerà (es. "1.20.1-forge-47.3.0"). */
    private static String installerVersionId(Path installer) throws IOException {
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            ZipEntry e = zip.getEntry("install_profile.json");
            if (e == null) throw new IOException("Installer non valido: " + installer);
            try (InputStream in = zip.getInputStream(e)) {
                Map<String, Object> prof = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                String id = Json.str(prof, "version");
                if (id == null) id = Json.str(Json.obj(prof, "versionInfo"), "id"); // formato vecchio
                if (id == null) throw new IOException("Versione non trovata nell'installer");
                return id;
            }
        }
    }

    // ------------------------------------------------------------------ profilo

    /** Carica la versione (eventualmente figlia via inheritsFrom) e la unisce al genitore. */
    public Profile resolve(String versionId) throws IOException {
        Map<String, Object> child = Json.parseObject(Files.readString(versionJson(versionId), StandardCharsets.UTF_8));
        String parentId = Json.str(child, "inheritsFrom");
        Map<String, Object> parent = parentId != null ? installVanilla(parentId) : null;
        Map<String, Object> base = parent != null ? parent : child;

        Profile p = new Profile();
        p.id = versionId;
        p.vanillaId = parentId != null ? parentId : versionId;
        p.mainClass = inherited(child, base, "mainClass");
        p.type = inherited(child, base, "type");
        p.assetIndex = Json.obj(base, "assetIndex");
        p.logging = Json.obj(Json.obj(base, "logging"), "client");
        p.java = javaFor(Json.obj(child, "javaVersion") != null ? child : base);

        // Librerie: prima quelle della versione figlia, poi quelle vanilla non duplicate.
        Map<String, Map<String, Object>> libs = new LinkedHashMap<>();
        addLibraries(libs, child);
        if (parent != null) addLibraries(libs, parent);
        p.libraries.addAll(libs.values());

        // Argomenti: formato vecchio (stringa) o moderno (liste).
        String childLegacy = Json.str(child, "minecraftArguments");
        String parentLegacy = parent != null ? Json.str(parent, "minecraftArguments") : null;
        if (childLegacy != null || (parentLegacy != null && Json.obj(child, "arguments") == null)) {
            p.legacyGameArgs = childLegacy != null ? childLegacy : parentLegacy;
        }
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

    private static String inherited(Map<String, Object> child, Map<String, Object> base, String key) {
        String v = Json.str(child, key);
        return v != null ? v : Json.str(base, key);
    }

    @SuppressWarnings("unchecked")
    private void addLibraries(Map<String, Map<String, Object>> out, Map<String, Object> version) {
        for (Object o : Json.arr(version, "libraries")) {
            Map<String, Object> lib = (Map<String, Object>) o;
            if (!OS.allowed(Json.arr(lib, "rules"), Map.of())) continue;
            if (Boolean.FALSE.equals(lib.get("clientreq"))) continue;
            String[] parts = Json.str(lib, "name").split(":");
            // chiave senza versione: group:artifact[:classifier]
            // (le versioni vecchie elencano la stessa libreria due volte: jar e native)
            String key = parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "")
                    + (lib.get("natives") != null ? "#natives" : "");
            out.putIfAbsent(key, lib);
        }
    }

    /** Percorso del jar da mettere nel classpath, o null se la libreria contiene solo native. */
    public Path libraryPath(Map<String, Object> lib) {
        Map<String, Object> downloads = Json.obj(lib, "downloads");
        Map<String, Object> art = Json.obj(downloads, "artifact");
        if (art != null && Json.str(art, "path") != null) return librariesDir().resolve(Json.str(art, "path"));
        if (downloads != null && art == null) return null;          // solo native (es. lwjgl-platform)
        if (downloads == null && Json.obj(lib, "natives") != null) return null;
        return librariesDir().resolve(mavenPath(Json.str(lib, "name")));
    }

    /** Classificatore delle native per questo OS (formato vecchio), o null. */
    private static String nativeClassifier(Map<String, Object> lib) {
        String c = Json.str(Json.obj(lib, "natives"), OS.NAME);
        return c == null ? null : c.replace("${arch}", OS.ARCH.equals("x86") ? "32" : "64");
    }

    private Map<String, Object> nativeArtifact(Map<String, Object> lib) {
        String cls = nativeClassifier(lib);
        if (cls == null) return null;
        return Json.obj(Json.obj(Json.obj(lib, "downloads"), "classifiers"), cls);
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
            if (dest != null) {
                Map<String, Object> art = Json.obj(Json.obj(lib, "downloads"), "artifact");
                String url;
                String sha1 = art != null ? Json.str(art, "sha1") : Json.str(lib, "sha1");
                long size = art != null ? Json.num(art, "size", -1) : Json.num(lib, "size", -1);
                if (art != null) {
                    url = Json.str(art, "url");
                } else {
                    String base = Json.str(lib, "url") != null ? Json.str(lib, "url") : LIBRARIES_URL;
                    if (!base.endsWith("/")) base += "/";
                    url = base + mavenPath(Json.str(lib, "name"));
                }
                if (url == null || url.isEmpty()) {
                    // generata dall'installer (es. client patchato di Forge/NeoForge)
                    if (!Files.exists(dest)) throw new IOException("Libreria mancante: " + Json.str(lib, "name"));
                } else {
                    tasks.add(new Downloader.Task(url, dest, sha1, size));
                }
            }
            Map<String, Object> nat = nativeArtifact(lib);
            if (nat != null) tasks.add(Downloader.Task.of(nat, librariesDir().resolve(Json.str(nat, "path"))));
        }
        dl.downloadAll(tasks, progress);
    }

    /** Estrae le native delle versioni vecchie (fino a 1.18 circa) nella cartella indicata. */
    public void extractNatives(Profile p, Path nativesDir) throws IOException {
        Files.createDirectories(nativesDir);
        for (Map<String, Object> lib : p.libraries) {
            Map<String, Object> nat = nativeArtifact(lib);
            if (nat == null) continue;
            List<String> exclude = new ArrayList<>();
            for (Object x : Json.arr(Json.obj(lib, "extract"), "exclude")) exclude.add(x.toString());
            Path jar = librariesDir().resolve(Json.str(nat, "path"));
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (e.isDirectory() || e.getName().startsWith("META-INF/")
                            || exclude.stream().anyMatch(e.getName()::startsWith)) continue;
                    Path out = nativesDir.resolve(e.getName()).normalize();
                    if (!out.startsWith(nativesDir)) continue;
                    if (Files.exists(out) && Files.size(out) == e.getSize()) continue;
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public void downloadAssets(Profile p, Path instanceDir) throws IOException {
        progress.stage("Controllo risorse di gioco");
        String indexId = Json.str(p.assetIndex, "id");
        Path indexFile = assetsDir().resolve("indexes").resolve(indexId + ".json");
        dl.downloadAll(List.of(Downloader.Task.of(p.assetIndex, indexFile)), progress);

        Map<String, Object> index = Json.parseObject(Files.readString(indexFile));
        Map<String, Object> objects = Json.obj(index, "objects");
        List<Downloader.Task> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : objects.values()) {
            String hash = Json.str(o, "hash");
            if (!seen.add(hash)) continue;
            Path dest = objectPath(hash);
            // per gli asset basta controllare la dimensione (sono migliaia: niente hash a ogni avvio)
            long size = Json.num(o, "size", -1);
            if (Files.exists(dest) && Files.size(dest) == size) continue;
            tasks.add(new Downloader.Task(ASSETS_URL + hash.substring(0, 2) + "/" + hash, dest, hash, size));
        }
        dl.downloadAll(tasks, progress);

        // Versioni molto vecchie (< 1.7.10): asset copiati con il loro nome reale.
        p.gameAssets = assetsDir().resolve("virtual").resolve(indexId);
        boolean virtual = Json.bool(index, "virtual", false);
        boolean toResources = Json.bool(index, "map_to_resources", false);
        if (virtual || toResources) {
            Path target = toResources ? instanceDir.resolve("resources") : p.gameAssets;
            p.gameAssets = target;
            for (Map.Entry<String, Object> e : objects.entrySet()) {
                Path out = target.resolve(e.getKey()).normalize();
                if (!out.startsWith(target)) continue;
                long size = Json.num(e.getValue(), "size", -1);
                if (Files.exists(out) && Files.size(out) == size) continue;
                Files.createDirectories(out.getParent());
                Files.copy(objectPath(Json.str(e.getValue(), "hash")), out, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (p.logging != null) {
            Map<String, Object> f = Json.obj(p.logging, "file");
            dl.downloadAll(List.of(Downloader.Task.of(f, logConfig(p))), progress);
        }
    }

    /** File di configurazione del log (log4j) indicato dal JSON della versione. */
    public Path logConfig(Profile p) {
        return assetsDir().resolve("log_configs").resolve(Json.str(Json.obj(p.logging, "file"), "id"));
    }

    private Path objectPath(String hash) {
        return assetsDir().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }
}
