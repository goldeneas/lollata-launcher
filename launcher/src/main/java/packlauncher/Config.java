package packlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configurazione interna (nel jar) + impostazioni utente (su disco). */
public final class Config {
    public final String launcherName;
    public final String indexUrl;
    /** Client id dell'app Azure per il login Microsoft; vuoto = login premium non disponibile. */
    public final String msaClientId;
    public final Path baseDir;
    private final Properties user = new Properties();
    private final Path userFile;

    public Config() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Config.class.getResourceAsStream("/launcher.properties")) {
            if (in != null) p.load(in);
        }
        launcherName = System.getProperty("launcher.name", p.getProperty("launcher.name", "PackLauncher"));
        indexUrl = System.getProperty("index.url", p.getProperty("index.url"));
        msaClientId = System.getProperty("msa.clientId", p.getProperty("msa.clientId", "")).trim();
        String dir = System.getProperty("launcher.dir", p.getProperty("launcher.dir", "packlauncher"));
        baseDir = Path.of(System.getProperty("launcher.home", defaultBase(dir).toString()));
        Files.createDirectories(baseDir);
        userFile = baseDir.resolve("settings.properties");
        if (Files.exists(userFile)) {
            try (Reader r = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) { user.load(r); }
        }
    }

    private static Path defaultBase(String dir) {
        String home = System.getProperty("user.home");
        switch (OS.NAME) {
            case "windows": {
                String appData = System.getenv("APPDATA");
                return Path.of(appData != null ? appData : home, "." + dir);
            }
            case "osx": return Path.of(home, "Library", "Application Support", dir);
            default: return Path.of(home, "." + dir);
        }
    }

    public String get(String key, String def) { return user.getProperty(key, def); }

    public void set(String key, String value) { user.setProperty(key, value); }

    /**
     * Impostazione di un singolo server (es. nickname o RAM), salvata come
     * "server.&lt;id&gt;.&lt;chiave&gt;". Se manca si usa il valore globale "&lt;chiave&gt;"
     * (impostazioni delle versioni precedenti), poi def.
     */
    public String getFor(String serverId, String key, String def) {
        String v = serverId != null ? user.getProperty(serverKey(serverId, key)) : null;
        return v != null ? v : user.getProperty(key, def);
    }

    public void setFor(String serverId, String key, String value) {
        if (value == null) user.remove(serverKey(serverId, key));
        else user.setProperty(serverKey(serverId, key), value);
    }

    private static String serverKey(String serverId, String key) {
        return "server." + serverId + "." + key;
    }

    public void save() throws IOException {
        try (Writer w = Files.newBufferedWriter(userFile, StandardCharsets.UTF_8)) {
            user.store(w, launcherName + " - impostazioni utente");
        }
    }
}
