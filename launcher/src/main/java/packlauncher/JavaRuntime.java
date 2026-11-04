package packlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import packlauncher.common.Json;

/**
 * Installa automaticamente il Java giusto per ogni versione di Minecraft,
 * usando i runtime ufficiali Mojang (gli stessi del launcher ufficiale):
 *   jre-legacy (Java 8)        -> fino a 1.16
 *   java-runtime-alpha (16)    -> 1.17
 *   java-runtime-gamma (17)    -> 1.18 - 1.20.4
 *   java-runtime-delta (21)    -> 1.20.5+
 * Il componente richiesto è scritto nel JSON della versione (javaVersion.component).
 *
 * Layout: &lt;base&gt;/runtime/&lt;componente&gt;/...
 */
public final class JavaRuntime {
    private static final String ALL_RUNTIMES =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /** Java richiesto da una versione di Minecraft: componente Mojang e versione major. */
    public record Version(String component, int major) {
        public Version {
            if (component == null) component = defaultComponent(major);
        }
    }

    private final Path runtimeDir;
    private final Downloader dl;
    private final Progress progress;

    public JavaRuntime(Path baseDir, Downloader dl, Progress progress) {
        this.runtimeDir = baseDir.resolve("runtime");
        this.dl = dl;
        this.progress = progress;
    }

    /**
     * Restituisce l'eseguibile Java da usare per il gioco.
     * Se il Java che sta eseguendo il launcher ha già la versione esatta richiesta lo riusa,
     * altrimenti scarica il runtime Mojang.
     */
    public String ensure(Version v) throws IOException {
        String component = v.component();
        int major = v.major();
        if (Runtime.version().feature() == major && !"32".equals(System.getProperty("sun.arch.data.model"))) {
            return javaIn(Path.of(System.getProperty("java.home"), "bin")).toString();
        }
        Path home = runtimeDir.resolve(component);
        Path marker = home.resolve(".installed");
        Path exe = executable(home);
        if (Files.exists(marker) && Files.exists(exe)) return exe.toString();

        String platform = platform();
        progress.stage("Installo Java " + major + " (" + component + ")");
        Map<String, Object> all = Json.parseObject(dl.getString(ALL_RUNTIMES));
        List<Object> variants = Json.arr(Json.obj(all, platform), component);
        if (variants.isEmpty()) {
            throw new IOException("Nessun Java " + major + " disponibile per " + platform
                    + ". Installa Java " + major + " a mano da https://adoptium.net");
        }
        Map<String, Object> manifestRef = Json.obj(variants.get(0), "manifest");
        Map<String, Object> manifest = Json.parseObject(dl.getString(Json.str(manifestRef, "url")));

        List<Downloader.Task> tasks = new ArrayList<>();
        List<Path> executables = new ArrayList<>();
        List<String[]> links = new ArrayList<>();
        for (Map.Entry<String, Object> e : Json.obj(manifest, "files").entrySet()) {
            Path dest = home.resolve(e.getKey()).normalize();
            if (!dest.startsWith(home)) continue;
            String type = Json.str(e.getValue(), "type");
            if ("directory".equals(type)) {
                Files.createDirectories(dest);
            } else if ("file".equals(type)) {
                tasks.add(Downloader.Task.of(Json.obj(Json.obj(e.getValue(), "downloads"), "raw"), dest));
                if (Json.bool(e.getValue(), "executable", false)) executables.add(dest);
            } else if ("link".equals(type)) {
                links.add(new String[]{e.getKey(), Json.str(e.getValue(), "target")});
            }
        }
        dl.downloadAll(tasks, progress);

        if (!OS.NAME.equals("windows")) {
            for (Path p : executables) p.toFile().setExecutable(true, false);
            for (String[] l : links) {
                Path link = home.resolve(l[0]);
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.createDirectories(link.getParent());
                Files.createSymbolicLink(link, Path.of(l[1]));
            }
        }
        if (!Files.exists(exe)) throw new IOException("Installazione Java non riuscita: manca " + exe);
        Files.writeString(marker, component);
        return exe.toString();
    }

    private static Path executable(Path home) {
        return javaIn(OS.NAME.equals("osx") ? home.resolve("jre.bundle/Contents/Home/bin") : home.resolve("bin"));
    }

    /** L'eseguibile Java in una cartella bin/ (su Windows javaw.exe, così non si apre la console). */
    private static Path javaIn(Path bin) {
        if (!OS.NAME.equals("windows")) return bin.resolve("java");
        Path javaw = bin.resolve("javaw.exe");
        return Files.exists(javaw) ? javaw : bin.resolve("java.exe");
    }

    static String defaultComponent(int major) {
        if (major <= 8) return "jre-legacy";
        if (major <= 16) return "java-runtime-alpha";
        if (major <= 17) return "java-runtime-gamma";
        return "java-runtime-delta";
    }

    static String platform() {
        switch (OS.NAME) {
            case "windows": return OS.ARCH.equals("arm64") ? "windows-arm64" : OS.ARCH.equals("x86") ? "windows-x86" : "windows-x64";
            case "osx": return OS.ARCH.equals("arm64") ? "mac-os-arm64" : "mac-os";
            default: return OS.ARCH.equals("arm64") ? "linux-arm64" : OS.ARCH.equals("x86") ? "linux-i386" : "linux";
        }
    }
}
