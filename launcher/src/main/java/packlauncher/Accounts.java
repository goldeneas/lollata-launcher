package packlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import packlauncher.common.Json;

/**
 * Account Microsoft salvato su disco (accounts.json nella cartella dati del launcher),
 * così non serve rifare l'accesso a ogni avvio o cambio di server.
 *
 * <pre>
 * { "formatVersion": 1,
 *   "microsoft": { "name": "Steve", "uuid": "…", "xuid": "…",
 *                  "mcAccessToken": "…", "mcExpiresAt": 1730000000, "refreshToken": "…" } }
 * </pre>
 * Il file contiene token di accesso: su Linux/macOS viene reso leggibile solo dall'utente (600).
 */
public final class Accounts {
    public record Account(String name, String uuid, String xuid, String mcAccessToken,
                          long mcExpiresAt, String refreshToken) {

        public Session session(String clientId) {
            return new Session(name, uuid, mcAccessToken, true, xuid, clientId);
        }
    }

    private final Path file;

    public Accounts(Path baseDir) { this.file = baseDir.resolve("accounts.json"); }

    public Path file() { return file; }

    /** L'account Microsoft salvato, o null. Un file rovinato equivale a "nessun account". */
    public Account load() {
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Object> m = Json.obj(root, "microsoft");
            if (m == null || Json.str(m, "uuid") == null || Json.str(m, "name") == null) return null;
            return new Account(Json.str(m, "name"), Json.str(m, "uuid"), Json.str(m, "xuid"),
                    Json.str(m, "mcAccessToken"), Json.num(m, "mcExpiresAt", 0), Json.str(m, "refreshToken"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void save(Account a) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("uuid", a.uuid());
        m.put("xuid", a.xuid());
        m.put("mcAccessToken", a.mcAccessToken());
        m.put("mcExpiresAt", a.mcExpiresAt());
        m.put("refreshToken", a.refreshToken());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1);
        root.put("microsoft", m);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("accounts.json.tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        restrict(tmp);
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Esci: dimentica l'account. */
    public void clear() throws IOException { Files.deleteIfExists(file); }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: il file sta già in %APPDATA% dell'utente
        }
    }
}
