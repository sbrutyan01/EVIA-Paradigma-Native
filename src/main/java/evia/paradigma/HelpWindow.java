package evia.paradigma;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The user guide (bundled HTML with a topic list) and the other Help menu actions. */
final class HelpWindow extends JFrame {
    static final String REPO = "https://github.com/sbrutyan01/EVIA-Paradigma-Native";

    /** Guide languages: code, name (in that language), window title, topic names (same order as ANCHORS). */
    private record Lang(String code, String name, String title, String[] topics) {}

    private static final String[] ANCHORS = {"start", "blocks", "expressions", "classes", "modules", "projects", "git", "cpp", "jvm",
            "boards", "fpga", "linux", "keys", "vim"};

    private static final Lang[] LANGS = {
            new Lang("en", "English", "EVIA Paradigma Guide", new String[]{"Getting started", "Blocks", "Expressions",
                    "Classes and objects", "Modules", "Projects", "Git and GitHub", "C and C++ code", "Java, Kotlin and Android",
                    "Arduino and ESP32 boards", "FPGA: Artix-7 and GateMate", "Linux images", "Keyboard shortcuts", "Vim keys"}),
            new Lang("ru", "Русский", "Руководство EVIA Paradigma", new String[]{"Начало работы", "Блоки", "Выражения",
                    "Классы и объекты", "Модули", "Проекты", "Git и GitHub", "Код на C и C++", "Java, Kotlin и Android",
                    "Платы Arduino и ESP32", "FPGA: Artix-7 и GateMate", "Образы Linux", "Горячие клавиши", "Клавиши Vim"}),
            new Lang("hy", "Հայերեն", "EVIA Paradigma ուղեցույց", new String[]{"Սկսել", "Բլոկներ", "Արտահայտություններ",
                    "Դասեր և օբյեկտներ", "Մոդուլներ", "Նախագծեր", "Git և GitHub", "C և C++ կոդ", "Java, Kotlin և Android",
                    "Arduino և ESP32 տախտակներ", "FPGA՝ Artix-7 և GateMate", "Linux պատկերներ", "Ստեղնային համակցություններ", "Vim ստեղներ"}),
    };

    private static HelpWindow instance;
    private final JEditorPane page = new JEditorPane();
    private final JList<String> list = new JList<>();
    private final JComboBox<String> language = new JComboBox<>();
    private Lang lang = LANGS[0];
    private String anchor = "start";
    private boolean loading;

