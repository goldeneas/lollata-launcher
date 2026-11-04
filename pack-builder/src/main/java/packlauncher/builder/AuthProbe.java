package packlauncher.builder;

import packlauncher.common.ServerProbe;

/**
 * Rileva se il server Minecraft di un modpack richiede un account premium (online-mode=true)
 * o accetta anche i nickname offline. Usato dal pack-builder con authMode=auto.
 *
 * Da riga di comando, per controllare un server al volo:
 *   java -cp pack-builder.jar packlauncher.builder.AuthProbe mc.example.org:25565
 */
public final class AuthProbe {
    private AuthProbe() {}

    /** "premium", "offline" o null se il server non risponde o la risposta non è chiara. */
    public static String detectAuthMode(String server) {
        if (server == null || server.isBlank()) return null;
        ServerProbe.Result r = new ServerProbe().probe(server);
        System.out.println("Server " + server + (r.version() != null ? " (" + r.version() + ")" : "")
                + ": " + (r.authMode() != null ? r.authMode() : "accesso non rilevato") + " - " + r.detail());
        return r.authMode();
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: java -cp pack-builder.jar packlauncher.builder.AuthProbe <host[:porta]>");
            System.exit(2);
        }
        System.exit(detectAuthMode(args[0]) != null ? 0 : 1);
    }
}
