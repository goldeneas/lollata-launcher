package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountsTest {
    @TempDir Path dir;

    private static Accounts.Account account(long expiresAt) {
        return new Accounts.Account("Notch", "069a79f444e94726a5befca90e38aaf5", "2535400000",
                "MC-TOKEN", expiresAt, "RT-1");
    }

    @Test
    void salvaERicaricaLAccount() throws IOException {
        Accounts store = new Accounts(dir);
        assertNull(store.load(), "nessun account al primo avvio");
        store.save(account(1234));
        // un nuovo Accounts = riapertura del launcher
        assertEquals(account(1234), new Accounts(dir).load());
    }

    @Test
    void fileLeggibileSoloDallUtente() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        Accounts store = new Accounts(dir);
        store.save(account(1));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
    }

    @Test
    void fileRovinatoEquivaleANessunAccount() throws IOException {
        Files.writeString(dir.resolve("accounts.json"), "{ non è json");
        assertNull(new Accounts(dir).load());
        Files.writeString(dir.resolve("accounts.json"), "{\"formatVersion\":1}");
        assertNull(new Accounts(dir).load());
    }

    @Test
    void esciCancellaLAccount() throws IOException {
        Accounts store = new Accounts(dir);
        store.save(account(1));
        store.clear();
        assertNull(store.load());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void sessionePremium() {
        Session s = account(1).session("client-123");
        assertTrue(s.premium());
        assertEquals("msa", s.userType());
        assertEquals("Notch", s.name());
        assertEquals("MC-TOKEN", s.accessToken());
        assertEquals("2535400000", s.xuid());
        assertEquals("client-123", s.clientId());
    }

    @Test
    void managerRinnovaESalvaIlTokenScaduto() throws IOException {
        try (FakeMicrosoft ms = new FakeMicrosoft()) {
            AtomicLong now = new AtomicLong(1_000_000);
            MicrosoftAuth auth = new MicrosoftAuth("client-123", ms.endpoints(), m -> {}, now::get);
            Accounts store = new Accounts(dir);
            store.save(account(now.get() - 1));

            AccountManager m = new AccountManager(store, auth);
            Session s = m.premiumSession();
            assertEquals("MC-TOKEN-1", s.accessToken());
            // il token nuovo è su disco: al prossimo avvio non serve un altro refresh
            Accounts.Account saved = new Accounts(dir).load();
            assertEquals("MC-TOKEN-1", saved.mcAccessToken());
            assertEquals("RT-REFRESHED", saved.refreshToken());

            int calls = ms.requests.size();
            assertEquals("MC-TOKEN-1", new AccountManager(new Accounts(dir), auth).premiumSession().accessToken());
            assertEquals(calls, ms.requests.size(), "token ancora valido: nessuna chiamata");
        }
    }

    @Test
    void managerDimenticaLAccountSeIlRefreshNonValePiu() throws IOException {
        try (FakeMicrosoft ms = new FakeMicrosoft()) {
            ms.refreshError = "invalid_grant";
            MicrosoftAuth auth = new MicrosoftAuth("client-123", ms.endpoints(), m -> {}, () -> 1_000_000);
            Accounts store = new Accounts(dir);
            store.save(account(0));
            AccountManager m = new AccountManager(store, auth);
            assertThrows(MicrosoftAuth.AuthException.class, m::premiumSession);
            assertNull(m.current());
            assertNull(store.load());
        }
    }

    @Test
    void managerLoginSalvaLAccount() throws IOException {
        try (FakeMicrosoft ms = new FakeMicrosoft()) {
            MicrosoftAuth auth = new MicrosoftAuth("client-123", ms.endpoints(), m -> {}, () -> 1_000_000);
            AccountManager m = new AccountManager(new Accounts(dir), auth);
            String[] shown = new String[1];
            Accounts.Account a = m.login(dc -> shown[0] = dc.userCode(), () -> false);
            assertEquals("ABCD-EFGH", shown[0]);
            assertEquals("Notch", a.name());
            assertEquals(a, new Accounts(dir).load());
        }
    }

    @Test
    void managerSenzaLoginMicrosoftConfigurato() {
        AccountManager m = new AccountManager(new Accounts(dir), null);
        assertFalse(m.premiumAvailable());
        assertThrows(IllegalStateException.class, m::premiumSession);
    }
}
