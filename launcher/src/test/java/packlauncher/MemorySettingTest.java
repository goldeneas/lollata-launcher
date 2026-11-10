package packlauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.text.ParseException;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class MemorySettingTest {
    @Test
    void leggeIlTestoSenzaPremereInvio() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = LauncherUI.createRamSpinner(16);
            JFormattedTextField field = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            field.setText("6");
            assertEquals(4, spinner.getValue());
            assertEquals(JFormattedTextField.PERSIST, field.getFocusLostBehavior());
            assertEquals(6, assertDoesNotThrow(() -> LauncherUI.readMemoryGb(spinner)));
        });
    }

    @Test
    void leggeIlValoreSceltoConLeFrecce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = LauncherUI.createRamSpinner(16);
            spinner.setValue(spinner.getNextValue());
            spinner.setValue(spinner.getNextValue());
            assertEquals(6, assertDoesNotThrow(() -> LauncherUI.readMemoryGb(spinner)));
        });
    }

    @Test
    void rifiutaTestoNonValidoESuperamentoDeiLimiti() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String text : new String[]{"abc", "1", "17", ""}) {
                JSpinner spinner = LauncherUI.createRamSpinner(16);
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText(text);
                assertThrows(ParseException.class, () -> LauncherUI.readMemoryGb(spinner), text);
                assertEquals(4, spinner.getValue());
            }
        });
    }
}
