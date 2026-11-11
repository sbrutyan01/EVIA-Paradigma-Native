package evia.paradigma;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Claude Code tab beside Output: Claude Code (the claude command) in a real terminal (JediTerm on a local
 * pseudo-terminal from pty4j), started in the program's folder the first time the tab is shown. When it ends, the
 * tab offers to start it again; when it is not installed, it says how to install it or lets you pick the file.
 */
final class ClaudeTerminal extends JPanel {

    private final Supplier<File> folder;
    private final CardLayout cards = new CardLayout();
    private final JPanel termBox = new JPanel(new BorderLayout());
    private final JLabel message = new JLabel();
    private final JButton startBtn = new JButton(Text.t("Start Claude Code"));
    private JediTermWidget widget;
    private Pty pty;

    /** folder: where Claude Code starts (the open program's folder, or null for the home folder). */
    ClaudeTerminal(Supplier<File> folder) {
        super(new BorderLayout());
        this.folder = folder;
        setLayout(cards);

        JButton choose = new JButton(Text.t("Choose the claude file..."));
        choose.addActionListener(e -> chooseExecutable());
        startBtn.addActionListener(e -> start());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        buttons.add(startBtn);
        buttons.add(choose);
        JPanel info = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new Insets(4, 4, 4, 4);
        info.add(message, c);
        info.add(buttons, c);

        add(termBox, "term");
        add(info, "info");
        showInfo(Text.t("Claude Code starts here, in the program's folder, when you open this tab."));
    }

    /** Starts Claude Code unless it is already running (the tab was shown). */
    void showing() {
        if (pty == null || !pty.process.isAlive()) {
            if (widget == null) start();
        }
        if (widget != null) SwingUtilities.invokeLater(() -> widget.requestFocusInWindow());
    }

    /** Ends Claude Code (the IDE is closing). */
    void stop() {
        if (pty != null) pty.close();
    }

    private void showInfo(String html) {
        message.setText("<html><div style='width:460px;text-align:center'>" + html + "</div></html>");
        cards.show(this, "info");
    }

