package packlauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Il flusso completo: pack -> vanilla -> loader -> librerie/asset -> sync -> comando. */
public final class LaunchFlow {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private LaunchFlow() {}

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public record Prepared(List<String> command, Path instanceDir, Pack pack) {}

    public static Prepared prepare(Config cfg, Downloader dl, Pack pack, String playerName,
                                   int memoryMb, Progress progress) throws IOException {
        if (!isValidName(playerName)) {
            throw new IOException("Nome non valido: 3-16 caratteri, solo lettere, numeri e _");
        }
        if ("32".equals(System.getProperty("sun.arch.data.model"))) {
            throw new IOException("Stai usando Java a 32 bit: installa Java 21 a 64 bit (https://adoptium.net).");
        }

        Path root = cfg.baseDir;
        Path instanceDir = root.resolve("instances").resolve(pack.id);
        Files.createDirectories(instanceDir);
        GameInstaller inst = new GameInstaller(root, dl, progress);

        // 1. Minecraft vanilla
        inst.installVanilla(pack.minecraft);

        // 2. Mod loader
        String versionId = pack.minecraft;
        if (pack.loaderType != null && !pack.loaderType.equals("vanilla")) {
            if (!pack.loaderType.equals("neoforge")) {
                throw new IOException("Loader non ancora supportato: " + pack.loaderType);
            }
            versionId = inst.installNeoForge(pack.loaderVersion, pack.minecraft);
        }

        // 3. Profilo, librerie e asset
        GameInstaller.Profile profile = inst.resolve(versionId);
        int javaNow = Runtime.version().feature();
        if (javaNow < profile.javaMajor) {
            throw new IOException("Serve Java " + profile.javaMajor + " (hai Java " + javaNow
                    + "). Scaricalo da https://adoptium.net e riapri il launcher.");
        }
        inst.downloadLibraries(profile);
        inst.downloadAssets(profile);

        // 4. Modpack
        new PackSync(instanceDir, dl, progress).sync(pack);

        // 5. Comando
        progress.stage("Avvio di Minecraft...");
        List<String> cmd = GameLauncher.buildCommand(inst, profile, pack, instanceDir,
                playerName, memoryMb, cfg.launcherName);
        return new Prepared(cmd, instanceDir, pack);
    }
}
