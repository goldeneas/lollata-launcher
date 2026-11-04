package packlauncher;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.FlowLayout;
import java.net.URI;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
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

import packlauncher.common.Minecraft;
import packlauncher.common.ServerProbe;

/**
 * Finestra unica: server/modpack, account (Microsoft premium o nickname), RAM, pulsante Gioca.
 * Nickname, RAM e tipo di account scelto sono salvati per server; l'account Microsoft vale per tutti.
 */
public final class LauncherUI {
    private final Config cfg;
    private final Downloader dl;
    private final JFrame frame;
    private final JTextField nameField = new JTextField(16);
    private final JLabel nameLabel = new JLabel("Nickname");
    private final AccountManager accounts;
    /** Voce 0 = nickname offline, voce 1 (se c'è) = account Microsoft salvato. */
    private final JComboBox<String> accountBox = new JComboBox<>();
    private final JButton accountButton = new JButton();
    private final JLabel accessHint = new JLabel(" ");
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
    /** true mentre si ricostruisce la tendina degli account (i suoi eventi vanno ignorati). */
    private boolean refreshingAccounts;
    private boolean busyNow;

    static final int RECHECK_S = 10;
    /** false se l'ultima sonda non ha raggiunto il server Minecraft: "Gioca" resta disattivato. */
    private boolean serverOnline = true;
    private boolean rechecking;

    public LauncherUI(Config cfg) {
        this.cfg = cfg;
        this.dl = new Downloader(cfg.userAgent());
        this.accounts = AccountManager.create(cfg);
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
        JPanel accountRow = new JPanel(new BorderLayout(6, 0));
        accountRow.add(accountBox, BorderLayout.CENTER);
        accountRow.add(accountButton, BorderLayout.EAST);
        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(new JLabel("Account"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(accountRow, c);
        c.gridx = 0; c.gridy = 4; c.fill = GridBagConstraints.NONE; form.add(nameLabel, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; form.add(nameField, c);
        c.gridx = 0; c.gridy = 5; c.fill = GridBagConstraints.NONE; form.add(new JLabel("RAM (GB)"), c);
        c.gridx = 1; form.add(ramSpinner, c);
        c.gridx = 0; c.gridy = 6; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; form.add(accessHint, c);
        c.gridy = 7; form.add(playButton, c);
        c.gridy = 8; form.add(bar, c);
        c.gridy = 9; form.add(status, c);

        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 16f));
        playButton.setPreferredSize(new Dimension(200, 40));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> play());
        nameField.setText(cfg.get("name", ""));
        nameField.addActionListener(e -> play());
        accountBox.addActionListener(e -> { if (!refreshingAccounts) onAccountChosen(); });
        accountButton.addActionListener(e -> {
            if (accounts.current() != null) logout(); else login();
        });
        accessHint.setFont(accessHint.getFont().deriveFont(Font.PLAIN, 11f));
        refreshAccounts();
        new javax.swing.Timer(RECHECK_S * 1000, e -> recheckServer()).start();
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
        serverOnline = true;
        playButton.setEnabled(false);
        packInfo.setText(infoText(entry.summary(), entry.server(), entry.authMode()));
        // Nome e RAM salvati per questo server (o quelli globali delle versioni precedenti)
        nameField.setText(cfg.getFor(entry.id(), "name", ""));
        selectAccountFor(entry.id(), entry.authMode());
        status.setText("Carico " + entry.name() + "...");
        background(() -> {
            Pack p = Pack.load(dl, entry.url());
            // online-mode letto ora dal server: vale anche se è cambiato dopo l'ultima pubblicazione
            ServerProbe.Result probe = LaunchFlow.refreshAuthMode(p);
            SwingUtilities.invokeLater(() -> {
                if (packBox.getSelectedIndex() != idx) return; // nel frattempo è stato scelto un altro server
                currentPack = p;
                serverOnline = LaunchFlow.serverOnline(probe);
                SpinnerNumberModel m = (SpinnerNumberModel) ramSpinner.getModel();
                int want = Math.max(2, (p.memoryMb + 1023) / 1024);
                try { want = Integer.parseInt(cfg.getFor(p.id, "memoryGb", Integer.toString(want))); }
                catch (NumberFormatException ignored) {}
                ramSpinner.setValue(Math.max(2, Math.min(want, (Integer) m.getMaximum())));
                status.setText(p.name + " v" + p.version + " - pronto");
                showProbe(p, probe);
            });
        });
    }

