package packlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import packlauncher.common.ServerProbe;

/** Il flusso completo: pack -> vanilla -> Java -> loader -> librerie/asset -> sync -> comando. */
public final class LaunchFlow {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private LaunchFlow() {}

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public record Prepared(List<String> command, Path instanceDir, Pack pack) {}

    /** Attesa massima per la sonda del server Minecraft prima di ripiegare sul manifest. */
    public static final int PROBE_TIMEOUT_MS = 3000;

    /**
     * Chiede direttamente al server Minecraft se è premium o offline, così un cambio di online-mode
     * viene visto subito, senza ripubblicare il pack. Se il server non risponde resta il valore del manifest.
     * Restituisce il risultato della sonda (null se il pack non ha un server).
     */
    public static ServerProbe.Result refreshAuthMode(Pack pack, ServerProbe probe) {
        if (pack.server == null || pack.server.isBlank()) return null;
        ServerProbe.Result r = probe.probe(pack.server);
        if (r.authMode() != null) pack.authMode = r.authMode();
        return r;
    }

    /** true se il pack non ha un server o se il server ha risposto (cioè è acceso). */
    public static boolean serverOnline(ServerProbe.Result r) {
        return r == null || r.online();
    }

    public static String offlineMessage(Pack pack) {
        return "Il server " + (pack.name != null ? pack.name : "") + " (" + pack.server + ") è spento o non raggiungibile.\n"
                + "Riprova quando è acceso.";
    }

    /** Testo per il log: cosa ha risposto il server e quale tipo di accesso viene usato. */
    public static String describeProbe(Pack pack, ServerProbe.Result r) {
        if (r != null && !r.online()) return "Server " + pack.server + " spento o non raggiungibile (" + r.detail() + ")";
        if (r == null) return "Nessun server Minecraft indicato: accesso " + (pack.authMode != null ? pack.authMode : "offline");
        if (r.authMode() != null) return "Server " + pack.server + ": " + r.authMode() + " (" + r.detail() + ")";
        return "Server " + pack.server + " non verificato (" + r.detail() + "): uso il manifest, accesso "
                + (pack.authMode != null ? pack.authMode : "offline");
    }

    /** Controlla nome e tipo di account rispetto al server, prima di scaricare qualsiasi cosa. */
    public static void checkAccess(Pack pack, Session session) throws IOException {
        if (!session.premium() && !isValidName(session.name())) {
            throw new IOException("Nome non valido: 3-16 caratteri, solo lettere, numeri e _");
        }
        String deny = AccessPolicy.denyReason(pack.authMode, session.premium(), pack.name);
        if (deny != null) throw new IOException(deny);
    }

    public static Prepared prepare(Config cfg, Downloader dl, Pack pack, Session session,
                                   int memoryMb, Progress progress) throws IOException {
        ServerProbe.Result probe = refreshAuthMode(pack, new ServerProbe(PROBE_TIMEOUT_MS));
        progress.log(describeProbe(pack, probe));
        if (!serverOnline(probe)) throw new IOException(offlineMessage(pack));
        checkAccess(pack, session);

        Path root = cfg.baseDir;
        Path instanceDir = root.resolve("instances").resolve(pack.id);
        Files.createDirectories(instanceDir);
        GameInstaller inst = new GameInstaller(root, dl, progress);
        JavaRuntime runtimes = new JavaRuntime(root, dl, progress);

        // 1. Minecraft vanilla (la versione la decide il modpack)
        Map<String, Object> vanilla = inst.installVanilla(pack.minecraft);

        // 2. Java giusto per questa versione di Minecraft (scaricato in automatico)
        String[] java = GameInstaller.javaFor(vanilla);
        String javaExe = runtimes.ensure(java[0], Integer.parseInt(java[1]));

        // 3. Mod loader
        String versionId = inst.installLoader(pack.loaderType, pack.loaderVersion, pack.minecraft, javaExe);

        // 4. Profilo, librerie e asset
        GameInstaller.Profile profile = inst.resolve(versionId);
        if (profile.javaMajor != Integer.parseInt(java[1])) {
            javaExe = runtimes.ensure(profile.javaComponent, profile.javaMajor);
        }
        inst.downloadLibraries(profile);
        inst.downloadAssets(profile, instanceDir);

        // 5. Modpack
        new PackSync(instanceDir, dl, progress).sync(pack);

        // 6. Comando
        progress.stage("Avvio di Minecraft...");
        List<String> cmd = GameLauncher.buildCommand(inst, profile, pack, instanceDir,
                session, memoryMb, cfg.launcherName, javaExe);
        return new Prepared(cmd, instanceDir, pack);
    }
}
