package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import packlauncher.common.Json;

class MicrosoftAuthTest {
    private FakeMicrosoft ms;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<Long> sleeps = new ArrayList<>();
    private MicrosoftAuth auth;

    @BeforeEach
    void setUp() throws IOException {
        ms = new FakeMicrosoft();
        // il tempo avanza solo quando il flusso "dorme": i test non aspettano davvero
        auth = new MicrosoftAuth("client-123", ms.endpoints(), millis -> {
            sleeps.add(millis);
            now.addAndGet(millis / 1000);
        }, now::get);
    }

    @AfterEach
    void tearDown() { ms.close(); }

    @Test
    void loginCompletoConDeviceCode() throws IOException {
        ms.pollErrors.addAll(List.of("authorization_pending", "slow_down", "authorization_pending"));
        MicrosoftAuth.DeviceCode dc = auth.startDeviceCode();
        assertEquals("ABCD-EFGH", dc.userCode());
        assertEquals("https://www.microsoft.com/link", dc.verificationUri());
        assertTrue(ms.requests("/devicecode").get(0).body().contains("scope=XboxLive.signin+offline_access"));

        MicrosoftAuth.MsTokens t = auth.awaitDeviceCode(dc, () -> false);
        assertEquals("MS-AT-1", t.accessToken());
        assertEquals("RT-1", t.refreshToken());
        assertEquals(4, ms.polls.get());
        assertEquals(List.of(5000L, 5000L, 10000L, 10000L), sleeps, "slow_down allunga l'intervallo di 5 s");

        Accounts.Account a = auth.loginMinecraft(t);
        assertEquals("Notch", a.name());
        assertEquals("069a79f444e94726a5befca90e38aaf5", a.uuid());
        assertEquals("2535400000", a.xuid());
        assertEquals("MC-TOKEN-1", a.mcAccessToken());
        assertEquals(now.get() + 86400, a.mcExpiresAt());
        assertEquals("RT-1", a.refreshToken());

        // la catena passa i token giusti a ogni passo
        Map<String, Object> xbl = Json.parseObject(ms.requests("/xbl").get(0).body());
        assertEquals("d=MS-AT-1", Json.str(Json.obj(xbl, "Properties"), "RpsTicket"));
        Map<String, Object> xsts = Json.parseObject(ms.requests("/xsts").get(0).body());
        assertEquals(List.of("XBL-TOKEN"), Json.arr(Json.obj(xsts, "Properties"), "UserTokens"));
        assertEquals("rp://api.minecraftservices.com/", Json.str(xsts, "RelyingParty"));
        Map<String, Object> mc = Json.parseObject(ms.requests("/mclogin").get(0).body());
        assertEquals("XBL3.0 x=UHS1;XSTS-TOKEN", Json.str(mc, "identityToken"));
        assertEquals("Bearer MC-TOKEN-1", ms.requests("/profile").get(0).authorization());
    }

    @Test
    void codiceRifiutatoOScaduto() throws IOException {
        ms.pollErrors.add("authorization_declined");
        MicrosoftAuth.DeviceCode dc = auth.startDeviceCode();
        MicrosoftAuth.AuthException e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.awaitDeviceCode(dc, () -> false));
        assertTrue(e.getMessage().contains("rifiutato"));

        ms.pollErrors.clear();
        ms.polls.set(0);
        for (int i = 0; i < 500; i++) ms.pollErrors.add("authorization_pending");
        MicrosoftAuth.DeviceCode dc2 = auth.startDeviceCode();
        e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.awaitDeviceCode(dc2, () -> false));
        assertTrue(e.getMessage().contains("scaduto"), e.getMessage());
    }

    @Test
    void annullaInterrompeIlPolling() throws IOException {
        MicrosoftAuth.DeviceCode dc = auth.startDeviceCode();
        assertThrows(MicrosoftAuth.AuthException.class, () -> auth.awaitDeviceCode(dc, () -> true));
        assertEquals(0, ms.polls.get());
    }

    @Test
    void tokenAncoraValidoNonVieneRinnovato() throws IOException {
        Accounts.Account a = new Accounts.Account("Notch", "uuid", "x", "MC-OLD", now.get() + 3600, "RT-1");
        assertSame(a, auth.ensureValid(a));
        assertTrue(ms.requests.isEmpty(), "nessuna chiamata di rete se il token è valido");
    }

    @Test
    void tokenScadutoVieneRinnovatoConIlRefreshToken() throws IOException {
        Accounts.Account a = new Accounts.Account("Notch", "uuid", "x", "MC-OLD", now.get() - 10, "RT-1");
        Accounts.Account fresh = auth.ensureValid(a);
        assertEquals("MC-TOKEN-1", fresh.mcAccessToken());
        assertEquals("RT-REFRESHED", fresh.refreshToken(), "Microsoft ruota il refresh token");
        assertTrue(fresh.mcExpiresAt() > now.get());
        assertTrue(ms.requests("/token").get(0).body().contains("refresh_token=RT-1"));
    }

    @Test
    void tokenInScadenzaVieneRinnovato() throws IOException {
        Accounts.Account a = new Accounts.Account("Notch", "uuid", "x", "MC-OLD", now.get() + 60, "RT-1");
        assertNotSame(a, auth.ensureValid(a));
    }

    @Test
    void refreshTokenNonRuotatoRestaQuelloVecchio() throws IOException {
        ms.rotateRefreshToken = false;
        Accounts.Account a = new Accounts.Account("Notch", "uuid", "x", "MC-OLD", 0, "RT-1");
        assertEquals("RT-1", auth.ensureValid(a).refreshToken());
    }

    @Test
    void refreshTokenRevocatoChiedeDiRifareLAccesso() {
        ms.refreshError = "invalid_grant";
        Accounts.Account a = new Accounts.Account("Notch", "uuid", "x", "MC-OLD", 0, "RT-1");
        MicrosoftAuth.AuthException e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.ensureValid(a));
        assertTrue(e.needsLogin);
    }

    @Test
    void accountSenzaXbox() {
        ms.xstsError = 2148916233L;
        MicrosoftAuth.AuthException e = assertThrows(MicrosoftAuth.AuthException.class,
                () -> auth.loginMinecraft(new MicrosoftAuth.MsTokens("MS-AT-1", "RT-1")));
        assertTrue(e.getMessage().contains("Xbox"), e.getMessage());
    }

    @Test
    void accountSenzaMinecraft() {
        ms.ownsGame = false;
        MicrosoftAuth.AuthException e = assertThrows(MicrosoftAuth.AuthException.class,
                () -> auth.loginMinecraft(new MicrosoftAuth.MsTokens("MS-AT-1", "RT-1")));
        assertTrue(e.getMessage().contains("non possiede Minecraft"), e.getMessage());
    }

    @Test
    void appAzureNonApprovataDaMojang() {
        ms.mcLoginError = "Invalid app registration, see https://aka.ms/AppRegInfo for more information";
        MicrosoftAuth.AuthException e = assertThrows(MicrosoftAuth.AuthException.class,
                () -> auth.loginMinecraft(new MicrosoftAuth.MsTokens("MS-AT-1", "RT-1")));
        assertTrue(e.getMessage().contains("Invalid app registration"), e.getMessage());
        assertFalse(e.needsLogin);
    }

    @Test
    void senzaClientIdIlLoginNonEDisponibile() {
        assertThrows(IllegalStateException.class, () -> new MicrosoftAuth(""));
    }
}
