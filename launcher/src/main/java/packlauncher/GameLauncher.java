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
                                            Path instanceDir, String playerName, int memoryMb,
                                            String launcherName) throws IOException {
        Path natives = inst.versionDir(p.id).resolve("natives");
        Files.createDirectories(natives);

        Set<String> cp = new LinkedHashSet<>();
        for (Map<String, Object> lib : p.libraries) cp.add(inst.libraryPath(lib).toString());
        cp.add(p.clientJar.toString());

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", playerName);
        vars.put("version_name", p.id);
        vars.put("game_directory", instanceDir.toString());
        vars.put("assets_root", inst.assetsDir().toString());
        vars.put("assets_index_name", Json.str(p.assetIndex, "id"));
        vars.put("auth_uuid", offlineUuid(playerName).toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("version_type", p.type != null ? p.type : "release");
        vars.put("natives_directory", natives.toString());
        vars.put("launcher_name", launcherName);
        vars.put("launcher_version", "1.0");
        vars.put("classpath", String.join(File.pathSeparator, cp));
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", inst.librariesDir().toString());
        vars.put("quickPlayMultiplayer", pack.server != null ? pack.server : "");

        Map<String, Boolean> features = new HashMap<>();
        features.put("is_quick_play_multiplayer", pack.server != null && !pack.server.isBlank());

        List<String> cmd = new ArrayList<>();
        cmd.add(GameInstaller.javaExecutable());
        cmd.add("-Xmx" + memoryMb + "M");
        cmd.addAll(pack.jvmArgs);
        cmd.addAll(expand(p.jvmArgs, vars, features));
        if (p.logging != null) {
            Path cfg = inst.assetsDir().resolve("log_configs").resolve(Json.str(Json.obj(p.logging, "file"), "id"));
            cmd.add(Json.str(p.logging, "argument").replace("${path}", cfg.toString()));
        }
        cmd.add(p.mainClass);
        cmd.addAll(expand(p.gameArgs, vars, features));
        return cmd;
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
