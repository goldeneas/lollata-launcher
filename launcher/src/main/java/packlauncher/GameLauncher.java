package packlauncher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import packlauncher.common.Json;

/** Costruisce la riga di comando e avvia il gioco. */
public final class GameLauncher {
    private static final Pattern VAR = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private GameLauncher() {}

    /** UUID offline, identico a quello che calcola il server in online-mode=false. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> buildCommand(GameInstaller inst, GameInstaller.Profile p, Pack pack,
                                            Path instanceDir, Session session, int memoryMb,
                                            String launcherName, String javaExe) throws IOException {
        Path natives = inst.versionDir(p.id).resolve("natives");
        inst.extractNatives(p, natives);

        Set<String> cp = new LinkedHashSet<>();
        for (Map<String, Object> lib : p.libraries) {
            Path lp = inst.libraryPath(lib);
            if (lp != null) cp.add(lp.toString());
        }
        cp.add(p.clientJar.toString());

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", session.name());
        vars.put("version_name", p.id);
        vars.put("game_directory", instanceDir.toString());
        vars.put("assets_root", inst.assetsDir().toString());
        vars.put("game_assets", p.gameAssets != null ? p.gameAssets.toString() : inst.assetsDir().toString());
        vars.put("assets_index_name", Json.str(p.assetIndex, "id"));
        vars.put("auth_uuid", session.uuid());
        vars.put("auth_access_token", session.accessToken());
        // versioni molto vecchie (--session): "token:<accessToken>:<uuid>"
        vars.put("auth_session", session.premium() ? "token:" + session.accessToken() + ":" + session.uuid() : "0");
        vars.put("user_properties", "{}");
        vars.put("clientid", session.clientId());
        vars.put("auth_xuid", session.xuid());
        vars.put("user_type", session.userType());
        vars.put("version_type", p.type != null ? p.type : "release");
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", launcherName);
        vars.put("launcher_version", "1.0");
        vars.put("classpath", String.join(File.pathSeparator, cp));
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", inst.librariesDir().toString());
        vars.put("quickPlayMultiplayer", pack.server != null ? pack.server : "");

        boolean hasServer = pack.server != null && !pack.server.isBlank();
        boolean quickPlay = p.legacyGameArgs == null && hasQuickPlay(p.gameArgs);
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_quick_play_multiplayer", hasServer && quickPlay);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Xmx" + memoryMb + "M");
        cmd.addAll(pack.jvmArgs);
        if (p.jvmArgs.isEmpty()) {
            // versioni vecchie: nessun argomento JVM nel JSON
            if (OS.NAME.equals("osx")) cmd.add("-XstartOnFirstThread");
            cmd.add("-Djava.library.path=" + natives);
            cmd.add("-cp");
            cmd.add(vars.get("classpath"));
        } else {
            cmd.addAll(expand(p.jvmArgs, vars, features));
        }
        if (p.logging != null) {
            Path cfg = inst.assetsDir().resolve("log_configs").resolve(Json.str(Json.obj(p.logging, "file"), "id"));
            cmd.add(Json.str(p.logging, "argument").replace("${path}", cfg.toString()));
        }
        cmd.add(p.mainClass);
        if (p.legacyGameArgs != null) {
            for (String a : p.legacyGameArgs.trim().split("\\s+")) cmd.add(subst(a, vars));
        } else {
            cmd.addAll(expand(p.gameArgs, vars, features));
        }
        if (hasServer && !quickPlay) {
            // prima della 1.20 non c'è Quick Play: si usano --server/--port
            String host = pack.server;
            String port = "25565";
            int colon = host.lastIndexOf(':');
            if (colon > 0) { port = host.substring(colon + 1); host = host.substring(0, colon); }
            cmd.add("--server"); cmd.add(host);
            cmd.add("--port"); cmd.add(port);
        }
        return cmd;
    }

    private static boolean hasQuickPlay(List<Object> gameArgs) {
        for (Object a : gameArgs) {
            if (a instanceof Map<?, ?> m) {
                for (Object r : Json.arr(m, "rules")) {
                    Map<String, Object> f = Json.obj(r, "features");
                    if (f != null && f.containsKey("is_quick_play_multiplayer")) return true;
                }
            }
        }
        return false;
    }

    public static Process start(List<String> cmd, Path instanceDir) throws IOException {
        Path out = instanceDir.resolve(".packlauncher").resolve("game-output.log");
        Files.createDirectories(out.getParent());
        return new ProcessBuilder(cmd)
                .directory(instanceDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
    }

    private static List<String> expand(List<Object> args, Map<String, String> vars, Map<String, Boolean> features) {
        List<String> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof String s) {
                out.add(subst(s, vars));
            } else if (a instanceof Map<?, ?> m) {
                if (!OS.allowed(Json.arr(m, "rules"), features)) continue;
                Object v = m.get("value");
                if (v instanceof List<?> l) l.forEach(x -> out.add(subst(x.toString(), vars)));
                else if (v != null) out.add(subst(v.toString(), vars));
            }
        }
        return out;
    }

    private static String subst(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = vars.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(b, Matcher.quoteReplacement(v));
        }
        m.appendTail(b);
        return b.toString();
    }
}
