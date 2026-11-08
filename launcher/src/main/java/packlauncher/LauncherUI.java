package packlauncher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
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
 * Mostra solo quello che serve per giocare; versioni e indirizzo sono nel tooltip del server,
 * il log tecnico sotto "Mostra dettagli".
 * Nickname, RAM e tipo di account scelto sono salvati per server; l'account Microsoft vale per tutti.
 */
public final class LauncherUI {
    private final Config cfg;
    private final Downloader dl;
    private final JFrame frame;
    private final JTextField nameField = new JTextField(16);
    private final JLabel nameLabel = new JLabel("Nickname");
    private final AccountManager accounts;
    /** Voce 0 = account Microsoft salvato, voce 1 = nickname offline. Visibile solo con un account salvato. */
    private final JComboBox<String> accountBox = new JComboBox<>();
    private final JLabel accountLabel = new JLabel("Account");
    private final JButton loginButton = new JButton("Accedi con Microsoft");
    private final JButton logoutButton = new JButton("Esci");
    /** Nickname con "Accedi con Microsoft" accanto; senza nickname il pulsante occupa tutta la riga. */
    private final JPanel nameRow = row(nameField, loginButton);
    private final JPanel accountRow = row(accountBox, logoutButton);
    private final JLabel accessHint = new JLabel(" ");
    private final JComboBox<String> packBox = new JComboBox<>();
    /** Al posto della tendina quando c'è un solo server. */
    private final JLabel packName = new JLabel(" ");
    private final JSpinner ramSpinner;
    private final JButton uninstallButton = new JButton("Disinstalla modpack");
    private final JButton playButton = new JButton("Caricamento...");
    private final JProgressBar bar = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    /** Sotto "Gioca": l'avviso (server spento, serve premium) oppure, durante l'avvio, barra e stato. */
    private final CardLayout feedbackCards = new CardLayout();
    private final JPanel feedback = new JPanel(feedbackCards);
    private final JTextArea logArea = new JTextArea(8, 40);
    private final JScrollPane logScroll = new JScrollPane(logArea);
    private final JButton detailsButton = new JButton("Mostra dettagli");
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

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        JPanel packRow = new JPanel(new BorderLayout());
        packRow.add(packBox, BorderLayout.CENTER);
        packName.setFont(packName.getFont().deriveFont(Font.BOLD));
        packName.setVisible(false);
        packRow.add(packName, BorderLayout.WEST);
        addRow(form, c, 0, new JLabel("Server"), packRow);
        addRow(form, c, 1, accountLabel, accountRow);
        addRow(form, c, 2, nameLabel, nameRow);
        JPanel ramRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ramRow.add(ramSpinner);
        ramRow.add(new JLabel("  GB"));
        addRow(form, c, 3, new JLabel("RAM"), ramRow);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(12, 4, 4, 4);
        form.add(playButton, c);
        JPanel progress = new JPanel(new BorderLayout(0, 4));
        progress.add(bar, BorderLayout.NORTH);
        progress.add(status, BorderLayout.CENTER);
        feedback.add(accessHint, "hint");
        feedback.add(progress, "progress");
        c.gridy = 5; c.insets = new Insets(4, 4, 4, 4);
        form.add(feedback, c);
        c.gridy = 6;
        form.add(uninstallButton, c);
        uninstallButton.setEnabled(false);
        uninstallButton.setToolTipText("Rimuovi i file locali del modpack selezionato per liberare spazio");
        uninstallButton.addActionListener(e -> uninstall());

        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 16f));
        playButton.setPreferredSize(new Dimension(200, 44));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> play());
        nameField.setText(cfg.get("name", ""));
        nameField.addActionListener(e -> play());
        accountBox.addActionListener(e -> { if (!refreshingAccounts) onAccountChosen(); });
        loginButton.addActionListener(e -> login());
        logoutButton.addActionListener(e -> logout());
        accessHint.setFont(accessHint.getFont().deriveFont(Font.PLAIN, 12f));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
        refreshAccounts();
        new javax.swing.Timer(RECHECK_S * 1000, e -> recheckServer()).start();

        // Log tecnico: nascosto, si apre a richiesta (gli errori arrivano comunque in una finestra)
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logScroll.setVisible(false);
        detailsButton.setBorderPainted(false);
        detailsButton.setContentAreaFilled(false);
        detailsButton.setFocusPainted(false);
        detailsButton.setMargin(new Insets(0, 0, 0, 0));
        detailsButton.setForeground(Color.GRAY);
        detailsButton.setFont(detailsButton.getFont().deriveFont(Font.PLAIN, 11f));
        detailsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detailsButton.addActionListener(e -> toggleDetails());
        JPanel details = new JPanel(new BorderLayout(0, 4));
        details.add(logScroll, BorderLayout.CENTER);
        JPanel detailsLink = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        detailsLink.add(detailsButton);
        details.add(detailsLink, BorderLayout.NORTH);

        JPanel root = new JPanel(new BorderLayout(8, 4));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
        root.add(form, BorderLayout.NORTH);
        root.add(details, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.pack();
        frame.setSize(Math.max(420, frame.getWidth()), frame.getHeight());
        frame.setMinimumSize(new Dimension(380, 0));
        frame.setLocationRelativeTo(null);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int y, JLabel label, java.awt.Component field) {
        c.gridy = y; c.gridwidth = 1;
        c.gridx = 0; c.fill = GridBagConstraints.NONE; c.weightx = 0; form.add(label, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(field, c);
    }

    private static JPanel row(java.awt.Component main, java.awt.Component side) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(main, BorderLayout.CENTER);
        p.add(side, BorderLayout.EAST);
        return p;
    }

    private void toggleDetails() {
        boolean show = !logScroll.isVisible();
        logScroll.setVisible(show);
        detailsButton.setText(show ? "Nascondi dettagli" : "Mostra dettagli");
        relayout();
    }

    /** Adatta l'altezza della finestra alle righe visibili (la larghezza scelta dall'utente resta). */
    private void relayout() {
        if (frame.getPreferredSize().height == frame.getHeight()) return;
        int w = frame.getWidth();
        frame.pack();
        if (w > frame.getWidth()) frame.setSize(w, frame.getHeight());
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
                packBox.addActionListener(e -> loadSelectedPack());
                boolean single = packs.size() == 1;
                packBox.setVisible(!single);
                packName.setVisible(single);
                if (packs.isEmpty()) {
                    playButton.setText("Gioca");
                    accessHint.setText("Nessun server disponibile.");
                } else {
                    loadSelectedPack();
                }
            });
        });
    }

    private void loadSelectedPack() {
        int idx = packBox.getSelectedIndex();
        if (idx < 0 || idx >= packs.size()) return;
        Pack.Entry entry = packs.get(idx);
        if (currentPack != null && currentPack.id.equals(entry.id())) return;
        currentPack = null;
        updateUninstall();
        serverOnline = true;
        playButton.setEnabled(false);
        showPackInfo(entry.name(), entry.summary(), entry.server());
        // Nome e RAM salvati per questo server (o quelli globali delle versioni precedenti)
        nameField.setText(cfg.getFor(entry.id(), "name", ""));
        selectAccountFor(entry.id(), entry.authMode());
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

    private void updateUninstall() {
        Pack.Entry entry = selectedEntry();
        uninstallButton.setEnabled(!busyNow && entry != null
                && PackUninstaller.isInstalled(cfg.baseDir, entry.id()));
    }

    private void uninstall() {
        Pack.Entry entry = selectedEntry();
        if (busyNow || entry == null || !uninstallButton.isEnabled()) return;
        Object[] options = {"Disinstalla", "Annulla"};
        int answer = JOptionPane.showOptionDialog(frame,
                "Disinstallare il modpack «" + entry.name() + "»?\n\n"
                + "Verranno eliminati tutti i suoi file locali, inclusi mondi salvati,\n"
                + "screenshot, mod aggiunte e impostazioni di gioco. L'operazione è definitiva.\n\n"
                + "Gli altri modpack, i file condivisi e l'account resteranno disponibili.\n"
                + "Potrai scaricare di nuovo il modpack premendo Gioca.",
                "Disinstalla modpack", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (answer != 0) return;
        setBusy(true);
        status.setText("Disinstallazione del modpack...");
        background(() -> {
            PackUninstaller.uninstall(cfg.baseDir, entry.id());
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                logArea.append("Modpack disinstallato: " + entry.name() + "\n");
                JOptionPane.showMessageDialog(frame,
                        "Modpack «" + entry.name() + "» disinstallato.\nLo spazio occupato dai suoi file locali è stato liberato.",
                        "Disinstallazione completata", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    private void setBusy(boolean busy) {
        busyNow = busy;
        updateUninstall();
        playButton.setEnabled(!busy);
        nameField.setEnabled(!busy);
        accountBox.setEnabled(!busy);
        loginButton.setEnabled(!busy);
        logoutButton.setEnabled(!busy);
        packBox.setEnabled(!busy);
        ramSpinner.setEnabled(!busy);
        feedbackCards.show(feedback, busy ? "progress" : "hint");
        if (busy) { status.setText("Preparazione..."); bar.setIndeterminate(true); }
        else { bar.setIndeterminate(false); bar.setValue(0); updateAccess(); }
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
        showPackInfo(p.name, p.summary(), p.server);
        selectAccountFor(p.id, p.authMode);   // chiama anche updateAccess()
    }

    // ------------------------------------------------------------ account

    private boolean premiumSelected() {
        return accounts.current() != null && accountBox.getSelectedIndex() == 0;
    }

    /** Ricostruisce la tendina degli account dopo login/logout. */
    private void refreshAccounts() {
        Accounts.Account acc = accounts.current();
        refreshingAccounts = true;
        accountBox.removeAllItems();
        if (acc != null) {
            accountBox.addItem(acc.name() + " (Microsoft)");
            accountBox.addItem("Nickname (non-premium)");
        }
        refreshingAccounts = false;
        loginButton.setEnabled(!busyNow);
        Pack.Entry e = selectedEntry();
        selectAccountFor(e != null ? e.id() : null, currentAuthMode());
    }

    /** Sceglie l'account salvato per questo server; se il server è premium e c'è l'account Microsoft, quello. */
    private void selectAccountFor(String serverId, String authMode) {
        boolean hasPremium = accounts.current() != null;
        String saved = serverId != null ? cfg.getFor(serverId, "account", null) : null;
        boolean premium = hasPremium && (Minecraft.PREMIUM.equals(authMode) || !Minecraft.OFFLINE.equals(saved));
        refreshingAccounts = true;
        if (hasPremium) accountBox.setSelectedIndex(premium ? 0 : 1);
        refreshingAccounts = false;
        onAccountChosen();
    }

    private void onAccountChosen() {
        boolean hasAccount = accounts.current() != null;
        boolean premiumServer = Minecraft.PREMIUM.equals(currentAuthMode());
        // Senza account Microsoft: nickname e, se il login è configurato, "Accedi con Microsoft" accanto.
        // Con l'account: tendina account/nickname ed "Esci"; il nickname solo se scelto.
        // Su un server premium senza account il nickname non serve: resta solo il pulsante di accesso.
        boolean showLogin = !hasAccount && accounts.premiumAvailable();
        boolean showName = !premiumSelected() && !(premiumServer && !hasAccount);
        accountLabel.setVisible(hasAccount);
        accountRow.setVisible(hasAccount);
        nameField.setVisible(showName);
        loginButton.setVisible(showLogin);
        nameRow.add(loginButton, showName ? BorderLayout.EAST : BorderLayout.CENTER);
        nameLabel.setText(showName ? "Nickname" : "Account");
        nameLabel.setVisible(showName || showLogin);
        nameRow.setVisible(showName || showLogin);
        updateAccess();
        relayout();
    }

    /** Abilita "Gioca" solo se l'account scelto può entrare nel server selezionato. */
    private void updateAccess() {
        updateUninstall();
        if (busyNow) return;
        boolean premium = premiumSelected();
        String authMode = currentAuthMode();
        boolean ok = AccessPolicy.canJoin(authMode, premium) && serverOnline;
        String hint;
        if (!serverOnline) {
            hint = "Il server è spento o non raggiungibile. Riprovo ogni " + RECHECK_S + " secondi.";
        } else if (ok) {
            hint = " ";
        } else if (accounts.current() != null) {
            hint = "Questo server accetta solo account premium: scegli l'account Microsoft.";
        } else if (accounts.premiumAvailable()) {
            hint = "Questo server accetta solo account premium: accedi con Microsoft.";
        } else {
            hint = "Questo server accetta solo account premium, ma questa versione del launcher non ha il login Microsoft.";
        }
        accessHint.setText(hint.isBlank() ? " " : "<html>" + hint + "</html>");
        playButton.setText(currentPack == null ? "Caricamento..." : serverOnline ? "Gioca" : "Server spento");
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
        loginButton.setEnabled(false);

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

    /** Nome del server; versione e indirizzo solo nel tooltip, per chi li cerca. */
    private void showPackInfo(String name, String summary, String server) {
        packName.setText(name);
        StringBuilder tip = new StringBuilder("<html>");
        if (summary != null && !summary.isEmpty()) tip.append(summary).append("<br>");
        if (server != null && !server.isBlank()) tip.append(server);
        String t = tip.length() > 6 ? tip.append("</html>").toString() : null;
        packBox.setToolTipText(t);
        packName.setToolTipText(t);
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
                    if (currentPack == null) {   // server/modpack non caricato: lo si dice sotto "Gioca"
                        playButton.setText("Gioca");
                        accessHint.setText("<html>Impossibile caricare il server: " + e.getMessage() + "</html>");
                    }
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
