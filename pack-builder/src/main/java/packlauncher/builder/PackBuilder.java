package packlauncher.builder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import packlauncher.common.Hashing;
import packlauncher.common.Json;
import packlauncher.common.Minecraft;

/**
 * Genera pack.json + objects/ a partire da un'istanza di Prism Launcher.
 *
 * Uso:
 *   java -jar pack-builder.jar <cartella-istanza-prism> <cartella-output> [--index <index.json>] [--prune]
 *   java -jar pack-builder.jar --all <cartella-con-le-istanze> <cartella-www> [--prune]
 *
 * Con --all ogni sottocartella che è un'istanza Prism diventa un server/modpack:
 * &lt;www&gt;/&lt;id&gt;/pack.json + objects/, e &lt;www&gt;/index.json elenca esattamente quelle istanze.
 *
 * Versione di Minecraft e loader vengono lette da mmc-pack.json, il nome da instance.cfg.
 * Vengono pubblicati i file di .minecraft/. Le opzioni del pack stanno in
 * &lt;cartella-istanza&gt;/pack.properties, accanto a instance.cfg (vedi README).
 */
public final class PackBuilder {
    private static final List<String> DEFAULT_EXCLUDE = List.of(
            "logs/**", "crash-reports/**", "saves/**", "screenshots/**", "backups/**", "local/**",
            ".packlauncher/**", ".git/**", ".gitignore", "usercache.json", "usernamecache.json", "*.log", "**/*.log",
            "downloads/**", "journeymap/data/**", "xaero/**", "patchouli_data.json");
    private static final Map<String, String> PRISM_LOADERS = Map.of(
            "net.neoforged", "neoforge",
            "net.minecraftforge", "forge",
            "net.fabricmc.fabric-loader", "fabric",
            "org.quiltmc.quilt-loader", "quilt");
    private static final List<String> DEFAULT_ONCE = List.of(
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat");

    private static final String USAGE = """
            Uso:
              java -jar pack-builder.jar <cartella-istanza-prism> <cartella-output> [--index <index.json>] [--prune]
              java -jar pack-builder.jar --all <cartella-con-le-istanze> <cartella-www> [--prune]""";

    public static void main(String[] args) throws IOException {
        List<String> pos = new ArrayList<>();
        String indexArg = null;
        boolean prune = false, all = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--index") && i + 1 < args.length) indexArg = args[++i];
            else if (args[i].equals("--prune")) prune = true;
            else if (args[i].equals("--all")) all = true;
            else pos.add(args[i]);
        }
        if (pos.size() != 2) {
            System.err.println(USAGE);
            System.exit(1);
        }
        Path src = Path.of(pos.get(0)).toAbsolutePath().normalize();
        Path out = Path.of(pos.get(1)).toAbsolutePath().normalize();
        Path index = indexArg != null ? Path.of(indexArg).toAbsolutePath().normalize() : null;
        try {
            if (all) new PackBuilder().buildAll(src, out, prune);
            else new PackBuilder().build(src, out, index, prune);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Errore: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Pubblica tutte le istanze Prism in root (una per sottocartella) in www/&lt;id&gt;/ e riscrive
     * www/index.json con esattamente quei server. Le cartelle senza mmc-pack.json vengono ignorate.
     * Restituisce gli id pubblicati.
     */
    public List<String> buildAll(Path root, Path www, boolean prune) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Cartella non trovata: " + root);
        List<Path> instances;
        try (Stream<Path> s = Files.list(root)) {
            instances = s.filter(d -> Files.isRegularFile(d.resolve("mmc-pack.json"))).sorted().toList();
        }
        if (instances.isEmpty()) throw new IOException("Nessuna istanza Prism (con mmc-pack.json) in " + root);
        // Prima tutti gli id, così un doppione blocca tutto prima di pubblicare qualcosa.
        Map<String, Path> byId = new LinkedHashMap<>();
        for (Path inst : instances) {
            String id = readId(inst);
            if (byId.putIfAbsent(id, inst) != null) throw new IllegalArgumentException("Id duplicato '" + id + "' (" + inst + ")");
        }
        List<Map<String, Object>> built = new ArrayList<>();
        for (Map.Entry<String, Path> e : byId.entrySet()) {
            System.out.println("== " + e.getValue().getFileName() + " -> " + e.getKey());
            built.add(build(e.getValue(), www.resolve(e.getKey()), null, prune));
        }
        Path index = www.resolve("index.json");
        Set<String> previous = new HashSet<>();
        if (Files.exists(index)) {
            for (Object p : Json.arr(Json.parseObject(Files.readString(index)), "packs")) previous.add(Json.str(p, "id"));
        }
        previous.removeAll(byId.keySet());
        for (String gone : previous) {
            System.out.println("Tolto dall'indice (istanza non più presente): " + gone + "  (i file in " + www.resolve(gone) + " restano)");
        }
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("formatVersion", 2);
        List<Object> packs = new ArrayList<>();
        for (Map<String, Object> pack : built) packs.add(indexEntry(index, www.resolve(Json.str(pack, "id")).resolve("pack.json"), pack));
        idx.put("packs", packs);
        writeAtomic(index, Json.write(idx));
        System.out.println("Aggiornato " + index + " (" + packs.size() + " server)");
        return built.stream().map(p -> Json.str(p, "id")).toList();
    }

    /** Id del pack: "id" in pack.properties, altrimenti il nome della cartella. */
    static String readId(Path instance) throws IOException {
        Properties props = loadProps(instance.resolve("pack.properties"));
        String id = blankToNull(props.getProperty("id"));
        if (id == null) id = instance.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
        if (!Minecraft.isValidId(id)) {
            throw new IllegalArgumentException("Id non valido '" + id + "': usa lettere, numeri, '_', '-' o '.'");
        }
        return id;
    }

    private static Properties loadProps(Path file) throws IOException {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { props.load(r); }
        }
        return props;
    }

