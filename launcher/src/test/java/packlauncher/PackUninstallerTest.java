package packlauncher;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackUninstallerTest {
    @TempDir Path home;

    @Test void removesOnlySelectedInstanceIncludingUserFiles() throws Exception {
        Path pack = home.resolve("instances/selected");
        for (String file : new String[]{"mods/a.jar", "saves/world/level.dat", "options.txt", ".packlauncher/state.json"}) {
            Path path = pack.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "data");
        }
        for (String file : new String[]{"instances/other/mods/b.jar", "libraries/shared.jar", "assets/index.json", "runtime/java", "accounts.json", "settings.properties"}) {
            Path path = home.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "keep");
        }
        assertTrue(PackUninstaller.isInstalled(home, "selected"));
        PackUninstaller.uninstall(home, "selected");
        assertFalse(Files.exists(pack));
        assertFalse(PackUninstaller.isInstalled(home, "selected"));
        assertEquals("keep", Files.readString(home.resolve("instances/other/mods/b.jar")));
        assertEquals("keep", Files.readString(home.resolve("libraries/shared.jar")));
        assertEquals("keep", Files.readString(home.resolve("assets/index.json")));
        assertEquals("keep", Files.readString(home.resolve("runtime/java")));
        assertEquals("keep", Files.readString(home.resolve("accounts.json")));
        assertEquals("keep", Files.readString(home.resolve("settings.properties")));
        PackUninstaller.uninstall(home, "selected");
    }

    @Test void rejectsTraversalAndDoesNotFollowLinks() throws Exception {
        assertThrows(IOException.class, () -> PackUninstaller.uninstall(home, "../outside"));
        Path outside = Files.createDirectory(home.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Path instances = Files.createDirectory(home.resolve("instances"));
        Path pack = Files.createDirectory(instances.resolve("selected"));
        Files.createSymbolicLink(pack.resolve("linked"), outside);
        PackUninstaller.uninstall(home, "selected");
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
        Files.createSymbolicLink(instances.resolve("selected"), outside);
        PackUninstaller.uninstall(home, "selected");
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        Files.delete(instances);
        Files.createSymbolicLink(instances, outside);
        assertThrows(IOException.class, () -> PackUninstaller.uninstall(home, "selected"));
    }
}
