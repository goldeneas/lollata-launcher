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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import packlauncher.common.Hashing;

/** Download HTTP con verifica SHA-1, retry e parallelismo. */
public final class Downloader {
    /** Download contemporanei: ognuno usa una propria connessione keep-alive. */
    private static final int THREADS = 16;
    /** Controlli (hash) dei file già presenti, in parallelo. */
    private static final int CHECK_THREADS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int RETRIES = 3;
    /** Intervallo minimo tra due aggiornamenti della barra di avanzamento. */
    private static final long PROGRESS_INTERVAL_NS = 100_000_000L;

    /*
     * HTTP/1.1 invece di HTTP/2: con HTTP/2 il client Java mette tutti i download
     * verso lo stesso host su UNA sola connessione TCP, che su linee domestiche
     * (e dietro al reverse proxy) limita parecchio la velocità. Con HTTP/1.1 ogni
     * thread ha la sua connessione, riusata dal pool per i file successivi.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
        // 1. Quali file mancano? Il controllo dell'hash dei file già presenti
        //    (es. librerie) gira in parallelo invece che uno alla volta.
        List<Task> todo = new ArrayList<>();
        ExecutorService checkers = Executors.newFixedThreadPool(Math.min(CHECK_THREADS, Math.max(1, tasks.size())));
        try {
            List<Callable<Boolean>> checks = new ArrayList<>();
            for (Task t : tasks) checks.add(() -> isValid(t.dest(), t.sha1(), t.size()));
            List<Future<Boolean>> results = checkers.invokeAll(checks);
            for (int i = 0; i < tasks.size(); i++) {
                if (!get(results.get(i))) todo.add(tasks.get(i));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        } finally {
            checkers.shutdownNow();
        }
        if (todo.isEmpty()) return;

        // 2. Prima i file grandi: così alla fine non resta un solo thread
        //    a scaricare l'ultima mod da 50 MB mentre gli altri sono fermi.
        todo.sort((a, b) -> Long.compare(b.size(), a.size()));
        long total = 0;
        for (Task t : todo) total += Math.max(t.size(), 0);
        progress.log("Da scaricare: " + todo.size() + " file (" + mb(total) + " MB)");

        final long grandTotal = total;
        AtomicLong done = new AtomicLong();
        AtomicLong lastReport = new AtomicLong();
        java.util.function.LongConsumer onBytes = n -> {
            long d = done.addAndGet(n);
            long now = System.nanoTime();
            long prev = lastReport.get();
            if (now - prev >= PROGRESS_INTERVAL_NS && lastReport.compareAndSet(prev, now)) {
                progress.progress(d, grandTotal);
            }
        };

        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, todo.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Task t : todo) {
                futures.add(pool.submit(() -> {
                    downloadOne(t, onBytes);
                    return null;
                }));
            }
            for (Future<?> f : futures) get(f);
        } finally {
            pool.shutdownNow();
        }
        progress.progress(grandTotal, grandTotal);

        double secs = Math.max((System.nanoTime() - start) / 1e9, 0.001);
        long bytes = done.get();
        progress.log(String.format("Scaricati %d file, %d MB in %.1f s (%.1f MB/s)",
                todo.size(), mb(bytes), secs, bytes / 1024.0 / 1024.0 / secs));
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
                // L'hash si calcola mentre il file arriva: niente seconda lettura dal disco.
                MessageDigest md = sha1Digest();
                try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        md.update(buf, 0, r);
                        counted += r;
                        if (t.size() > 0 && onBytes != null) onBytes.accept(r);
                    }
                }
                if (t.size() >= 0 && counted != t.size()) {
                    throw new IOException("Dimensione errata per " + t.url() + " (" + counted + " invece di " + t.size() + ")");
                }
                if (t.sha1() != null && !t.sha1().equalsIgnoreCase(HexFormat.of().formatHex(md.digest()))) {
                    throw new IOException("Hash non valido per " + t.url());
                }
                Files.move(part, t.dest(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrotto", e);
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(part);
                if (t.size() > 0 && onBytes != null) onBytes.accept(-counted);
                if (attempt < RETRIES) pause(500L * attempt);
            }
        }
        throw last;
    }

    private static <T> T get(Future<T> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw c instanceof IOException io ? io : new IOException(c.getMessage(), c);
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        }
    }

    private static long mb(long bytes) { return bytes / 1024 / 1024; }
}
