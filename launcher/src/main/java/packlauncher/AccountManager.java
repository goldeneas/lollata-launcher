package packlauncher;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Account Microsoft salvato + login/refresh: quello che usano interfaccia e riga di comando. */
public final class AccountManager {
    private final Accounts store;
    private final MicrosoftAuth auth;   // null se il login Microsoft non è configurato
    private Accounts.Account current;

    public AccountManager(Accounts store, MicrosoftAuth auth) {
        this.store = store;
        this.auth = auth;
        this.current = store.load();
    }

    public static AccountManager create(Config cfg) {
        MicrosoftAuth auth = cfg.msaClientId.isEmpty() ? null : new MicrosoftAuth(cfg.msaClientId);
        return new AccountManager(new Accounts(cfg.baseDir), auth);
    }

    public boolean premiumAvailable() { return auth != null; }

    /** L'account premium salvato (anche con token scaduto: si rinnova in {@link #premiumSession}). */
    public synchronized Accounts.Account current() { return current; }

    /** Device code flow completo. showCode riceve il codice da far inserire all'utente. */
    public Accounts.Account login(Consumer<MicrosoftAuth.DeviceCode> showCode, BooleanSupplier cancelled)
            throws IOException {
        requireAuth();
        MicrosoftAuth.DeviceCode dc = auth.startDeviceCode();
        showCode.accept(dc);
        Accounts.Account acc = auth.loginMinecraft(auth.awaitDeviceCode(dc, cancelled));
        store.save(acc);
        synchronized (this) { current = acc; }
        return acc;
    }

    /**
     * Sessione premium pronta per il gioco: rinnova il token se serve e salva il risultato.
     * Se il refresh token non vale più l'account viene dimenticato (needsLogin).
     */
    public Session premiumSession() throws IOException {
        requireAuth();
        Accounts.Account acc = current();
        if (acc == null) throw new MicrosoftAuth.AuthException("Nessun account Microsoft: accedi prima", true);
        Accounts.Account fresh;
        try {
            fresh = auth.ensureValid(acc);
        } catch (MicrosoftAuth.AuthException e) {
            if (e.needsLogin) logout();
            throw e;
        }
        if (fresh != acc) {
            store.save(fresh);
            synchronized (this) { current = fresh; }
        }
        return fresh.session(auth.clientId());
    }

    public void logout() throws IOException {
        store.clear();
        synchronized (this) { current = null; }
    }

    private void requireAuth() {
        if (auth == null) {
            throw new IllegalStateException("Login Microsoft non disponibile in questa versione del launcher "
                    + "(manca msa.clientId in launcher.properties)");
        }
    }
}
