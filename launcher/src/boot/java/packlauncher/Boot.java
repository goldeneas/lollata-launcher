package packlauncher;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

import javax.swing.JOptionPane;

/**
 * Punto d'ingresso compilato per Java 8: se il Java installato è troppo vecchio
 * mostra un messaggio chiaro (invece di un errore incomprensibile) e apre la pagina
 * di download. Altrimenti avvia il launcher vero.
 */
public final class Boot {
    private static final int REQUIRED = 17;

    public static void main(String[] args) throws Exception {
        String spec = System.getProperty("java.specification.version");
        int major = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec.split("\\.")[0]);
        if (major < REQUIRED) {
            String msg = "Il launcher richiede Java " + REQUIRED + " o superiore (hai Java " + major + ").\n"
                    + "Scarica e installa Java 21 da https://adoptium.net e riapri il launcher.\n"
                    + "(Minecraft e il Java per il gioco vengono poi scaricati in automatico.)";
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println(msg);
            } else {
                JOptionPane.showMessageDialog(null, msg, "Java troppo vecchio", JOptionPane.WARNING_MESSAGE);
                try { Desktop.getDesktop().browse(new URI("https://adoptium.net/temurin/releases/?version=21")); }
                catch (Exception ignored) { }
            }
            System.exit(1);
        }
        Class.forName("packlauncher.Main").getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
