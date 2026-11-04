package packlauncher;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import packlauncher.common.Json;

/**
 * Login Microsoft per Minecraft "premium" con il device code flow
 * (l'utente apre microsoft.com/link e inserisce un codice: niente browser integrato, niente password nel launcher).
 *
 * Catena: token Microsoft (OAuth) → Xbox Live → XSTS → token Minecraft → profilo.
 * Il refresh token Microsoft viene salvato (vedi {@link Accounts}) così il login si fa una volta sola:
 * quando il token Minecraft (24 h) scade, {@link #ensureValid} lo rinnova in silenzio.
 *
 * Serve un'app registrata su Azure (client id in launcher.properties, chiave msa.clientId) con
 * "Allow public client flows" attivo e approvata da Mojang per l'API di Minecraft (vedi README).
 */
public final class MicrosoftAuth {
    /** Indirizzi dei servizi: configurabili solo per i test. */
    public record Endpoints(String deviceCode, String token, String xbl, String xsts, String mcLogin, String mcProfile) {
        public static final Endpoints LIVE = new Endpoints(
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "https://user.auth.xboxlive.com/user/authenticate",
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                "https://api.minecraftservices.com/minecraft/profile");
    }

    public static final String SCOPE = "XboxLive.signin offline_access";
    /** Il token Minecraft viene rinnovato se scade entro questo margine. */
    static final long REFRESH_MARGIN_S = 15 * 60;

    /** Errore di login. needsLogin = il refresh token non vale più, serve rifare l'accesso. */
    public static final class AuthException extends IOException {
        public final boolean needsLogin;

