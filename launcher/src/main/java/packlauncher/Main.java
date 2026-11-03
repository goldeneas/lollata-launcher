package packlauncher;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

/**
 * Avvio:
 *   java -jar launcher.jar                       -> interfaccia grafica
 *   java -jar launcher.jar --cli --name Steve    -> da terminale (debug / test)
 *       [--pack <id>] [--pack-url <url>] [--memory <MB>] [--dry-run]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        boolean cli = List.of(args).contains("--cli") || GraphicsEnvironment.isHeadless();
        if (!cli) {
            SwingUtilities.invokeLater(() -> new LauncherUI(cfg).show());
            return;
        }

        String name = arg(args, "--name", cfg.get("name", null));
        String packId = arg(args, "--pack", null);
        String packUrl = arg(args, "--pack-url", null);
        String mem = arg(args, "--memory", null);
        boolean dryRun = List.of(args).contains("--dry-run");

        Downloader dl = new Downloader(cfg.launcherName + "/1.0");
        if (packUrl == null) {
            List<Pack.Entry> packs = Pack.loadIndex(dl, cfg.indexUrl);
            Pack.Entry e = packs.stream().filter(p -> packId == null || p.id().equals(packId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Modpack non trovato: " + packId));
            packUrl = e.url();
        }
        Pack pack = Pack.load(dl, packUrl);
        int memory = mem != null ? Integer.parseInt(mem) : pack.memoryMb;

        LaunchFlow.Prepared prep = LaunchFlow.prepare(cfg, dl, pack, name, memory, Progress.CONSOLE);
        if (dryRun) {
            System.out.println(String.join("\n", prep.command()));
            return;
        }
        Process p = GameLauncher.start(prep.command(), prep.instanceDir());
        System.out.println("Gioco avviato (pid " + p.pid() + "), output in "
                + prep.instanceDir().resolve(".packlauncher/game-output.log"));
        System.exit(p.waitFor());
    }

    private static String arg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(key)) return args[i + 1];
        return def;
    }
}
