package packlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import packlauncher.common.Hashing;
import packlauncher.common.Json;

/**
 * Sincronizza la cartella dell'istanza con pack.json.
 *
 * - Scarica i file mancanti o modificati.
 * - I file "once" (es. options.txt) vengono scritti solo se non esistono:
 *   le modifiche dell'utente restano.
 * - Cancella solo i file che il launcher aveva installato in precedenza e che
 *   non sono più nel pack: le mod aggiunte a mano dall'utente non vengono toccate.
 *
 * Stato in &lt;istanza&gt;/.packlauncher/state.json (path -> sha1/size/mtime),
 * così ai successivi avvii non serve ricalcolare l'hash di ogni file.
 */
public final class PackSync {
    private final Path instance;
    private final Downloader dl;
    private final Progress progress;

    public PackSync(Path instance, Downloader dl, Progress progress) {
        this.instance = instance;
        this.dl = dl;
        this.progress = progress;
    }

    public void sync(Pack pack) throws IOException {
        progress.stage("Aggiorno il modpack " + pack.name + " (v" + pack.version + ")");
        Path stateFile = instance.resolve(".packlauncher").resolve("state.json");
        Map<String, Object> oldState = Files.exists(stateFile)
                ? Json.obj(Json.parseObject(Files.readString(stateFile)), "files") : null;
        if (oldState == null) oldState = new LinkedHashMap<>();

        List<Downloader.Task> tasks = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        long checked = 0;
        for (Pack.PackFile f : pack.files) {
            Path dest = safeResolve(f.path());
            progress.progress(++checked, pack.files.size());
            if (!f.once()) wanted.add(f.path());
            boolean needed = f.once() ? !Files.exists(dest) : !upToDate(dest, f, oldState.get(f.path()));
            if (needed) tasks.add(new Downloader.Task(pack.objectUrl(f.sha1()), dest, f.sha1(), f.size()));
        }

        // Rimuovi i file gestiti che non fanno più parte del pack.
        for (String old : oldState.keySet()) {
            if (wanted.contains(old)) continue;
            Path p = safeResolve(old);
            if (Files.deleteIfExists(p)) progress.log("Rimosso: " + old);
        }

        if (!tasks.isEmpty()) {
            progress.stage("Scarico " + tasks.size() + " file del modpack");
            dl.downloadAll(tasks, progress);
        }

        // Salva il nuovo stato.
        Map<String, Object> files = new LinkedHashMap<>();
        for (Pack.PackFile f : pack.files) {
            if (f.once()) continue;
            Path dest = safeResolve(f.path());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sha1", f.sha1());
            e.put("size", f.size());
            e.put("mtime", Files.getLastModifiedTime(dest).toMillis());
            files.put(f.path(), e);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("packVersion", pack.version);
        state.put("files", files);
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, Json.write(state));
    }

    private boolean upToDate(Path dest, Pack.PackFile f, Object prev) throws IOException {
        if (!Files.isRegularFile(dest)) return false;
        long size = Files.size(dest);
        if (f.size() >= 0 && size != f.size()) return false;
        // scorciatoia: stesso hash atteso, stessa dimensione e mtime dell'ultima sync
        if (prev != null && f.sha1().equals(Json.str(prev, "sha1"))
                && Json.num(prev, "size", -2) == size
                && Json.num(prev, "mtime", -2) == Files.getLastModifiedTime(dest).toMillis()) {
            return true;
        }
        return f.sha1().equalsIgnoreCase(Hashing.sha1(dest));
    }

    /** Impedisce percorsi che escono dalla cartella dell'istanza (es. "../../"). */
    private Path safeResolve(String rel) throws IOException {
        Path p = instance.resolve(rel).normalize();
        if (!p.startsWith(instance.normalize()) || rel.startsWith("/") || rel.contains(":")) {
            throw new IOException("Percorso non valido nel pack: " + rel);
        }
        return p;
    }
}