    /**
     * Genera &lt;out&gt;/pack.json + objects/ e (se index != null) aggiorna la voce in index.json.
     * Restituisce il manifest scritto.
     */
    public Map<String, Object> build(Path instance, Path out, Path index, boolean prune) throws IOException {
        if (!Files.isDirectory(instance)) throw new IOException("Cartella non trovata: " + instance);
        Path mmcPack = instance.resolve("mmc-pack.json");
        if (!Files.exists(mmcPack)) {
            throw new IOException("Non è un'istanza Prism (manca mmc-pack.json): " + instance
                    + "\nIndica la cartella dell'istanza, es. ~/.local/share/PrismLauncher/instances/<nome>");
        }
        // I file del gioco stanno in .minecraft/ (o minecraft/ nelle istanze più vecchie)
        Path src = instance.resolve(".minecraft");
        if (!Files.isDirectory(src)) src = instance.resolve("minecraft");
        if (!Files.isDirectory(src)) throw new IOException("Manca la cartella .minecraft/ in " + instance);

        Properties props = loadProps(instance.resolve("pack.properties"));

        // Versione di Minecraft e loader da mmc-pack.json
        String detectedMc = null, detectedLoader = null, detectedLoaderType = null;
        Map<String, Object> mmc = Json.parseObject(Files.readString(mmcPack, StandardCharsets.UTF_8));
        for (Object c : Json.arr(mmc, "components")) {
            String uid = Json.str(c, "uid");
            String ver = Json.str(c, "version");
            if (uid == null || ver == null) continue;
            String type = PRISM_LOADERS.get(uid);
            if (uid.equals("net.minecraft")) detectedMc = ver;
            else if (type != null) { detectedLoaderType = type; detectedLoader = ver; }
        }
        // Nome e versione del modpack da instance.cfg
        Properties ic = loadProps(instance.resolve("instance.cfg"));
        String detectedName = blankToNull(ic.getProperty("name"));
        String detectedVersion = blankToNull(ic.getProperty("ManagedPackVersionName"));

        String id = readId(instance);
        String name = props.getProperty("name", detectedName != null ? detectedName : id);
        String mc = require(props.getProperty("minecraft", detectedMc), "minecraft");
        String loaderType = props.getProperty("loader", detectedLoaderType != null ? detectedLoaderType : "vanilla");
        if (!List.of("vanilla", "neoforge", "forge", "fabric", "quilt").contains(loaderType)) {
            throw new IllegalArgumentException("Loader non supportato: " + loaderType);
        }
        String loaderVersion = props.getProperty("loaderVersion", detectedLoader);
        if (!loaderType.equals("vanilla")) require(loaderVersion, "loaderVersion");
        String version = props.getProperty("version", detectedVersion != null
                ? detectedVersion + "-" + stamp() : stamp());
        String server = blankToNull(props.getProperty("server"));
        String authMode = resolveAuthMode(props.getProperty("authMode"), server);

        List<PathMatcher> exclude = matchers(DEFAULT_EXCLUDE, props.getProperty("exclude", ""));
        List<PathMatcher> once = matchers(DEFAULT_ONCE, props.getProperty("once", ""));

        // Scansione file
        List<Path> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        Path objects = out.resolve("objects");
        Files.createDirectories(objects);
        List<Object> entries = new ArrayList<>();
        Set<String> used = new HashSet<>();
        long totalSize = 0;
        int copied = 0;
        for (Path f : files) {
            String rel = src.relativize(f).toString().replace('\\', '/');
            Path relPath = Path.of(rel);
            if (matchesAny(exclude, relPath)) continue;
            String sha1 = Hashing.sha1(f);
            long size = Files.size(f);
            Path obj = objects.resolve(sha1.substring(0, 2)).resolve(sha1);
            if (!Files.exists(obj)) {
                Files.createDirectories(obj.getParent());
                Path tmp = obj.resolveSibling(sha1 + ".tmp");
                Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, obj, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                copied++;
            }
            used.add(sha1);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", rel);
            e.put("sha1", sha1);
            e.put("size", size);
            if (matchesAny(once, relPath)) e.put("once", true);
            entries.add(e);
            totalSize += size;
        }

        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("formatVersion", 1);
        pack.put("id", id);
        pack.put("name", name);
        pack.put("version", version);
        pack.put("minecraft", mc);
        Map<String, Object> loader = new LinkedHashMap<>();
        loader.put("type", loaderType);
        if (loaderVersion != null) loader.put("version", loaderVersion);
        pack.put("loader", loader);
        if (server != null) pack.put("server", server);
        if (authMode != null) pack.put("authMode", authMode);
        pack.put("memoryMb", Long.parseLong(props.getProperty("memoryMb", "8192")));
        List<Object> jvm = new ArrayList<>();
        for (String a : props.getProperty("jvmArgs", "").trim().split("\\s+")) if (!a.isEmpty()) jvm.add(a);
        pack.put("jvmArgs", jvm);
        pack.put("files", entries);

        // pack.json per ultimo e in modo atomico: i client vedono o la vecchia o la nuova versione.
        Path packJson = out.resolve("pack.json");
        writeAtomic(packJson, Json.write(pack));

        System.out.printf("Pack %s v%s: %d file, %d MB, %d oggetti nuovi%n",
                id, version, entries.size(), totalSize / 1024 / 1024, copied);
        System.out.printf("Minecraft %s, %s %s%n", mc, loaderType, loaderVersion == null ? "" : loaderVersion);
        System.out.println("Accesso: " + (authMode == null ? "non indicato" : authMode));

        if (prune) prune(objects, used);
        if (index != null) updateIndex(index, packJson, pack);
        return pack;
    }