        public AuthException(String msg, boolean needsLogin) {
            super(msg);
            this.needsLogin = needsLogin;
        }
    }

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int intervalS, long expiresAt) {}

    public record MsTokens(String accessToken, String refreshToken) {}

    /** Attesa tra un controllo e l'altro durante il device code flow (sostituibile nei test). */
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    /** Orologio in secondi (sostituibile nei test). */
    public interface Clock { long nowS(); }

    private final String clientId;
    private final Endpoints ep;
    private final Sleeper sleeper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public MicrosoftAuth(String clientId) {
        this(clientId, Endpoints.LIVE, Thread::sleep, () -> System.currentTimeMillis() / 1000);
    }

    public MicrosoftAuth(String clientId, Endpoints ep, Sleeper sleeper, Clock clock) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Login Microsoft non configurato (manca msa.clientId in launcher.properties)");
        }
        this.clientId = clientId;
        this.ep = ep;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public String clientId() { return clientId; }

    // ------------------------------------------------------------ Microsoft

    public DeviceCode startDeviceCode() throws IOException {
        Map<String, Object> r = postForm(ep.deviceCode(), Map.of("client_id", clientId, "scope", SCOPE), true);
        return new DeviceCode(Json.str(r, "device_code"), Json.str(r, "user_code"),
                Json.str(r, "verification_uri"), (int) Json.num(r, "interval", 5),
                clock.nowS() + Json.num(r, "expires_in", 900));
    }

    /** Aspetta che l'utente inserisca il codice. cancelled: true per interrompere (es. finestra chiusa). */
    public MsTokens awaitDeviceCode(DeviceCode dc, BooleanSupplier cancelled) throws IOException {
        long interval = Math.max(1, dc.intervalS());
        while (true) {
            if (cancelled.getAsBoolean()) throw new AuthException("Accesso annullato", true);
            if (clock.nowS() > dc.expiresAt()) throw new AuthException("Il codice è scaduto: riprova", true);
            try {
                sleeper.sleep(interval * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("Accesso annullato", true);
            }
            if (cancelled.getAsBoolean()) throw new AuthException("Accesso annullato", true);
            Map<String, Object> r = postForm(ep.token(), Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", clientId,
                    "device_code", dc.deviceCode()), false);
            String error = Json.str(r, "error");
            if (error == null) return tokens(r);
            switch (error) {
                case "authorization_pending": break;
                case "slow_down": interval += 5; break;
                case "authorization_declined": throw new AuthException("Accesso rifiutato su Microsoft", true);
                case "expired_token": throw new AuthException("Il codice è scaduto: riprova", true);
                default: throw new AuthException("Errore Microsoft: " + describe(r), true);
            }
        }
    }

    public MsTokens refresh(String refreshToken) throws IOException {
        Map<String, Object> r = postForm(ep.token(), Map.of(
                "grant_type", "refresh_token",
                "client_id", clientId,
                "refresh_token", refreshToken,
                "scope", SCOPE), false);
        String error = Json.str(r, "error");
        if (error != null) {
            // invalid_grant: token revocato, password cambiata, inutilizzato per 90 giorni...
            throw new AuthException("L'accesso Microsoft è scaduto, accedi di nuovo (" + describe(r) + ")",
                    "invalid_grant".equals(error) || "interaction_required".equals(error));
        }
        return tokens(r);
    }

    private static MsTokens tokens(Map<String, Object> r) throws AuthException {
        String at = Json.str(r, "access_token");
        if (at == null) throw new AuthException("Risposta Microsoft senza access_token", true);
        return new MsTokens(at, Json.str(r, "refresh_token"));
    }

    // ------------------------------------------------- Xbox → Minecraft

    /** Da token Microsoft ad account Minecraft completo (token + profilo). */
    public Accounts.Account loginMinecraft(MsTokens ms) throws IOException {
        Map<String, Object> xblReq = new LinkedHashMap<>();
        xblReq.put("Properties", map("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com",
                "RpsTicket", "d=" + ms.accessToken()));
        xblReq.put("RelyingParty", "http://auth.xboxlive.com");
        xblReq.put("TokenType", "JWT");
        Map<String, Object> xbl = postJson(ep.xbl(), xblReq, null);
        String xblToken = Json.str(xbl, "Token");
        if (xblToken == null) throw new AuthException("Accesso Xbox Live fallito", false);

        Map<String, Object> xstsReq = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("SandboxId", "RETAIL");
        props.put("UserTokens", List.of(xblToken));
        xstsReq.put("Properties", props);
        xstsReq.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.put("TokenType", "JWT");
        Map<String, Object> xsts = postJson(ep.xsts(), xstsReq, null);
        long xerr = Json.num(xsts, "XErr", 0);
        if (xerr != 0) throw new AuthException(xboxError(xerr), true);
        String xstsToken = Json.str(xsts, "Token");
        Object xui = Json.arr(Json.obj(xsts, "DisplayClaims"), "xui").stream().findFirst().orElse(null);
        String uhs = Json.str(xui, "uhs");
        String xuid = Json.str(xui, "xid");
        if (xstsToken == null || uhs == null) throw new AuthException("Accesso Xbox (XSTS) fallito", false);

        Map<String, Object> mc = postJson(ep.mcLogin(), map("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken), null);
        String mcToken = Json.str(mc, "access_token");
        if (mcToken == null) {
            throw new AuthException("Minecraft ha rifiutato l'accesso (" + describe(mc) + "). "
                    + "Se dice \"Invalid app registration\", l'app Azure non è ancora approvata da Mojang.", false);
        }
        long expiresAt = clock.nowS() + Json.num(mc, "expires_in", 86400);

        Map<String, Object> profile = get(ep.mcProfile(), mcToken);
        String id = Json.str(profile, "id");
        String name = Json.str(profile, "name");
        if (id == null || name == null) {
            throw new AuthException("Questo account Microsoft non possiede Minecraft Java Edition", true);
        }
        return new Accounts.Account(name, id.replace("-", ""), xuid != null ? xuid : "0",
                mcToken, expiresAt, ms.refreshToken());
    }

    /** Restituisce l'account con un token Minecraft valido, rinnovandolo se sta per scadere. */
    public Accounts.Account ensureValid(Accounts.Account acc) throws IOException {
        if (acc.mcExpiresAt() - REFRESH_MARGIN_S > clock.nowS()) return acc;
        if (acc.refreshToken() == null) throw new AuthException("L'accesso Microsoft è scaduto, accedi di nuovo", true);
        MsTokens ms = refresh(acc.refreshToken());
        // Microsoft può non restituire un nuovo refresh token: in quel caso si tiene il vecchio.
        if (ms.refreshToken() == null) ms = new MsTokens(ms.accessToken(), acc.refreshToken());
        return loginMinecraft(ms);
    }

    static String xboxError(long xerr) {
        if (xerr == 2148916233L) return "Questo account Microsoft non ha un profilo Xbox: crealo su xbox.com e riprova";
        if (xerr == 2148916235L) return "Xbox Live non è disponibile nel tuo paese";
        if (xerr == 2148916236L || xerr == 2148916237L) return "L'account richiede la verifica dell'età su xbox.com";
        if (xerr == 2148916238L) return "Account minorenne: deve essere aggiunto a una Famiglia Microsoft da un adulto";
        return "Accesso Xbox fallito (errore " + xerr + ")";
    }

    // ----------------------------------------------------------------- HTTP

    private Map<String, Object> postForm(String url, Map<String, String> form, boolean failOnError) throws IOException {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(req, failOnError);
    }

    private Map<String, Object> postJson(String url, Object json, String bearer) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(json)));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return send(b.build(), false);
    }

    private Map<String, Object> get(String url, String bearer) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30)).GET().build();
        return send(req, false);
    }

    /** Le risposte di errore (4xx) dei servizi Microsoft hanno comunque un corpo JSON utile. */
    private Map<String, Object> send(HttpRequest req, boolean failOnError) throws IOException {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            throw Downloader.interrupted(e);
        }
        int code = res.statusCode();
        String text = res.body() == null ? "" : res.body().trim();
        if (code >= 500 || (failOnError && code >= 400)) {
            throw new IOException("HTTP " + code + " da " + req.uri().getHost() + (text.isEmpty() ? "" : ": " + text));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!text.isEmpty()) {
            try {
                Object v = Json.parse(text);
                if (v instanceof Map<?, ?> m) m.forEach((k, val) -> out.put(k.toString(), val));
            } catch (RuntimeException e) {
                out.put("error", "http_" + code);
            }
        }
        if (code >= 400 && !out.containsKey("error") && !out.containsKey("XErr")) out.put("error", "http_" + code);
        return out;
    }

    private static String describe(Map<String, Object> r) {
        String d = Json.str(r, "error_description");
        if (d == null) d = Json.str(r, "errorMessage");
        String e = Json.str(r, "error");
        return d != null ? d : e != null ? e : r.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
