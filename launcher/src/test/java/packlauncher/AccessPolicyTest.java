package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class AccessPolicyTest {
    private static Pack pack(String authMode) {
        Pack p = new Pack();
        p.id = "test";
        p.name = "Server di prova";
        p.minecraft = "1.21.1";
        p.authMode = authMode;
        return p;
    }

    private static final Session PREMIUM = new Session("Notch", "069a79f444e94726a5befca90e38aaf5", "TOKEN", true, "1", "c");
    private static final Session OFFLINE = Session.offline("Steve");

    @Test
    void premiumEntraOvunque() {
        assertTrue(AccessPolicy.canJoin(Pack.AUTH_PREMIUM, true));
        assertTrue(AccessPolicy.canJoin(Pack.AUTH_OFFLINE, true));
        assertTrue(AccessPolicy.canJoin(null, true));
    }

    @Test
    void offlineSoloSuiServerOffline() {
        assertFalse(AccessPolicy.canJoin(Pack.AUTH_PREMIUM, false));
        assertTrue(AccessPolicy.canJoin(Pack.AUTH_OFFLINE, false));
        assertTrue(AccessPolicy.canJoin(null, false), "authMode sconosciuto = offline (pack vecchi)");
        assertNull(AccessPolicy.denyReason(Pack.AUTH_OFFLINE, false, "X"));
        assertTrue(AccessPolicy.denyReason(Pack.AUTH_PREMIUM, false, "ATM10").startsWith("ATM10 richiede"));
    }

    @Test
    void launchFlowBloccaIlNicknameSuUnServerPremium() {
        IOException e = assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Pack.AUTH_PREMIUM), OFFLINE));
        assertTrue(e.getMessage().contains("premium"));
    }

    @Test
    void launchFlowLasciaPassareGliAccountGiusti() throws IOException {
        LaunchFlow.checkAccess(pack(Pack.AUTH_PREMIUM), PREMIUM);
        LaunchFlow.checkAccess(pack(Pack.AUTH_OFFLINE), PREMIUM);
        LaunchFlow.checkAccess(pack(Pack.AUTH_OFFLINE), OFFLINE);
        LaunchFlow.checkAccess(pack(null), OFFLINE);
    }

    @Test
    void nicknameNonValido() {
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Pack.AUTH_OFFLINE), Session.offline("a")));
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Pack.AUTH_OFFLINE), Session.offline(null)));
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Pack.AUTH_OFFLINE), Session.offline("nome con spazi")));
    }

    @Test
    void sessioneOfflineUsaLUuidDelServer() {
        assertFalse(OFFLINE.premium());
        assertEquals("legacy", OFFLINE.userType());
        assertEquals(GameLauncher.offlineUuid("Steve").toString().replace("-", ""), OFFLINE.uuid());
        assertEquals("0", OFFLINE.accessToken());
    }
}
