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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import packlauncher.common.Hashing;
import packlauncher.common.Json;

/**
 * Genera pack.json + objects/ a partire dalla cartella di un'istanza client.
 *
 * Uso:
 *   java -jar pack-builder.jar <cartella-istanza> <cartella-output> [--index <index.json>] [--prune]
 *
 * Le opzioni del pack stanno in &lt;cartella-istanza&gt;/pack.properties (vedi README).
 * Se c'è un minecraftinstance.json (istanza CurseForge), versione di Minecraft e
 * NeoForge vengono lette da lì automaticamente.
 */
public final class PackBuilder {
    private static final List<String> DEFAULT_EXCLUDE = List.of(
            "logs/**", "crash-reports/**", "saves/**", "screenshots/**", "backups/**", "local/**",
            ".packlauncher/**", ".git/**", ".gitignore", ".curseclient", "minecraftinstance.json",
            "pack.properties", "usercache.json", "usernamecache.json", "*.log", "**/*.log",
            "downloads/**", "journeymap/data/**", "xaero/**", "patchouli_data.json");
    private static final List<String> DEFAULT_ONCE = List.of(
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat");

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Uso: java -jar pack-builder.jar <cartella-istanza> <cartella-output> [--index <index.json>] [--prune]");
            System.exit(1);
        }
        Path src = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        String indexArg = null;
        boolean prune = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--index") && i + 1 < args.length) indexArg = args[++i];
            else if (args[i].equals("--prune")) prune = true;
        }
        Path index = indexArg != null ? Path.of(indexArg).toAbsolutePath().normalize() : null;
        new PackBuilder().build(src, out, index, prune);
    }

    public void build(Path src, Path out, Path index, boolean prune) throws IOException {
        if (!Files.isDirectory(src)) throw new IOException("Cartella non trovata: " + src);
        Properties props = new Properties();
        Path propsFile = src.resolve("pack.properties");
        if (Files.exists(propsFile)) {
            try (Reader r = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) { props.load(r); }
        }

        // Rilevamento automatico da istanza CurseForge
        String detectedMc = null, detectedLoader = null, detectedLoaderType = null, detectedName = null, detectedVersion = null;
        Path cf = src.resolve("minecraftinstance.json");
        if (Files.exists(cf)) {
            Map<String, Object> j = Json.parseObject(Files.readString(cf, StandardCharsets.UTF_8));
            Map<String, Object> loader = Json.obj(j, "baseModLoader");
            detectedMc = Json.str(loader, "minecraftVersion");
            if (detectedMc == null) detectedMc = Json.str(j, "gameVersion");
            // "neoforge-21.1.228", "forge-47.3.0", "fabric-0.16.9-1.21.1", "quilt-0.27.1-1.20.1"
            String ln = Json.str(loader, "name");
            if (ln != null && ln.contains("-")) {
                detectedLoaderType = ln.substring(0, ln.indexOf('-'));
                detectedLoader = ln.substring(ln.indexOf('-') + 1);
                if (detectedMc != null && detectedLoader.endsWith("-" + detectedMc)) {
                    detectedLoader = detectedLoader.substring(0, detectedLoader.length() - detectedMc.length() - 1);
                }
            }
            detectedName = Json.str(j, "name");
            detectedVersion = Json.str(Json.obj(j, "manifest"), "version");
        }

        String id = require(props.getProperty("id"), "id");
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
        if (props.getProperty("server") != null) pack.put("server", props.getProperty("server"));
        pack.put("memoryMb", Long.parseLong(props.getProperty("memoryMb", "8192")));
        List<Object> jvm = new ArrayList<>();
        for (String a : props.getProperty("jvmArgs", "").trim().split("\\s+")) if (!a.isEmpty()) jvm.add(a);
        pack.put("jvmArgs", jvm);
        pack.put("files", entries);

        // pack.json per ultimo e in modo atomico: i client vedono o la vecchia o la nuova versione.
        Path packJson = out.resolve("pack.json");
        Path tmp = out.resolve("pack.json.tmp");
        Files.writeString(tmp, Json.write(pack));
        Files.move(tmp, packJson, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        System.out.printf("Pack %s v%s: %d file, %d MB, %d oggetti nuovi%n",
                id, version, entries.size(), totalSize / 1024 / 1024, copied);
        System.out.printf("Minecraft %s, %s %s%n", mc, loaderType, loaderVersion == null ? "" : loaderVersion);

        if (prune) prune(objects, used);
        if (index != null) updateIndex(index, out.resolve("pack.json"), id, name);
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
    private static void updateIndex(Path index, Path packJson, String id, String name) throws IOException {
        Map<String, Object> idx = Files.exists(index)
                ? Json.parseObject(Files.readString(index)) : new LinkedHashMap<>();
        List<Object> packs = (List<Object>) idx.computeIfAbsent("packs", k -> new ArrayList<>());
        packs.removeIf(p -> id.equals(Json.str(p, "id")));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("name", name);
        e.put("url", index.getParent().relativize(packJson).toString().replace('\\', '/'));
        packs.add(e);
        Files.writeString(index, Json.write(idx));
        System.out.println("Aggiornato " + index);
    }

    private static List<PathMatcher> matchers(List<String> defaults, String extra) {
        List<PathMatcher> out = new ArrayList<>();
        List<String> all = new ArrayList<>(defaults);
        for (String s : extra.split(",")) if (!s.isBlank()) all.add(s.trim());
        for (String g : all) out.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        return out;
    }

    private static boolean matchesAny(List<PathMatcher> ms, Path p) {
        for (PathMatcher m : ms) if (m.matches(p)) return true;
        return false;
    }

    private static String require(String v, String key) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Manca '" + key + "' in pack.properties");
        return v;
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }
}
