package evia.paradigma;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The EVIA logo: window/taskbar icons in several sizes, and the full logo for the About dialog. */
final class AppIcon {
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};
    private static List<Image> icons;
    private static Image logo;

    private AppIcon() {}

    static synchronized List<Image> icons() {
        if (icons == null) {
            icons = new ArrayList<>();
            for (int s : SIZES) {
                Image img = load("icons/icon-" + s + ".png");
                if (img != null) icons.add(img);
            }
        }
        return icons;
    }

    static synchronized Image logo() {
        if (logo == null) logo = load("icons/logo.png");
        return logo;
    }

    /** Sets the icon on a window, and once on the taskbar/dock where supported. */
    static void apply(Window w) {
        List<Image> list = icons();
        if (list.isEmpty()) return;
        w.setIconImages(list);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(list.get(list.size() - 1));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // not available on this platform
        }
    }

    static void showAbout(Component parent) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));
        Image l = logo();
        if (l != null) {
            JLabel img = new JLabel(new ImageIcon(l.getScaledInstance(220, 220, Image.SCALE_SMOOTH)));
            p.add(img, BorderLayout.NORTH);
        }
        JLabel text = new JLabel("<html><div style='text-align:center'><b style='font-size:15pt'>" + App.name() + " " + App.VERSION + "</b><br>"
                + "Visual block programming compiled straight to executables<br><br>"
                + "Java " + System.getProperty("java.version") + "<br>"
                + "github.com/sbrutyan01/EVIA-Paradigma-Native<br>Forked from EVIA Paradigma 0.11.0<br>MIT License</div></html>", SwingConstants.CENTER);
        p.add(text, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(parent, p, "About " + App.name(), JOptionPane.PLAIN_MESSAGE);
    }

    private static Image load(String name) {
        try (InputStream in = AppIcon.class.getResourceAsStream(name)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
