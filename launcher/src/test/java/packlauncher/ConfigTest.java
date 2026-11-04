package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    @TempDir Path home;

    @BeforeEach void setUp() { System.setProperty("launcher.home", home.toString()); }

    @AfterEach void tearDown() { System.clearProperty("launcher.home"); }

    @Test
    void nicknameSalvatoPerServerESopravviveAlRiavvio() throws Exception {
        Config c = new Config();
        c.setFor("atm10a", "name", "Steve");
        c.setFor("vanilla", "name", "Alex");
        c.save();

        Config reopened = new Config();
        assertEquals("Steve", reopened.getFor("atm10a", "name", ""));
        assertEquals("Alex", reopened.getFor("vanilla", "name", ""));
        assertEquals("", reopened.getFor("nuovo", "name", ""));
    }

    @Test
    void ripiegaSulleImpostazioniGlobaliDelleVersioniPrecedenti() throws Exception {
        Files.writeString(home.resolve("settings.properties"), "name=Vecchio\nmemoryGb=6\n");
        Config c = new Config();
        assertEquals("Vecchio", c.getFor("atm10a", "name", ""));
        assertEquals("6", c.getFor("atm10a", "memoryGb", "4"));

        c.setFor("atm10a", "name", "Nuovo");
        assertEquals("Nuovo", c.getFor("atm10a", "name", ""));
        assertEquals("Vecchio", c.getFor("altro", "name", ""));

        c.setFor("atm10a", "name", null);
        assertEquals("Vecchio", c.getFor("atm10a", "name", ""), "null cancella il valore del server");
    }
}
