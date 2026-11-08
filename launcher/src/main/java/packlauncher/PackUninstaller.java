package packlauncher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import packlauncher.common.Minecraft;

/** Removes only a pack's instance; shared libraries, assets, runtimes and accounts are retained. */
public final class PackUninstaller {
    private PackUninstaller() {}

    private static Path instance(Path base, String id) throws IOException {
        if (!Minecraft.isValidId(id)) throw new IOException("Id del pack non valido");
        Path root = base.toAbsolutePath().normalize().resolve("instances");
        if (Files.isSymbolicLink(root)) throw new IOException("La cartella instances è un collegamento simbolico");
        return root.resolve(id);
    }

    public static boolean isInstalled(Path base, String id) {
        try { return Files.exists(instance(base, id), LinkOption.NOFOLLOW_LINKS); }
        catch (IOException e) { return false; }
    }

    public static void uninstall(Path base, String id) throws IOException {
        Path dir = instance(base, id);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        // Do not follow links: a pack may contain links to user data outside its instance.
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
