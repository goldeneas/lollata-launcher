package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import packlauncher.common.Minecraft;

class PackTest {
    private static final String INDEX_URL = "https://mcfiles.example/packs/index.json";

    @Test
    void indexV2ConDatiDelServer() {
        String json = """
                { "formatVersion": 2, "packs": [
                  { "id": "atm10a", "name": "ATM10 Aeronautics", "url": "atm10a/pack.json",
                    "version": "0.7.1", "minecraft": "1.21.1",
                    "loader": { "type": "neoforge", "version": "21.1.228" },
                    "server": "mc.example:25565", "authMode": "premium" },
                  { "id": "vanilla", "name": "Vanilla", "url": "https://altro.example/v/pack.json",
                    "minecraft": "1.20.1", "loader": { "type": "vanilla" }, "authMode": "OFFLINE" } ] }
                """;
        List<Pack.Entry> e = Pack.parseIndex(json, INDEX_URL);
        assertEquals(2, e.size());

        Pack.Entry a = e.get(0);
        assertEquals("atm10a", a.id());
        assertEquals("https://mcfiles.example/packs/atm10a/pack.json", a.url());
        assertEquals("1.21.1", a.minecraft());
        assertEquals("neoforge", a.loaderType());
        assertEquals("21.1.228", a.loaderVersion());
        assertEquals("mc.example:25565", a.server());
        assertEquals(Minecraft.PREMIUM, a.authMode());
        assertEquals("Minecraft 1.21.1 - neoforge 21.1.228", a.summary());

        Pack.Entry b = e.get(1);
        assertEquals("https://altro.example/v/pack.json", b.url(), "url assoluti restano invariati");
        assertEquals(Minecraft.OFFLINE, b.authMode(), "authMode non sensibile a maiuscole");
        assertEquals("Minecraft 1.20.1", b.summary(), "vanilla non mostra il loader");
    }

    @Test
    void indexV1VecchioFormatoFunzionaAncora() {
        String json = """
                { "packs": [ { "id": "atm10a", "name": "ATM10", "url": "atm10a/pack.json" } ] }
                """;
        Pack.Entry e = Pack.parseIndex(json, INDEX_URL).get(0);
        assertEquals("atm10a", e.id());
        assertNull(e.minecraft());
        assertNull(e.authMode());
        assertEquals("", e.summary());
    }

    @Test
    void indexScartaVociConIdPericolosoOSenzaUrl() {
        String json = """
                { "packs": [ { "id": "../../etc", "url": "x/pack.json" },
                             { "id": "senzaurl" },
                             { "id": "ok", "url": "ok/pack.json" } ] }
                """;
        List<Pack.Entry> e = Pack.parseIndex(json, INDEX_URL);
        assertEquals(1, e.size());
        assertEquals("ok", e.get(0).id());
        assertEquals("ok", e.get(0).name(), "senza nome si usa l'id");
    }

    @Test
    void packJsonConAuthMode() {
        Pack p = Pack.parse("""
                { "id": "atm10a", "name": "ATM10", "version": "1", "minecraft": "1.21.1",
                  "loader": { "type": "neoforge", "version": "21.1.228" },
                  "server": "mc.example:25565", "authMode": "premium", "memoryMb": 6144,
                  "files": [ { "path": "mods/a.jar", "sha1": "abcdef", "size": 3 } ] }
                """, "https://mcfiles.example/packs/atm10a/pack.json");
        assertEquals(Minecraft.PREMIUM, p.authMode);
        assertEquals(6144, p.memoryMb);
        assertEquals("https://mcfiles.example/packs/atm10a/objects/ab/abcdef", p.objectUrl("abcdef"));
    }

    @Test
    void authModeSconosciutoONonValido() {
        for (String v : new String[] { null, "", "auto", "boh" }) assertNull(Pack.normalizeAuth(v), String.valueOf(v));
        Pack p = Pack.parse("{\"id\":\"x\",\"minecraft\":\"1.21.1\"}", "https://h/x/pack.json");
        assertNull(p.authMode);

    }

    @Test
    void packJsonConIdPericolosoRifiutato() {
        assertThrows(IllegalArgumentException.class,
                () -> Pack.parse("{\"id\":\"../x\",\"minecraft\":\"1.21.1\"}", "https://h/x/pack.json"));
        assertThrows(IllegalArgumentException.class,
                () -> Pack.parse("{\"id\":\"x\"}", "https://h/x/pack.json"));
    }
}