    private HelpWindow() {
        super("EVIA Paradigma Guide");
        AppIcon.apply(this);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(980, 720);
        page.setEditable(false);
        page.setContentType("text/html");
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        page.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
                if (e.getDescription().startsWith("#")) page.scrollToReference(e.getDescription().substring(1));
                else browse(this, e.getDescription());
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(28);
        list.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            if (!e.getValueIsAdjusting() && !loading && i >= 0) {
                anchor = ANCHORS[i];
                page.scrollToReference(anchor);
            }
        });
        for (Lang l : LANGS) language.addItem(l.name());
        language.setToolTipText(Text.t("Language of the guide"));
        language.addActionListener(e -> {
            int i = language.getSelectedIndex();
            if (!loading && i >= 0 && LANGS[i] != lang) {
                lang = LANGS[i];
                Settings.setHelpLanguage(lang.code());
                reload();
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        left.add(language, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(260, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(page));
        split.setDividerLocation(260);
        getContentPane().add(split);

        String saved = Settings.helpLanguage();
        for (Lang l : LANGS) if (l.code().equals(saved)) lang = l;
        reload();
        Theme.onChange(this::reload);
    }

    /** Shows the guide in the current language and theme, keeping the current topic. */
    private void reload() {
        loading = true;
        try {
            setTitle(lang.title());
            language.setSelectedItem(lang.name());
            DefaultListModel<String> model = new DefaultListModel<>();
            for (String t : lang.topics()) model.addElement(t);
            list.setModel(model);
            list.setSelectedIndex(java.util.Arrays.asList(ANCHORS).indexOf(anchor));
            String file = lang.code().equals("en") ? "help/guide.html" : "help/guide." + lang.code() + ".html";
            String html;
            try (InputStream in = HelpWindow.class.getResourceAsStream(file)) {
                html = in == null ? "<html>Guide not found.</html>" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                html = "<html>Guide not available: " + e.getMessage() + "</html>";
            }
            javax.swing.text.html.HTMLEditorKit kit = new javax.swing.text.html.HTMLEditorKit();
            javax.swing.text.html.StyleSheet css = new javax.swing.text.html.StyleSheet();
            Color bg = Theme.codeBg();
            Color fg = Theme.plain();
            Color rule = Theme.dark() ? new Color(85, 88, 92) : new Color(190, 194, 204);
            Color head = Theme.dark() ? new Color(60, 63, 65) : new Color(238, 240, 246);
            css.addRule("body { font-family: " + Fonts.uiCss() + "; font-size: 13pt; margin: 14px 22px; background-color: " + Theme.hex(bg)
                    + "; color: " + Theme.hex(fg) + "; }");
            css.addRule("h1 { font-size: 24pt; font-weight: bold; margin-top: 4px; } h2 { font-size: 17pt; font-weight: bold; margin-top: 22px; margin-bottom: 6px; }");
            css.addRule("p, ul, ol { margin-top: 6px; margin-bottom: 6px; }");
            css.addRule("code, pre { font-family: " + Fonts.monoCss() + "; font-size: 12pt; }");
            css.addRule("a { color: " + Theme.hex(Theme.dark() ? new Color(110, 165, 255) : new Color(30, 90, 190)) + "; }");
            css.addRule("table { border-color: " + Theme.hex(rule) + "; } th { background-color: " + Theme.hex(head)
                    + "; } td, th { border-color: " + Theme.hex(rule) + "; }");
            kit.setStyleSheet(css);
            page.setEditorKit(kit);
            page.setText(html);
            page.setCaretPosition(0);
            SwingUtilities.invokeLater(() -> page.scrollToReference(anchor));
        } finally {
            loading = false;
        }
    }

    static void showTopic(Component parent, String anchor) {
        if (instance == null) {
            instance = new HelpWindow();
            instance.setLocationRelativeTo(parent);
        }
        instance.setVisible(true);
        instance.toFront();
        instance.anchor = anchor;
        instance.loading = true;
        instance.list.setSelectedIndex(java.util.Arrays.asList(ANCHORS).indexOf(anchor));
        instance.loading = false;
        SwingUtilities.invokeLater(() -> instance.page.scrollToReference(anchor));
    }

    static void browse(Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException e) {
            // fall through and show the address
        }
        JTextField f = new JTextField(url);
        f.setEditable(false);
        JOptionPane.showMessageDialog(parent, new Object[]{"Open this address in your browser:", f}, "Link",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** The languages of the tutorial PDFs bundled with the application: code and the name shown in the menu. */
    static final String[][] TUTORIALS = {
            {"en", "English"}, {"ru", "Русский"}, {"hy", "Հայերեն"}, {"de", "Deutsch"},
            {"zh-CN", "简体中文"}, {"zh-TW", "繁體中文"}, {"ja", "日本語"}
    };

    /** Opens a bundled tutorial PDF in the system's PDF viewer. */
    static void openTutorial(Component parent, String lang) {
        String name = "EVIA-Paradigma-Tutorial." + lang + ".pdf";
        try (java.io.InputStream in = HelpWindow.class.getResourceAsStream("help/" + name)) {
            if (in == null) throw new IOException("the tutorial is not part of this build");
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("evia-tutorial");
            java.nio.file.Path file = dir.resolve(name);
            java.nio.file.Files.copy(in, file);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toFile());
                return;
            }
            JOptionPane.showMessageDialog(parent, "No PDF viewer is available. The tutorial was saved as\n" + file,
                    "Tutorial", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException e) {
            browse(parent, REPO + "/blob/main/docs/tutorial/" + name);   // the same file on GitHub
        }
    }

    /** Asks GitHub for the latest release and compares it with this version. */
    static void checkForUpdates(Component parent) {
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                HttpURLConnection c = (HttpURLConnection) URI.create(
                        "https://api.github.com/repos/sbrutyan01/EVIA-Paradigma-Native/releases/latest").toURL().openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "EVIA-Paradigma/" + App.VERSION);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    return m.find() ? m.group(1) : null;
                }
            }

            @Override protected void done() {
                try {
                    String tag = get();
                    if (tag == null) throw new IOException("no release found");
                    String latest = tag.replaceFirst("^[vV]", "");
                    if (compare(latest, App.VERSION) > 0) {
                        int r = JOptionPane.showConfirmDialog(parent, "EVIA Paradigma " + latest + " is available (you have "
                                + App.VERSION + ").\nOpen the download page?", "Update available", JOptionPane.YES_NO_OPTION);
                        if (r == JOptionPane.YES_OPTION) browse(parent, REPO + "/releases/latest");
                    } else {
                        JOptionPane.showMessageDialog(parent, "You have the latest version (" + App.VERSION + ").",
                                "Check for updates", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(parent, "Could not reach GitHub: " + c.getMessage()
                            + "\n\nReleases: " + REPO + "/releases", "Check for updates", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    /** Compares dotted versions numerically ("0.10.0" > "0.9.1"). */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
