package packlauncher;

/**
 * Identità con cui avviare il gioco.
 *
 * @param premium     true per un account Microsoft (online-mode), false per un nickname offline
 * @param uuid        UUID senza trattini
 * @param accessToken token Minecraft (offline: "0")
 * @param xuid        id Xbox (offline: "0")
 * @param clientId    client id Azure del launcher (offline: "0")
 */
public record Session(String name, String uuid, String accessToken, boolean premium, String xuid, String clientId) {

    public static Session offline(String name) {
        if (name == null) name = "";
        return new Session(name, GameLauncher.offlineUuid(name).toString().replace("-", ""), "0", false, "0", "0");
    }

    /** Valore di ${user_type} negli argomenti del gioco. */
    public String userType() { return premium ? "msa" : "legacy"; }
}
