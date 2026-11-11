package evia.paradigma;

import com.jediterm.terminal.ui.JediTermWidget;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The Claude Code tab beside Output, on the real main window: a stand-in claude script shows the terminal is a real
 * one (a size, typed keys arrive, the program's folder), the tab offers a new start when it ends, Run goes back to
 * Output. Then, when Claude Code itself is installed, a screenshot of it in the tab.
 */
public class ClaudeTabTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok    " : "FAIL  ") + what); if (!ok) fails++; }
    static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static Object field(Object o, String f) throws Exception { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }
    static void shot(Window w, String name) throws Exception {
        ui(() -> {}); Thread.sleep(500);
        ui(() -> { try {
            java.awt.image.MultiResolutionImage mr = new Robot().createMultiResolutionScreenCapture(w.getBounds());
            List<Image> v = mr.getResolutionVariants(); Image big = v.get(v.size() - 1);
            BufferedImage b = new BufferedImage(big.getWidth(null), big.getHeight(null), BufferedImage.TYPE_INT_RGB);
            b.getGraphics().drawImage(big, 0, 0, null);
            ImageIO.write(b, "png", new File(System.getProperty("out", "build/claude-tab"), name + ".png"));
        } catch (Exception e) { throw new RuntimeException(e); } });
    }
    static String screen(ClaudeTerminal t) throws Exception {
        String[] s = {""};
        ui(() -> { try {
            JediTermWidget w = (JediTermWidget) field(t, "widget");
            if (w != null) s[0] = w.getTerminalTextBuffer().getScreenLines();
        } catch (Exception e) { throw new RuntimeException(e); } });
        return s[0];
    }
    static boolean waitFor(ClaudeTerminal t, String text, int ms) throws Exception {
        for (int i = 0; i < ms / 100; i++) { if (screen(t).contains(text)) return true; Thread.sleep(100); }
        return false;
    }
    static void type(ClaudeTerminal t, String s) throws Exception {
        ui(() -> { try {
            JediTermWidget w = (JediTermWidget) field(t, "widget");
            Component p = w.getTerminalPanel();
            for (char ch : s.toCharArray()) {
                if (ch == '\r') {
                    p.dispatchEvent(new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\r'));
                } else {
                    p.dispatchEvent(new KeyEvent(p, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch));
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); } });
    }

    public static void main(String[] a) throws Exception {
        String realClaude = System.getProperty("claude", "");
        Path dir = Files.createTempDirectory("claude-tab");
        Path fake = dir.resolve("fake-claude");
        Files.writeString(fake, """
                #!/bin/sh
                echo "FAKE CLAUDE in $(pwd)"
                echo "size $(stty size)"
                read line
                echo "got: $line"
                echo "now $(stty size)"
                sleep 1
                """);
        fake.toFile().setExecutable(true);
        Settings.setClaudePath(fake.toString());

        MainFrame[] fr = new MainFrame[1];
        ui(() -> { fr[0] = new MainFrame(); fr[0].setBounds(0, 0, 1180, 880); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        Thread.sleep(800);
        JTabbedPane tabs = (JTabbedPane) field(f, "bottomTabs");
        ClaudeTerminal t = (ClaudeTerminal) field(f, "claude");
        ui(() -> check(tabs.getTabCount() == 2 && tabs.getTitleAt(1).equals("Claude Code") && tabs.getSelectedIndex() == 0,
                "the Output tab, and Claude Code beside it"));
        check(screen(t).isEmpty(), "nothing starts before the tab is opened");

        ui(() -> tabs.setSelectedIndex(1));
        check(waitFor(t, "FAKE CLAUDE in " + System.getProperty("user.home"), 5000), "the tab starts claude (with no program open: in the home folder)");
        check(waitFor(t, "size ", 3000) && !screen(t).contains("size 0 0"), "it runs in a terminal with a size: " + screen(t).lines().filter(l -> l.startsWith("size")).findFirst().orElse("?"));
        type(t, "hello\r");
        check(waitFor(t, "got: hello", 3000), "keys typed in the tab reach it");
        check(waitFor(t, "now ", 3000) && !screen(t).contains("now 30 120"), "the size follows the tab: "
                + screen(t).lines().filter(l -> l.startsWith("now")).findFirst().orElse("?").strip());
        shot(f, "fake-claude");
        Thread.sleep(2500);
        JButton start = (JButton) field(t, "startBtn");
        JLabel msg = (JLabel) field(t, "message");
        ui(() -> check(msg.isShowing() && msg.getText().contains("ended") && start.isShowing(), "when it ends, the tab offers to start it again"));

        java.lang.reflect.Method clear = MainFrame.class.getDeclaredMethod("clearOutput");
        clear.setAccessible(true);
        ui(() -> { try { clear.invoke(f); } catch (Exception e) { throw new RuntimeException(e); } });
        ui(() -> check(tabs.getSelectedIndex() == 0, "running a program shows Output again"));

        Settings.setClaudePath(dir.resolve("missing").toString());
        if (!realClaude.isEmpty()) {
            Settings.setClaudePath(realClaude);
            ui(() -> { tabs.setSelectedIndex(1); start.doClick(); });
            check(waitFor(t, "Claude", 20000), "Claude Code itself starts in the tab");
            Thread.sleep(3000);
            shot(f, "claude-code");
        }
        ui(() -> { try { ((ClaudeTerminal) field(f, "claude")).stop(); } catch (Exception e) { throw new RuntimeException(e); } });
        System.out.println(fails == 0 ? "claude tab: all ok" : "claude tab: " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
