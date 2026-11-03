package packlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import packlauncher.common.Hashing;

/** Download HTTP con verifica SHA-1, retry e parallelismo. */
public final class Downloader {
    private static final int THREADS = 8;
    private static final int RETRIES = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final String userAgent;

    public Downloader(String userAgent) { this.userAgent = userAgent; }

    /** Un file da scaricare. sha1 e size possono essere null / -1. */
    public record Task(String url, Path dest, String sha1, long size) {}

    public String getString(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " per " + url);
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        }
    }

    /** true se il file esiste già e corrisponde (hash, o almeno dimensione se l'hash manca). */
    public static boolean isValid(Path dest, String sha1, long size) throws IOException {
        if (!Files.isRegularFile(dest)) return false;
        if (size >= 0 && Files.size(dest) != size) return false;
        return sha1 == null || sha1.equalsIgnoreCase(Hashing.sha1(dest));
    }

    /** Scarica tutti i task non già presenti, in parallelo. */
    public void downloadAll(List<Task> tasks, Progress progress) throws IOException {
        List<Task> todo = new ArrayList<>();
        long total = 0;
        for (Task t : tasks) {
            if (!isValid(t.dest(), t.sha1(), t.size())) {
                todo.add(t);
                total += Math.max(t.size(), 0);
            }
        }
        if (todo.isEmpty()) return;
        progress.log("Da scaricare: " + todo.size() + " file (" + (total / 1024 / 1024) + " MB)");

        final long grandTotal = total;
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Task t : todo) {
                futures.add(pool.submit(() -> {
                    downloadOne(t, n -> progress.progress(done.addAndGet(n), grandTotal));
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    throw c instanceof IOException io ? io : new IOException(c.getMessage(), c);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    public void downloadOne(Task t, java.util.function.LongConsumer onBytes) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            long counted = 0;
            Path part = t.dest().resolveSibling(t.dest().getFileName() + ".part");
            try {
                Files.createDirectories(t.dest().getParent());
                HttpRequest req = HttpRequest.newBuilder(URI.create(t.url()))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofMinutes(5)).GET().build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    res.body().close();
                    throw new IOException("HTTP " + res.statusCode() + " per " + t.url());
                }
                try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        counted += r;
                        if (t.size() > 0 && onBytes != null) onBytes.accept(r);
                    }
                }
                if (!isValid(part, t.sha1(), t.size())) throw new IOException("Hash non valido per " + t.url());
                Files.move(part, t.dest(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrotto", e);
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(part);
                if (t.size() > 0 && onBytes != null) onBytes.accept(-counted);
            }
        }
        throw last;
    }
}
