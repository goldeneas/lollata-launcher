package packlauncher;

/**
 * Chi può entrare in quale server.
 * Un account premium entra ovunque; un nickname offline solo sui server con authMode "offline"
 * (o senza authMode, per compatibilità con i pack pubblicati prima di questo campo).
 */
public final class AccessPolicy {
    private AccessPolicy() {}

    public static boolean requiresPremium(String authMode) {
        return Pack.AUTH_PREMIUM.equals(authMode);
    }

    public static boolean canJoin(String authMode, boolean premiumAccount) {
        return premiumAccount || !requiresPremium(authMode);
    }

    /** null se l'accesso è consentito, altrimenti il messaggio da mostrare. */
    public static String denyReason(String authMode, boolean premiumAccount, String serverName) {
        if (canJoin(authMode, premiumAccount)) return null;
        return (serverName != null ? serverName : "Questo server")
                + " richiede un account Minecraft originale (premium).\n"
                + "Accedi con Microsoft per giocare: l'accesso con solo nickname non è consentito.";
    }
}
