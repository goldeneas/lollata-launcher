package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import packlauncher.common.Minecraft;

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
        assertTrue(AccessPolicy.canJoin(Minecraft.PREMIUM, true));
        assertTrue(AccessPolicy.canJoin(Minecraft.OFFLINE, true));
        assertTrue(AccessPolicy.canJoin(null, true));
    }

    @Test
    void offlineSoloSuiServerOffline() {
        assertFalse(AccessPolicy.canJoin(Minecraft.PREMIUM, false));
        assertTrue(AccessPolicy.canJoin(Minecraft.OFFLINE, false));
        assertTrue(AccessPolicy.canJoin(null, false), "authMode sconosciuto = offline (pack vecchi)");
        assertNull(AccessPolicy.denyReason(Minecraft.OFFLINE, false, "X"));
        assertTrue(AccessPolicy.denyReason(Minecraft.PREMIUM, false, "ATM10").startsWith("ATM10 richiede"));
    }

    @Test
    void launchFlowBloccaIlNicknameSuUnServerPremium() {
        IOException e = assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Minecraft.PREMIUM), OFFLINE));
        assertTrue(e.getMessage().contains("premium"));
    }

    @Test
    void launchFlowLasciaPassareGliAccountGiusti() throws IOException {
        LaunchFlow.checkAccess(pack(Minecraft.PREMIUM), PREMIUM);
        LaunchFlow.checkAccess(pack(Minecraft.OFFLINE), PREMIUM);
        LaunchFlow.checkAccess(pack(Minecraft.OFFLINE), OFFLINE);
        LaunchFlow.checkAccess(pack(null), OFFLINE);
    }

    @Test
    void nicknameNonValido() {
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Minecraft.OFFLINE), Session.offline("a")));
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Minecraft.OFFLINE), Session.offline(null)));
        assertThrows(IOException.class, () -> LaunchFlow.checkAccess(pack(Minecraft.OFFLINE), Session.offline("nome con spazi")));
    }

    @Test
    void sessioneOfflineUsaLUuidDelServer() {
        assertFalse(OFFLINE.premium());
        assertEquals("legacy", OFFLINE.userType());
        assertEquals(Minecraft.offlineUuid("Steve").toString().replace("-", ""), OFFLINE.uuid());
        assertEquals("0", OFFLINE.accessToken());
    }
}
