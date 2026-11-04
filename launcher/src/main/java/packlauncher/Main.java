package packlauncher;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

/**
 * Avvio:
 *   java -jar launcher.jar                       -> interfaccia grafica
 *   java -jar launcher.jar --cli --name Steve    -> da terminale (debug / test)
 *       [--pack <id>] [--pack-url <url>] [--memory <MB>] [--dry-run]
 *   java -jar launcher.jar --cli --premium [--pack <id>] ...   -> con l'account Microsoft salvato
 *   java -jar launcher.jar --cli --login                       -> accesso Microsoft (codice da inserire su microsoft.com/link)
 *   java -jar launcher.jar --cli --logout
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        List<String> flags = List.of(args);
        if (!flags.contains("--cli") && !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> new LauncherUI(cfg).show());
            return;
        }

        AccountManager accounts = AccountManager.create(cfg);
        if (flags.contains("--logout")) {
            accounts.logout();
            System.out.println("Account Microsoft rimosso.");
            return;
        }
        if (flags.contains("--login")) {
            Accounts.Account a = accounts.login(dc -> System.out.println(
                    "Apri " + dc.verificationUri() + " e inserisci il codice: " + dc.userCode()), () -> false);
            System.out.println("Accesso eseguito: " + a.name());
            return;
        }
        boolean premium = flags.contains("--premium");
        String name = arg(args, "--name", cfg.get("name", null));
        String packId = arg(args, "--pack", null);
        String packUrl = arg(args, "--pack-url", null);
        String mem = arg(args, "--memory", null);

        Downloader dl = new Downloader(cfg.userAgent());
        if (packUrl == null) {
            List<Pack.Entry> packs = Pack.loadIndex(dl, cfg.indexUrl);
            Pack.Entry e = packs.stream().filter(p -> packId == null || p.id().equals(packId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Modpack non trovato: " + packId));
            packUrl = e.url();
        }
        Pack pack = Pack.load(dl, packUrl);
        int memory = mem != null ? Integer.parseInt(mem) : pack.memoryMb;

        Session session = premium ? accounts.premiumSession() : Session.offline(name);
        LaunchFlow.Prepared prep = LaunchFlow.prepare(cfg, dl, pack, session, memory, Progress.CONSOLE);
        if (flags.contains("--dry-run")) {
            System.out.println(String.join("\n", prep.command()));
            return;
        }
        Process p = GameLauncher.start(prep.command(), prep.instanceDir());
        System.out.println("Gioco avviato (pid " + p.pid() + "), output in " + prep.gameLog());
        System.exit(p.waitFor());
    }

    private static String arg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(key)) return args[i + 1];
        return def;
    }
}
