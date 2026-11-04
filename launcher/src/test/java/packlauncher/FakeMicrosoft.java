package packlauncher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Finti servizi Microsoft / Xbox / Minecraft per testare {@link MicrosoftAuth} senza rete. */
final class FakeMicrosoft implements AutoCloseable {
    record Request(String path, String body, String authorization) {}

    final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Risposte del polling del device code, in ordine (l'ultima si ripete). */
    final List<String> pollErrors = new CopyOnWriteArrayList<>();
    final AtomicInteger polls = new AtomicInteger();
    volatile String refreshError;        // es. "invalid_grant"
    volatile boolean rotateRefreshToken = true;
    volatile long xstsError;             // XErr, 0 = nessun errore
    volatile boolean ownsGame = true;
    volatile String mcLoginError;        // es. "Invalid app registration"
    private final AtomicInteger issued = new AtomicInteger();

    private final HttpServer server;

    FakeMicrosoft() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    MicrosoftAuth.Endpoints endpoints() {
        String b = "http://127.0.0.1:" + server.getAddress().getPort();
        return new MicrosoftAuth.Endpoints(b + "/devicecode", b + "/token", b + "/xbl", b + "/xsts",
                b + "/mclogin", b + "/profile");
    }

    List<Request> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(path, body, ex.getRequestHeaders().getFirst("Authorization")));
        Map<String, String> form = form(body);
        switch (path) {
            case "/devicecode" -> reply(ex, 200, "{\"device_code\":\"DEV123\",\"user_code\":\"ABCD-EFGH\","
                    + "\"verification_uri\":\"https://www.microsoft.com/link\",\"expires_in\":900,\"interval\":5,"
                    + "\"message\":\"...\"}");
            case "/token" -> token(ex, form);
            case "/xbl" -> reply(ex, 200, "{\"Token\":\"XBL-TOKEN\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"UHS1\"}]}}");
            case "/xsts" -> {
                if (xstsError != 0) reply(ex, 401, "{\"Identity\":\"0\",\"XErr\":" + xstsError + ",\"Message\":\"\"}");
                else reply(ex, 200, "{\"Token\":\"XSTS-TOKEN\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"UHS1\",\"xid\":\"2535400000\"}]}}");
            }
            case "/mclogin" -> {
                if (mcLoginError != null) reply(ex, 403, "{\"path\":\"/authentication/login_with_xbox\","
                        + "\"errorMessage\":\"" + mcLoginError + "\"}");
                else reply(ex, 200, "{\"username\":\"x\",\"access_token\":\"MC-TOKEN-" + issued.incrementAndGet()
                        + "\",\"token_type\":\"Bearer\",\"expires_in\":86400}");
            }
            case "/profile" -> {
                if (ownsGame) reply(ex, 200, "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"skins\":[]}");
                else reply(ex, 404, "{\"path\":\"/minecraft/profile\",\"errorType\":\"NOT_FOUND\",\"error\":\"NOT_FOUND\"}");
            }
            default -> reply(ex, 404, "{}");
        }
    }

    private void token(HttpExchange ex, Map<String, String> form) throws IOException {
        if ("refresh_token".equals(form.get("grant_type"))) {
            if (refreshError != null) {
                reply(ex, 400, "{\"error\":\"" + refreshError + "\",\"error_description\":\"AADSTS70000: token scaduto\"}");
                return;
            }
            String rt = rotateRefreshToken ? ",\"refresh_token\":\"RT-REFRESHED\"" : "";
            reply(ex, 200, "{\"token_type\":\"Bearer\",\"access_token\":\"MS-AT-2\"" + rt + ",\"expires_in\":3600}");
            return;
        }
        int n = polls.getAndIncrement();
        String err = n < pollErrors.size() ? pollErrors.get(n) : null;
        if (err != null) reply(ex, 400, "{\"error\":\"" + err + "\"}");
        else reply(ex, 200, "{\"token_type\":\"Bearer\",\"access_token\":\"MS-AT-1\",\"refresh_token\":\"RT-1\",\"expires_in\":3600}");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body.isEmpty() || body.startsWith("{")) return m;
        for (String kv : body.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }

    @Override public void close() { server.stop(0); }
}
