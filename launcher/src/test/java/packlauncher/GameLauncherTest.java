package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameLauncherTest {
    @TempDir Path root;

    @Test
    void ramSceltaPrevaleSuiLimitiDelPackEDelProfilo() throws Exception {
        Pack pack = new Pack();
        pack.jvmArgs.addAll(List.of("-Xmx4G", "-Dpack.option=true"));
        GameInstaller.Profile profile = profile();
        profile.jvmArgs.addAll(List.of("-XX:MaxHeapSize=4096M", "-cp", "${classpath}"));

        List<String> cmd = command(pack, profile);
        assertEquals(List.of("-Xmx6144M"), cmd.stream()
                .filter(a -> a.startsWith("-Xmx") || a.startsWith("-XX:MaxHeapSize=")).toList());
        assertTrue(cmd.contains("-Dpack.option=true"));
        assertTrue(cmd.indexOf("-Xmx6144M") < cmd.indexOf(profile.mainClass));
    }

    @Test
    void ramSceltaSiApplicaAncheAlProfiloLegacy() throws Exception {
        Pack pack = new Pack();
        pack.jvmArgs.add("-Xmx4096M");
        GameInstaller.Profile profile = profile();
        profile.legacyGameArgs = "--username ${auth_player_name}";
        List<String> cmd = command(pack, profile);
        assertTrue(cmd.contains("-Xmx6144M"));
        assertFalse(cmd.contains("-Xmx4096M"));
        assertEquals(List.of("--username", "Steve"), cmd.subList(cmd.indexOf(profile.mainClass) + 1, cmd.size()));
    }

    @Test
    void neoforge1211EreditaQuickPlayVanillaEPassaIlServerUnaSolaVolta() throws Exception {
        Pack pack = new Pack();
        pack.server = "mc.example.test:25566";
        GameInstaller.Profile profile = neoforge1211();
        List<String> cmd = command(pack, profile);
        int quickPlay = cmd.indexOf("--quickPlayMultiplayer");
        assertTrue(quickPlay > cmd.indexOf(profile.mainClass));
        assertEquals(pack.server, cmd.get(quickPlay + 1));
        assertEquals(1, Collections.frequency(cmd, "--quickPlayMultiplayer"));
        assertFalse(cmd.contains("--server"));
        assertFalse(cmd.contains("--port"));
        assertTrue(cmd.contains("neoforgeclient"));
        assertFalse(cmd.stream().anyMatch(a -> a.contains("${")));
    }

    @Test
    void neoforge1211SenzaServerNonAggiungeParametriDiConnessione() throws Exception {
        List<String> cmd = command(new Pack(), neoforge1211());
        assertFalse(cmd.contains("--quickPlayMultiplayer"));
        assertFalse(cmd.contains("--server"));
    }

    private GameInstaller.Profile neoforge1211() throws Exception {
        GameInstaller installer = new GameInstaller(root, new Downloader("test"), Progress.CONSOLE);
        Path vanilla = installer.versionDir("1.21.1");
        Files.createDirectories(vanilla);
        // Argomenti originali Mojang 1.21.1; download e librerie omessi per il test offline.
        // Fonte: yushijinhun/minecraft-version-json-history, history/release/1.21.1.json
        try (var in = getClass().getResourceAsStream("/minecraft-1.21.1-arguments.json")) {
            Files.writeString(vanilla.resolve("1.21.1.json"), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Files.createFile(vanilla.resolve("1.21.1.jar"));
        Path loader = installer.versionDir("neoforge-test");
        Files.createDirectories(loader);
        Files.writeString(loader.resolve("neoforge-test.json"), """
                {"id":"neoforge-test", "inheritsFrom":"1.21.1",
                 "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
                 "arguments":{"game":["--launchTarget","neoforgeclient"],"jvm":[]}}
                """);
        return installer.resolve("neoforge-test");
    }

    private GameInstaller.Profile profile() {
        GameInstaller.Profile profile = new GameInstaller.Profile();
        profile.id = "test";
        profile.mainClass = "net.minecraft.client.main.Main";
        profile.clientJar = root.resolve("client.jar");
        profile.assetIndex = Map.of("id", "test");
        return profile;
    }

    private List<String> command(Pack pack, GameInstaller.Profile profile) throws Exception {
        GameInstaller installer = new GameInstaller(root, new Downloader("test"), Progress.CONSOLE);
        return GameLauncher.buildCommand(installer, profile, pack, root.resolve("instance"),
                Session.offline("Steve"), 6144, "test", "java");
    }
}