    private void start() {
        stop();
        String exe = findExecutable();
        if (exe == null) {
            widget = null;
            startBtn.setText(Text.t("Try again"));
            showInfo(Text.t("Claude Code is not installed (no claude command found).")
                    + "<br><br>" + Text.t("Install it, then press Try again:") + "<br><code>"
                    + (ExternalEditor.WINDOWS ? "irm https://claude.ai/install.ps1 | iex"
                    : "curl -fsSL https://claude.ai/install.sh | bash")
                    + "</code><br>" + Text.t("or") + " <code>npm install -g @anthropic-ai/claude-code</code>");
            return;
        }
        File dir = folder.get();
        if (dir == null || !dir.isDirectory()) dir = new File(System.getProperty("user.home"));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("PATH", pathWith(new File(exe).getParent(), env.getOrDefault("PATH", "")));
        List<String> cmd = new ArrayList<>();
        if (ExternalEditor.WINDOWS) {
            if (exe.toLowerCase(Locale.ROOT).endsWith(".cmd")) cmd.addAll(List.of("cmd.exe", "/c"));
            cmd.add(exe);
        } else {
            // pty4j leaves a new terminal at 0 x 0 until the first resize; Claude Code reads the size as it starts
            cmd.addAll(List.of("/bin/sh", "-c", "stty cols 120 rows 30 2>/dev/null; exec \"$0\"", exe));
        }
        try {
            PtyProcess p = new PtyProcessBuilder(cmd.toArray(new String[0]))
                    .setDirectory(dir.getAbsolutePath())
                    .setEnvironment(env)
                    .setInitialColumns(120)
                    .setInitialRows(30)
                    .start();
            pty = new Pty(p);
        } catch (IOException | RuntimeException ex) {
            widget = null;
            startBtn.setText(Text.t("Try again"));
            showInfo(Text.t("Could not start Claude Code: %s", String.valueOf(ex.getMessage())));
            return;
        }
        if (widget != null) widget.close();
        termBox.removeAll();
        widget = new JediTermWidget(120, 30, new Look());
        widget.setTtyConnector(pty);
        widget.start();
        termBox.add(widget, BorderLayout.CENTER);
        termBox.revalidate();
        cards.show(this, "term");
        widget.requestFocusInWindow();

        Pty mine = pty;
        Thread watch = new Thread(() -> {
            try {
                mine.process.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (pty != mine) return;                    // started again meanwhile
                startBtn.setText(Text.t("Start Claude Code"));
                showInfo(Text.t("Claude Code has ended."));
            });
        }, "claude-code-watch");
        watch.setDaemon(true);
        watch.start();
    }

    private void chooseExecutable() {
        JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
        fc.setFileHidingEnabled(false);
        fc.setDialogTitle(Text.t("The claude file (Claude Code)"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Settings.setClaudePath(fc.getSelectedFile().getAbsolutePath());
        start();
    }

    /** The claude command: the one chosen by hand, on PATH, or where its installers put it. */
    static String findExecutable() {
        String chosen = Settings.claudePath();
        if (!chosen.isEmpty() && Files.isRegularFile(Path.of(chosen))) return chosen;
        String home = System.getProperty("user.home");
        List<String> names = ExternalEditor.WINDOWS ? List.of("claude.exe", "claude.cmd") : List.of("claude");
        List<String> dirs = new ArrayList<>(Arrays.asList(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)));
        // an app started from the desktop often has a short PATH: the installers' places too
        dirs.addAll(List.of(home + "/.local/bin", home + "/.claude/local", home + "/.npm-global/bin",
                "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        if (ExternalEditor.WINDOWS) {
            dirs.add(home + "\\.local\\bin");
            String appData = System.getenv("APPDATA");
            if (appData != null) dirs.add(appData + "\\npm");
        }
        for (String d : dirs) {
            if (d.isBlank()) continue;
            for (String n : names) {
                Path p = Path.of(d, n);
                if (Files.isRegularFile(p) && (ExternalEditor.WINDOWS || Files.isExecutable(p))) return p.toString();
            }
        }
        return null;
    }

    private static String pathWith(String dir, String path) {
        if (dir == null || Arrays.asList(path.split(File.pathSeparator)).contains(dir)) return path;
        return path.isEmpty() ? dir : dir + File.pathSeparator + path;
    }

    /** The terminal in the Output window's colours and the code font. */
    private static final class Look extends DefaultSettingsProvider {
        @Override public TerminalColor getDefaultForeground() {
            Color c = Theme.consoleFg();
            return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }

        @Override public TerminalColor getDefaultBackground() {
            Color c = Theme.consoleBg();
            return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }

        @Override public com.jediterm.terminal.TextStyle getDefaultStyle() {
            return new com.jediterm.terminal.TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override public Font getTerminalFont() {
            return Fonts.mono(Font.PLAIN, getTerminalFontSize());
        }

        @Override public float getTerminalFontSize() {
            return 13;
        }
    }

    /** Claude Code's pseudo-terminal: what it writes goes to the screen, the keys to it, the size follows the tab. */
    private static final class Pty implements TtyConnector {
        final PtyProcess process;
        private final Reader in;
        private final OutputStream out;

        Pty(PtyProcess process) {
            this.process = process;
            this.in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
            this.out = process.getOutputStream();
        }

        @Override public int read(char[] buf, int offset, int length) throws IOException {
            return in.read(buf, offset, length);
        }

        @Override public void write(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        @Override public void write(String s) throws IOException {
            write(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override public boolean isConnected() {
            return process.isAlive();
        }

        @Override public boolean ready() throws IOException {
            return in.ready();
        }

        @Override public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override public void resize(TermSize size) {
            if (process.isAlive()) process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }

        @Override public String getName() {
            return "Claude Code";
        }

        @Override public void close() {
            process.destroy();
        }
    }
}
