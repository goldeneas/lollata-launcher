package packlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Il flusso completo: pack -> vanilla -> Java -> loader -> librerie/asset -> sync -> comando. */
public final class LaunchFlow {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private LaunchFlow() {}

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public record Prepared(List<String> command, Path instanceDir, Pack pack) {}

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
