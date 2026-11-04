package packlauncher;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Finestra unica: server/modpack, nome, RAM, pulsante Gioca. Nome e RAM sono salvati per server. */
public final class LauncherUI {
    private final Config cfg;
    private final Downloader dl;
    private final JFrame frame;
    private final JTextField nameField = new JTextField(16);
    private final JComboBox<String> packBox = new JComboBox<>();
    private final JLabel packLabel = new JLabel("Server");
    private final JLabel packInfo = new JLabel(" ");
    private final JSpinner ramSpinner;
    private final JButton playButton = new JButton("Gioca");
    private final JProgressBar bar = new JProgressBar();
    private final JLabel status = new JLabel("Caricamento modpack...");
    private final JTextArea logArea = new JTextArea(8, 40);
    private List<Pack.Entry> packs = List.of();
    private Pack currentPack;

    public LauncherUI(Config cfg) {
        this.cfg = cfg;
        this.dl = new Downloader(cfg.launcherName + "/1.0");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        frame = new JFrame(cfg.launcherName);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        try {
            java.net.URL icon = LauncherUI.class.getResource("/icon.png");
            if (icon != null) frame.setIconImage(javax.imageio.ImageIO.read(icon));
        } catch (Exception ignored) {}

        int maxGb = Math.max(2, (int) (totalRamMb() / 1024) - 1);
        ramSpinner = new JSpinner(new SpinnerNumberModel(Math.min(4, maxGb), 2, maxGb, 1));

        JLabel title = new JLabel(cfg.launcherName);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; form.add(title, c);
        c.gridwidth = 1;
        c.gridy = 1; form.add(packLabel, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(packBox, c);
        c.gridy = 2; form.add(packInfo, c);
        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("Inserisci il nome"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(nameField, c);
        c.gridx = 0; c.gridy = 4; c.fill = GridBagConstraints.NONE; form.add(new JLabel("RAM (GB)"), c);
        c.gridx = 1; form.add(ramSpinner, c);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; form.add(playButton, c);
        c.gridy = 6; form.add(bar, c);
        c.gridy = 7; form.add(status, c);

        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 16f));
        playButton.setPreferredSize(new Dimension(200, 40));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> play());
        nameField.setText(cfg.get("name", ""));
        nameField.addActionListener(e -> play());
        packBox.addActionListener(e -> loadSelectedPack());
        packInfo.setFont(packInfo.getFont().deriveFont(Font.PLAIN, 11f));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Dettagli"));

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(form, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(460, 420));
        frame.setLocationRelativeTo(null);
    }

    public void show() {
        frame.setVisible(true);
        if (nameField.getText().isEmpty()) nameField.requestFocusInWindow();
        background(() -> {
            packs = Pack.loadIndex(dl, cfg.indexUrl);
            SwingUtilities.invokeLater(() -> {
                String last = cfg.get("pack", "");
                for (Pack.Entry e : packs) packBox.addItem(e.name());
                for (int i = 0; i < packs.size(); i++) if (packs.get(i).id().equals(last)) packBox.setSelectedIndex(i);
                packBox.setEnabled(packs.size() > 1);
                if (packs.isEmpty()) status.setText("Nessun server disponibile.");
                else loadSelectedPack();
            });
        });
    }

    private void loadSelectedPack() {
        int idx = packBox.getSelectedIndex();
        if (idx < 0 || idx >= packs.size()) return;
        Pack.Entry entry = packs.get(idx);
        if (currentPack != null && currentPack.id.equals(entry.id())) return;
        currentPack = null;
        playButton.setEnabled(false);
        packInfo.setText(infoText(entry.summary(), entry.server(), entry.authMode()));
        // Nome e RAM salvati per questo server (o quelli globali delle versioni precedenti)
        nameField.setText(cfg.getFor(entry.id(), "name", ""));
        status.setText("Carico " + entry.name() + "...");
        background(() -> {
            Pack p = Pack.load(dl, entry.url());
            SwingUtilities.invokeLater(() -> {
                if (packBox.getSelectedIndex() != idx) return; // nel frattempo è stato scelto un altro server
                currentPack = p;
                SpinnerNumberModel m = (SpinnerNumberModel) ramSpinner.getModel();
                int want = Math.max(2, (p.memoryMb + 1023) / 1024);
                try { want = Integer.parseInt(cfg.getFor(p.id, "memoryGb", Integer.toString(want))); }
                catch (NumberFormatException ignored) {}
                ramSpinner.setValue(Math.max(2, Math.min(want, (Integer) m.getMaximum())));
                packInfo.setText(infoText(
                        "Minecraft " + p.minecraft + (p.loaderType != null && !p.loaderType.equals("vanilla")
                                ? " - " + p.loaderType + " " + p.loaderVersion : ""),
                        p.server, p.authMode));
                status.setText(p.name + " v" + p.version + " - pronto");
                playButton.setEnabled(true);
            });
        });
    }

    private void play() {
        if (currentPack == null || !playButton.isEnabled()) return;
        String name = nameField.getText().trim();
        if (!LaunchFlow.isValidName(name)) {
            JOptionPane.showMessageDialog(frame, "Il nome deve avere 3-16 caratteri: lettere, numeri o _",
                    "Nome non valido", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int gb = (Integer) ramSpinner.getValue();
        cfg.set("pack", currentPack.id);
        cfg.setFor(currentPack.id, "name", name);
        cfg.setFor(currentPack.id, "memoryGb", Integer.toString(gb));
        try { cfg.save(); } catch (Exception ignored) {}

        setBusy(true);
        Pack pack = currentPack;
        background(() -> {
            LaunchFlow.Prepared prep = LaunchFlow.prepare(cfg, dl, pack, name, gb * 1024, uiProgress());
            Process proc = GameLauncher.start(prep.command(), prep.instanceDir());
            SwingUtilities.invokeLater(() -> {
                status.setText("Minecraft è in avvio... (il caricamento delle mod richiede qualche minuto)");
                bar.setIndeterminate(false);
                bar.setValue(100);
                frame.setState(JFrame.ICONIFIED);
            });
            int code = proc.waitFor();
            if (code == 0) System.exit(0);
            Path log = prep.instanceDir().resolve(".packlauncher").resolve("game-output.log");
            SwingUtilities.invokeLater(() -> {
                frame.setState(JFrame.NORMAL);
                setBusy(false);
                int r = JOptionPane.showConfirmDialog(frame,
                        "Minecraft si è chiuso con un errore (codice " + code + ").\nAprire il log?\n" + log,
                        "Errore", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                if (r == JOptionPane.YES_OPTION) {
                    try { Desktop.getDesktop().open(log.toFile()); } catch (Exception ignored) {}
                }
            });
        });
    }

    private void setBusy(boolean busy) {
        playButton.setEnabled(!busy);
        nameField.setEnabled(!busy);
        packBox.setEnabled(!busy && packs.size() > 1);
        ramSpinner.setEnabled(!busy);
        if (!busy) { bar.setIndeterminate(false); bar.setValue(0); }
    }

    private static String infoText(String summary, String server, String authMode) {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(summary == null || summary.isEmpty() ? "&nbsp;" : summary);
        if (server != null && !server.isBlank()) sb.append("<br>Indirizzo: ").append(server);
        if (Pack.AUTH_PREMIUM.equals(authMode)) sb.append("<br>Richiede un account Microsoft (premium)");
        else if (Pack.AUTH_OFFLINE.equals(authMode)) sb.append("<br>Basta un nickname (non-premium)");
        return sb.append("</html>").toString();
    }

    private Progress uiProgress() {
        return new Progress() {
            @Override public void stage(String text) {
                SwingUtilities.invokeLater(() -> { status.setText(text); bar.setIndeterminate(true); });
                log("== " + text);
            }

            @Override public void progress(long done, long total) {
                SwingUtilities.invokeLater(() -> {
                    if (total <= 0) { bar.setIndeterminate(true); return; }
                    bar.setIndeterminate(false);
                    bar.setValue((int) (done * 100 / total));
                });
            }

            @Override public void log(String line) {
                SwingUtilities.invokeLater(() -> {
                    logArea.append(line + "\n");
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                });
            }
        };
    }

    private interface Job { void run() throws Exception; }

    private void background(Job job) {
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    playButton.setEnabled(currentPack != null);
                    status.setText("Errore: " + e.getMessage());
                    logArea.append("ERRORE: " + e + "\n");
                    JOptionPane.showMessageDialog(frame, e.getMessage(), "Errore", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "launcher-worker");
        t.setDaemon(true);
        t.start();
    }

    private static long totalRamMb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize() / 1024 / 1024;
        } catch (Throwable t) {
            return 8192;
        }
    }
}
