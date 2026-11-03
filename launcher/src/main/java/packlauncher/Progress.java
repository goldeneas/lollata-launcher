package packlauncher;

/** Ricevitore di avanzamento (UI o console). */
public interface Progress {
    /** Nuova fase, es. "Download librerie". */
    void stage(String text);

    /** Avanzamento della fase corrente: done/total (byte o file). */
    void progress(long done, long total);

    /** Riga di log. */
    void log(String line);

    Progress CONSOLE = new Progress() {
        private int lastPct = -1;

        @Override public void stage(String text) { lastPct = -1; System.out.println("== " + text); }

        @Override public void progress(long done, long total) {
            int pct = total <= 0 ? 0 : (int) (done * 100 / total);
            if (pct / 10 != lastPct / 10) { System.out.println("   " + pct + "%"); lastPct = pct; }
        }

        @Override public void log(String line) { System.out.println(line); }
    };
}
