package packlauncher;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import packlauncher.common.Json;

/** Rilevamento OS e valutazione delle "rules" dei JSON di versione Mojang. */
public final class OS {
    public static final String NAME;
    public static final String ARCH;

    static {
        String os = System.getProperty("os.name").toLowerCase();
        NAME = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        String a = System.getProperty("os.arch").toLowerCase();
        ARCH = a.contains("aarch64") || a.contains("arm64") ? "arm64" : a.contains("64") ? "x86_64" : "x86";
    }

    private OS() {}

    /** Valuta una lista di regole. features = feature attive (es. is_quick_play_multiplayer). */
    public static boolean allowed(List<Object> rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allow = false;
        for (Object r : rules) {
            if (matches(r, features)) allow = "allow".equals(Json.str(r, "action"));
        }
        return allow;
    }

    private static boolean matches(Object rule, Map<String, Boolean> features) {
        Map<String, Object> os = Json.obj(rule, "os");
        if (os != null) {
            String name = Json.str(os, "name");
            if (name != null && !name.equals(NAME)) return false;
            String arch = Json.str(os, "arch");
            if (arch != null && !arch.equals(ARCH) && !(arch.equals("x86") && ARCH.equals("x86"))) return false;
            String version = Json.str(os, "version");
            if (version != null && !Pattern.compile(version).matcher(System.getProperty("os.version")).find()) return false;
        }
        Map<String, Object> f = Json.obj(rule, "features");
        if (f != null) {
            for (Map.Entry<String, Object> e : f.entrySet()) {
                boolean want = Boolean.TRUE.equals(e.getValue());
                if (features.getOrDefault(e.getKey(), false) != want) return false;
            }
        }
        return true;
    }
}