    private void play() {
        if (currentPack == null || !playButton.isEnabled()) return;
        boolean premium = premiumSelected();
        String name = nameField.getText().trim();
        String deny = AccessPolicy.denyReason(currentPack.authMode, premium, currentPack.name);
        if (deny != null) {
            JOptionPane.showMessageDialog(frame, deny, "Account premium richiesto", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!premium && !LaunchFlow.isValidName(name)) {
            JOptionPane.showMessageDialog(frame, "Il nickname deve avere 3-16 caratteri: lettere, numeri o _",
                    "Nickname non valido", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int gb = (Integer) ramSpinner.getValue();
        cfg.set("pack", currentPack.id);
        if (!premium) cfg.setFor(currentPack.id, "name", name);
        cfg.setFor(currentPack.id, "account", premium ? Minecraft.PREMIUM : Minecraft.OFFLINE);
        cfg.setFor(currentPack.id, "memoryGb", Integer.toString(gb));
        try { cfg.save(); } catch (Exception ignored) {}

        setBusy(true);
        Pack pack = currentPack;
        background(() -> {
            Session session;
            if (premium) {
                try {
                    session = accounts.premiumSession();   // rinnova il token se è scaduto
                } catch (MicrosoftAuth.AuthException e) {
                    if (e.needsLogin) SwingUtilities.invokeLater(this::refreshAccounts);
                    throw e;
                }
            } else {
                session = Session.offline(name);
            }
            LaunchFlow.Prepared prep = LaunchFlow.prepare(cfg, dl, pack, session, gb * 1024, uiProgress());
            Process proc = GameLauncher.start(prep.command(), prep.instanceDir());
            SwingUtilities.invokeLater(() -> {
                status.setText("Minecraft è in avvio... (il caricamento delle mod richiede qualche minuto)");
                bar.setIndeterminate(false);
                bar.setValue(100);
                frame.setState(JFrame.ICONIFIED);
            });
            int code = proc.waitFor();
            if (code == 0) System.exit(0);
            Path log = prep.gameLog();
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
        busyNow = busy;
        playButton.setEnabled(!busy);
        nameField.setEnabled(!busy);
        accountBox.setEnabled(!busy);
        accountButton.setEnabled(!busy && accounts.premiumAvailable());
        packBox.setEnabled(!busy && packs.size() > 1);
        ramSpinner.setEnabled(!busy);
        if (!busy) { bar.setIndeterminate(false); bar.setValue(0); updateAccess(); }
    }

    // ------------------------------------------------------ server acceso?

    /** Ogni RECHECK_S secondi, se il server era spento, riprova: appena si accende "Gioca" si riattiva. */
    private void recheckServer() {
        Pack p = currentPack;
        if (p == null || serverOnline || busyNow || rechecking) return;
        rechecking = true;
        daemon("server-recheck", () -> {
            ServerProbe.Result r = LaunchFlow.refreshAuthMode(p);
            SwingUtilities.invokeLater(() -> {
                rechecking = false;
                if (currentPack != p || !LaunchFlow.serverOnline(r)) return;
                serverOnline = true;
                showProbe(p, r);
            });
        });
    }

    /** Mostra cosa ha risposto il server e aggiorna dati del pack, account e "Gioca". */
    private void showProbe(Pack p, ServerProbe.Result r) {
        logArea.append(LaunchFlow.describeProbe(p, r) + "\n");
        packInfo.setText(infoText(p.summary(), p.server, p.authMode));
        selectAccountFor(p.id, p.authMode);   // chiama anche updateAccess()
    }

    // ------------------------------------------------------------ account

    private boolean premiumSelected() {
        return accounts.current() != null && accountBox.getSelectedIndex() == 1;
    }

    /** Ricostruisce la tendina degli account dopo login/logout. */
    private void refreshAccounts() {
        Accounts.Account acc = accounts.current();
        refreshingAccounts = true;
        accountBox.removeAllItems();
        accountBox.addItem("Non-premium (solo nickname)");
        if (acc != null) accountBox.addItem("Microsoft: " + acc.name());
        refreshingAccounts = false;
        accountButton.setText(acc != null ? "Esci" : "Accedi con Microsoft");
        accountButton.setEnabled(accounts.premiumAvailable() && !busyNow);
        if (!accounts.premiumAvailable()) {
            accountButton.setToolTipText("Login Microsoft non configurato in questa versione del launcher");
        }
        Pack.Entry e = selectedEntry();
        selectAccountFor(e != null ? e.id() : null, currentAuthMode());
    }

    /** Sceglie l'account salvato per questo server; se il server è premium e c'è l'account Microsoft, quello. */
    private void selectAccountFor(String serverId, String authMode) {
        boolean hasPremium = accounts.current() != null;
        String saved = serverId != null ? cfg.getFor(serverId, "account", null) : null;
        boolean premium = hasPremium && (Minecraft.PREMIUM.equals(authMode) || !Minecraft.OFFLINE.equals(saved));
        refreshingAccounts = true;
        accountBox.setSelectedIndex(premium ? 1 : 0);
        refreshingAccounts = false;
        onAccountChosen();
    }

    private void onAccountChosen() {
        boolean premium = premiumSelected();
        nameLabel.setVisible(!premium);
        nameField.setVisible(!premium);
        updateAccess();
    }

    /** Abilita "Gioca" solo se l'account scelto può entrare nel server selezionato. */
    private void updateAccess() {
        if (busyNow) return;
        boolean premium = premiumSelected();
        String authMode = currentAuthMode();
        boolean ok = AccessPolicy.canJoin(authMode, premium) && serverOnline;
        if (!serverOnline) {
            accessHint.setText("<html><b>Il server Minecraft è spento o non raggiungibile.</b> "
                    + "Riprovo da solo ogni " + RECHECK_S + " secondi.</html>");
        } else if (ok) {
            accessHint.setText(" ");
        } else if (accounts.premiumAvailable()) {
            accessHint.setText("<html><b>Questo server richiede un account premium:</b> premi \"Accedi con Microsoft\".</html>");
        } else {
            accessHint.setText("<html><b>Questo server richiede un account premium</b>, "
                    + "ma questa versione del launcher non ha il login Microsoft configurato.</html>");
        }
        playButton.setEnabled(currentPack != null && ok);
    }

    /** authMode del pack caricato o, finché non è caricato, quello dell'indice. */
    private String currentAuthMode() {
        if (currentPack != null) return currentPack.authMode;
        Pack.Entry e = selectedEntry();
        return e != null ? e.authMode() : null;
    }

    private Pack.Entry selectedEntry() {
        int idx = packBox.getSelectedIndex();
        return idx >= 0 && idx < packs.size() ? packs.get(idx) : null;
    }

    private void logout() {
        int r = JOptionPane.showConfirmDialog(frame, "Uscire dall'account Microsoft " + accounts.current().name() + "?",
                "Esci", JOptionPane.YES_NO_OPTION);
        if (r != JOptionPane.YES_OPTION) return;
        try { accounts.logout(); } catch (Exception e) { logArea.append("ERRORE: " + e + "\n"); }
        refreshAccounts();
    }

    /** Device code flow: mostra il codice, apre microsoft.com/link e aspetta in background. */
    private void login() {
        JDialog dialog = new JDialog(frame, "Accedi con Microsoft", false);
        JLabel text = new JLabel("Richiesta del codice a Microsoft...");
        JButton open = new JButton("Copia codice e apri il browser");
        JButton cancel = new JButton("Annulla");
        open.setEnabled(false);
        boolean[] cancelled = {false};
        cancel.addActionListener(e -> { cancelled[0] = true; dialog.dispose(); });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { cancelled[0] = true; }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(open);
        buttons.add(cancel);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(text, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(440, 200);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        accountButton.setEnabled(false);

        daemon("msa-login", () -> {
            try {
                Accounts.Account acc = accounts.login(dc -> SwingUtilities.invokeLater(() -> {
                    text.setText("<html>1. Apri <b>" + dc.verificationUri() + "</b><br>"
                            + "2. Inserisci il codice: <font size=+2><b>" + dc.userCode() + "</b></font><br>"
                            + "3. Accedi con l'account Microsoft che possiede Minecraft.<br><br>"
                            + "Questa finestra si chiude da sola appena hai fatto.</html>");
                    open.setEnabled(true);
                    for (var l : open.getActionListeners()) open.removeActionListener(l);
                    open.addActionListener(e -> {
                        try {
                            Toolkit.getDefaultToolkit().getSystemClipboard()
                                    .setContents(new StringSelection(dc.userCode()), null);
                        } catch (Exception ignored) {}
                        try { Desktop.getDesktop().browse(URI.create(dc.verificationUri())); } catch (Exception ignored) {}
                    });
                    open.doClick();
                }), () -> cancelled[0]);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    refreshAccounts();
                    logArea.append("Accesso Microsoft eseguito: " + acc.name() + "\n");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    refreshAccounts();
                    if (!cancelled[0]) {
                        logArea.append("ERRORE login: " + e + "\n");
                        JOptionPane.showMessageDialog(frame, e.getMessage(), "Accesso non riuscito", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        });
    }

    private static String infoText(String summary, String server, String authMode) {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(summary == null || summary.isEmpty() ? "&nbsp;" : summary);
        if (server != null && !server.isBlank()) sb.append("<br>Indirizzo: ").append(server);
        if (Minecraft.PREMIUM.equals(authMode)) sb.append("<br>Richiede un account Microsoft (premium)");
        else if (Minecraft.OFFLINE.equals(authMode)) sb.append("<br>Basta un nickname (non-premium)");
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

    /** Esegue job fuori dal thread della UI; un errore viene mostrato all'utente. */
    private void background(Job job) {
        daemon("launcher-worker", () -> {
            try {
                job.run();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    status.setText("Errore: " + e.getMessage());
                    logArea.append("ERRORE: " + e + "\n");
                    JOptionPane.showMessageDialog(frame, e.getMessage(), "Errore", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
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