    /** authMode da pack.properties: premium | offline | auto (default: chiede al server Minecraft). */
    static String resolveAuthMode(String configured, String server) {
        String v = configured == null || configured.isBlank() ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case Minecraft.PREMIUM, Minecraft.OFFLINE: return v;
            case "auto": break;
            default: throw new IllegalArgumentException("authMode non valido: " + configured + " (premium | offline | auto)");
        }
        if (server == null) return null;
        String detected = AuthProbe.detectAuthMode(server);
        if (detected == null) {
            System.err.println("Attenzione: tipo di accesso di " + server + " non rilevato, il launcher lo tratterà "
                    + "come \"offline\". Avvia il server e ripubblica, oppure imposta authMode=premium|offline "
                    + "in pack.properties.");
        }
        return detected;
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void prune(Path objects, Set<String> used) throws IOException {
        int removed = 0;
        try (Stream<Path> s = Files.walk(objects)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (!used.contains(p.getFileName().toString())) { Files.delete(p); removed++; }
            }
        }
        System.out.println("Rimossi " + removed + " oggetti non più usati.");
    }

    @SuppressWarnings("unchecked")
    static void updateIndex(Path index, Path packJson, Map<String, Object> pack) throws IOException {
        Map<String, Object> idx = Files.exists(index)
                ? Json.parseObject(Files.readString(index)) : new LinkedHashMap<>();
        idx.put("formatVersion", 2);
        List<Object> packs = (List<Object>) idx.computeIfAbsent("packs", k -> new ArrayList<>());
        String id = Json.str(pack, "id");
        Map<String, Object> e = indexEntry(index, packJson, pack);
        boolean replaced = false;
        for (int i = 0; i < packs.size(); i++) {
            if (id.equals(Json.str(packs.get(i), "id"))) { packs.set(i, e); replaced = true; break; }
        }
        if (!replaced) packs.add(e);
        writeAtomic(index, Json.write(idx));
        System.out.println("Aggiornato " + index);
    }

    /** Voce di index.json: i dati principali del pack, così il launcher non deve scaricare ogni pack.json. */
    static Map<String, Object> indexEntry(Path index, Path packJson, Map<String, Object> pack) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", pack.get("id"));
        e.put("name", pack.get("name"));
        e.put("url", index.toAbsolutePath().getParent().relativize(packJson.toAbsolutePath()).toString().replace('\\', '/'));
        for (String k : List.of("version", "minecraft", "loader", "server", "authMode")) {
            if (pack.get(k) != null) e.put(k, pack.get(k));
        }
        return e;
    }

    private static List<PathMatcher> matchers(List<String> defaults, String extra) {
        List<String> all = new ArrayList<>(defaults);
        for (String s : extra.split(",")) if (!s.isBlank()) all.add(s.trim());
        return all.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
    }

    private static boolean matchesAny(List<PathMatcher> ms, Path p) {
        for (PathMatcher m : ms) if (m.matches(p)) return true;
        return false;
    }

    private static String require(String v, String key) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Manca '" + key + "' in pack.properties");
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }
}
