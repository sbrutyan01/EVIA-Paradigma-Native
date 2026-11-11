package evia.paradigma;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Main window: menu bar, toolbar, palette, main program canvas and output console. */
final class MainFrame extends JFrame {
    static final String APP = App.name();
    private final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    /** Bundled examples: file name (in resources/evia/paradigma/examples) and menu title. */
    static final String[][] EXAMPLES = {
            {"tutorial.evia", "Tutorial: a loop and a Counter class"},
            {"classes.evia", "Classes, objects and a module"},
            {"counters.evia", "Counters (objects keep their fields)"},
            {"loops.evia", "Loops: for, while, do-while"},
            {"factorial.evia", "Factorial (input)"},
            {"expressions.evia", "Expressions"},
            {"example.evia", "Even/odd and average"},
    };
    /** Programs for Arduino, ESP32, AVR, PIC32 and STM32 boards (they use the Board blocks; Run simulates an Uno). */
    static final String[][] BOARD_EXAMPLES = {
            {"boards/blink.evia", "Blink the built-in LED"},
            {"boards/button.evia", "Button: read a pin, light the LED"},
            {"boards/fade.evia", "Fade an LED with PWM"},
            {"boards/analog_meter.evia", "Analog meter (A0)"},
            {"boards/led_class.evia", "Led class: an object with a pin"},
            {"boards/uart_echo.evia", "UART: send a byte, read it back"},
            {"boards/spi_loopback.evia", "SPI: transfer a byte"},
            {"boards/i2c_memory.evia", "I2C: write and read a device register"},
            {"boards/mqtt_sensor.evia", "MQTT: publish a value, read a command back"},
            {"boards/mqtt_esk2.evia", "MQTT on the PIC32 Ethernet Starter Kit II"},
    };
    /** The board families of File > Open Example, and the examples each one can build (MQTT needs a network). */
    enum ExampleFamily { ARDUINO, ESP32, PIC32, STM32 }

    static boolean exampleFits(String file, ExampleFamily f) {
        boolean mqtt = file.contains("mqtt");
        return switch (f) {
            case ARDUINO -> !mqtt;
            case ESP32 -> !file.contains("esk2");
            case PIC32 -> true;
            case STM32 -> !mqtt;
        };
    }
    /** Programs for FPGA boards (LED0, SW0, BTN0 ... are the pins of Digilent boards; Run simulates them like an Uno). */
    static final String[][] FPGA_EXAMPLES = {
            {"fpga/blink.evia", "Blink LED0"},
            {"fpga/switches.evia", "Switches control LEDs"},
            {"fpga/counter.evia", "Binary counter on the LEDs, printed over the serial port"},
            {"fpga/button_counter.evia", "Count button presses"},
            {"fpga/fade.evia", "Fade an LED with PWM"},
            {"fpga/uart_hello.evia", "Serial port: hello, then echo"},
    };
    /** Programs for Android phones (Language Android; the sensor examples use the Phone Sensor block, which Run simulates). */
    static final String[][] ANDROID_EXAMPLES = {
            {"android/hello.evia", "Hello, world: print and ask a name"},
            {"android/gps.evia", "GPS viewer: latitude, longitude, altitude"},
            {"android/motion.evia", "Motion tester: accelerometer and gyroscope"},
    };
    static final String[] EXAMPLE_FILES = {"modules/counter.eviam"};

    private final Library lib = new Library();
    private final FlowCanvas canvas = new FlowCanvas(lib, null);
    private final Map<BlockDef, ClassEditor> editors = new HashMap<>();
    private final JTextArea console = new JTextArea();
    /** Output and the Claude Code terminal, as tabs under the canvas. */
    private final JTabbedPane bottomTabs = new JTabbedPane();
    private final ClaudeTerminal claude = new ClaudeTerminal(this::workFolder);
    private final JSlider speed = new JSlider(0, 1000, 300);
    private final JLabel status = new JLabel(" ");
    private final Action runAction, stopAction;
    private final List<JMenuItem> gitItems = new ArrayList<>();
    private JMenuItem gitInitItem;
    private SwingWorker<Void, Void> worker;
    private boolean gitBusy;
    private File currentFile;
    private Project project;
    private String savedSnapshot = "";
    private CodeViewer codeViewer;

    MainFrame() {
        super(APP);
        AppIcon.apply(this);
        lib.main = canvas;
        lib.editorOpener = this::openEditor;
        lib.sourceViewer = this::viewSource;
        lib.addListener(this::closeEditorsOfDeletedDefs);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        setSize(1250, 880);
        setLocationRelativeTo(null);

        runAction = action("Run", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> run());
        stopAction = action("Stop", KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.SHIFT_DOWN_MASK), e -> {
            if (worker != null) worker.cancel(true);
        });
        stopAction.setEnabled(false);
        setJMenuBar(menuBar());

        // ---- toolbar
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("New", e -> newProgram()));
        tb.add(button("Open...", e -> open()));
        tb.add(button("Save", e -> save()));
        tb.addSeparator();
        tb.add(button("New class...", e -> newClass()));
        tb.add(button("Group into class", e -> canvas.groupSelection()));
        tb.addSeparator();
        if (App.nativeEdition()) {
            JButton build = button("Build executable", e -> buildExecutable(false));
            build.setToolTipText(Text.t("Compile the blocks straight to a program for this computer (F7)"));
            buildButton = build;
            tb.add(build);
            tb.add(new TargetBar());
            JButton runExe = button("Run executable", e -> buildExecutable(true));
            runExe.setToolTipText(Text.t("Build, then run the program; Input goes to it from the field under the output (F6)"));
            runButton = runExe;
            tb.add(runExe);
            JButton export = button("Export app...", e -> exportApp());
            exportButton = export;
            export.setToolTipText(Text.t("The program as an app for any Linux: the executable, a portable .tar.gz and a .deb"));
            tb.add(export);
        } else {
            tb.add(button("Generate code", e -> generateCode()));
            tb.add(new TargetBar());
        }
        tb.addSeparator();
        tb.add(new JButton(runAction));
        tb.add(new JButton(stopAction));
        tb.addSeparator();
        JButton terminal = button("Terminal", e -> TerminalWindow.open(this));
        terminal.setToolTipText(Text.t("A terminal on a board's serial port (UART) or on another computer (SSH)"));
        if (!App.nativeEdition()) tb.add(terminal);
        tb.addSeparator();
        tb.add(new JLabel(Text.t(" Step delay: ")));
        speed.setMaximumSize(new Dimension(160, 30));
        speed.setPreferredSize(new Dimension(130, 30));
        speed.setToolTipText(Text.t("Delay between blocks (0 = full speed)"));
        speed.addChangeListener(e -> status.setText("Step delay: " + speed.getValue() + " ms"));
        tb.add(speed);

        // ---- console
        console.setEditable(false);
        console.setFont(Fonts.mono(Font.PLAIN, 13));
        Runnable consoleColors = () -> {
            console.setBackground(Theme.consoleBg());
            console.setForeground(Theme.consoleFg());
            console.setCaretColor(Theme.consoleFg());
        };
        consoleColors.run();
        Theme.onChange(consoleColors);
        console.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createEmptyBorder());
        JComponent consoleBox = consoleScroll;
        if (App.nativeEdition()) {
            // Input for a running executable: a line typed here goes to its stdin
            exeInput.setEnabled(false);
            exeInput.setToolTipText(Text.t("Type a line and press Enter to send it to the running program"));
            exeInput.addActionListener(e -> sendToExecutable());
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.add(new JLabel(Text.t(" Input: ")), BorderLayout.WEST);
            row.add(exeInput, BorderLayout.CENTER);
            JPanel box = new JPanel(new BorderLayout());
            box.add(consoleScroll, BorderLayout.CENTER);
            box.add(row, BorderLayout.SOUTH);
            consoleBox = box;
        }

        // ---- layout
        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(16);
        canvasScroll.getHorizontalScrollBar().setUnitIncrement(16);

        bottomTabs.addTab(Text.t("Output"), consoleBox);
        bottomTabs.addTab("Claude Code", claude);
        bottomTabs.setToolTipTextAt(1, Text.t("Claude Code, Anthropic's AI coding assistant, in a terminal in the program's folder"));
        bottomTabs.addChangeListener(e -> {
            if (bottomTabs.getSelectedComponent() == claude) claude.showing();
        });
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, canvasScroll, bottomTabs);
        right.setResizeWeight(0.78);

        JPanel paletteBox = new JPanel(new BorderLayout());
        paletteBox.add(brand(), BorderLayout.NORTH);
        paletteBox.add(new PalettePanel(lib, canvas), BorderLayout.CENTER);
        JScrollPane paletteScroll = new JScrollPane(paletteBox);
        paletteScroll.setBorder(BorderFactory.createEmptyBorder());
        paletteScroll.setPreferredSize(new Dimension(220, 100));

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paletteScroll, right);
        main.setDividerLocation(220);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.setText(Text.t("Drag blocks from the palette, connect them, press Run (F5). Help > Guide (F1) explains everything."));

        getContentPane().add(tb, BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(statusBar(), BorderLayout.SOUTH);

        Settings.onChange(() -> {
            if (project != null && !App.nativeEdition()) {
                try {
                    project.setTarget(Settings.target());
                } catch (IOException e) {
                    status.setText("Could not save the language in the project: " + e.getMessage());
                }
            }
        });
        installMacHandlers();
        ProgramIO.loadExample(lib);
        markSaved();
        updateTitle();
    }

    // ================================================================ menus

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = menu("File", KeyEvent.VK_F);
        file.add(item("New Program", KeyStroke.getKeyStroke(KeyEvent.VK_N, MENU), e -> newProgram()));
        file.add(item("New Project...", KeyStroke.getKeyStroke(KeyEvent.VK_N, MENU | InputEvent.SHIFT_DOWN_MASK), e -> newProject()));
        file.addSeparator();
        file.add(item("Open...", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU), e -> open()));
        file.add(item("Open Project...", KeyStroke.getKeyStroke(KeyEvent.VK_O, MENU | InputEvent.SHIFT_DOWN_MASK), e -> openProject()));
        file.add(examplesMenu("Open Example"));
        file.addSeparator();
        file.add(item("Save", KeyStroke.getKeyStroke(KeyEvent.VK_S, MENU), e -> save()));
        file.add(item("Save As...", KeyStroke.getKeyStroke(KeyEvent.VK_S, MENU | InputEvent.SHIFT_DOWN_MASK), e -> saveAs()));
        file.addSeparator();
        file.add(item("Import Module...", null, e -> importModule()));
        file.add(item("Export Classes as Module...", null, e -> exportModule()));
        file.addSeparator();
        file.add(item("Close Project", null, e -> closeProject()));
        if (!ExternalEditor.MAC) {
            file.addSeparator();
            file.add(item("Exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, MENU), e -> exit()));
        }
        bar.add(file);

        JMenu edit = menu("Edit", KeyEvent.VK_E);
        edit.add(item("Select All Blocks", KeyStroke.getKeyStroke(KeyEvent.VK_A, MENU), e -> canvas.selectAll()));
        edit.add(item("Delete Selected Blocks", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> canvas.deleteSelection()));
        edit.addSeparator();
        edit.add(item("New Class...", null, e -> newClass()));
        edit.add(item("Group Selection into Class", KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU), e -> canvas.groupSelection()));
        edit.add(classesMenu());
        bar.add(edit);
        // the canvas has the same keys; with the menu present let the menu own them
        for (KeyStroke ks : new KeyStroke[]{KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_A, MENU), KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU)}) {
            canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(ks);
        }

        JMenu view = menu("View", KeyEvent.VK_V);
        view.add(item("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MENU), e -> canvas.zoomIn()));
        view.add(item("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, MENU), e -> canvas.zoomOut()));
        view.add(item("Actual Size (100%)", KeyStroke.getKeyStroke(KeyEvent.VK_0, MENU), e -> canvas.zoomReset()));
        view.add(item("Fit Diagram in Window", KeyStroke.getKeyStroke(KeyEvent.VK_9, MENU), e -> canvas.zoomToFit()));
        view.addSeparator();
        JMenu theme = new JMenu(Text.t("Theme"));
        ButtonGroup themeGroup = new ButtonGroup();
        for (Theme.Mode m : Theme.Mode.values()) {
            String label = switch (m) { case SYSTEM -> "Same as system"; case LIGHT -> "Light (IntelliJ)"; case DARK -> "Dark (Darcula)"; };
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(Text.t(label), Settings.themeMode() == m);
            it.addActionListener(e -> Theme.apply(m));
            themeGroup.add(it);
            theme.add(it);
        }
        view.add(theme);
        view.addSeparator();
        JMenuItem wheelHelp = new JMenuItem(Text.t("Mouse wheel zooms toward the pointer (setting in Settings)"));
        wheelHelp.setEnabled(false);
        view.add(wheelHelp);
        bar.add(view);
        // the "+" and "-" keys of the number pad and Ctrl+Shift+= (Ctrl++) zoom too
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, MENU), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MENU | InputEvent.SHIFT_DOWN_MASK), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, MENU), "zoomOut");
        am.put("zoomIn", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { canvas.zoomIn(); }
        });
        am.put("zoomOut", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { canvas.zoomOut(); }
        });

        JMenu run = menu("Run", KeyEvent.VK_R);
        run.add(new JMenuItem(runAction));
        run.add(new JMenuItem(stopAction));
        run.addSeparator();
        if (App.nativeEdition()) {
            buildItem = item("Build Executable", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), e -> buildExecutable(false));
            runItem = item("Run Executable", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), e -> buildExecutable(true));
            run.add(buildItem);
            run.add(runItem);
            run.add(item("Stop Executable", null, e -> stopExecutable()));
            run.add(item("Chip Upload Settings...", null, e -> ChipRunner.choose(this, Settings.nativeChip())));
            run.addSeparator();
            exportItem = item("Export App...", KeyStroke.getKeyStroke(KeyEvent.VK_E, MENU | InputEvent.SHIFT_DOWN_MASK), e -> exportApp());
            run.add(exportItem);
            nativeLabels();
            Settings.onChange(() -> SwingUtilities.invokeLater(this::nativeLabels));
        } else {
            run.add(item("Generate Code", KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), e -> generateCode()));
            run.add(targetMenu());
        }
        run.add(item("Clear Output", null, e -> console.setText("")));
        bar.add(run);

        JMenu git = menu("Git", KeyEvent.VK_G);
        gitInitItem = item("Initialize Repository", null, e -> gitInit());
        git.add(gitInitItem);
        git.add(item("Commit...", KeyStroke.getKeyStroke(KeyEvent.VK_K, MENU), e -> gitCommit()));
        git.add(gitItem(item("Push", KeyStroke.getKeyStroke(KeyEvent.VK_K, MENU | InputEvent.SHIFT_DOWN_MASK), e -> gitPush())));
        git.add(gitItem(item("Pull", null, e -> gitPull())));
        git.addSeparator();
        git.add(item("Connect to GitHub...", null, e -> gitConnect()));
        git.add(gitItem(item("Open on GitHub", null, e -> gitOpenWeb())));
        git.addSeparator();
        git.add(gitItem(item("Status", null, e -> gitShow("Status"))));
        git.add(gitItem(item("History", null, e -> gitShow("History"))));
        git.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) { updateGitMenu(); }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        bar.add(git);
        if (!App.nativeEdition()) bar.add(linuxMenu());

        bar.add(settingsMenu());
        if (!App.nativeEdition()) bar.add(devicesMenu());

        JMenu help = menu("Help", KeyEvent.VK_H);
        help.add(item("Guide", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), e -> HelpWindow.showTopic(this, "start")));
        help.add(item("Classes and Objects", null, e -> HelpWindow.showTopic(this, "classes")));
        help.add(item("Expressions", null, e -> HelpWindow.showTopic(this, "expressions")));
        help.add(item("Projects and Git", null, e -> HelpWindow.showTopic(this, "projects")));
        help.add(item("Keyboard Shortcuts", null, e -> HelpWindow.showTopic(this, "keys")));
        help.add(item("Vim Keys", null, e -> HelpWindow.showTopic(this, "vim")));
        help.add(examplesMenu("Examples"));
        if (!App.nativeEdition()) help.add(item("Terminal (UART, SSH)", null, e -> TerminalWindow.open(this)));
        JMenu tutorial = new JMenu(Text.t("Tutorial (PDF)"));
        for (String[] t : HelpWindow.TUTORIALS) tutorial.add(item(t[1], null, e -> HelpWindow.openTutorial(this, t[0])));
        help.add(tutorial);
        help.addSeparator();
        help.add(item("Online Documentation", null, e -> HelpWindow.browse(this, HelpWindow.REPO + "#readme")));
        help.add(item("Report an Issue...", null, e -> HelpWindow.browse(this, HelpWindow.REPO + "/issues/new")));
        help.add(item("Check for Updates...", null, e -> PackagesDialog.show(this, false)));
        if (!ExternalEditor.MAC) {
            help.addSeparator();
            help.add(item(App.nativeEdition() ? "About EVIA Paradigma Native" : "About EVIA Paradigma", null, e -> AppIcon.showAbout(this)));
        }
        bar.add(help);
        return bar;
    }

    private static JMenu menu(String text, int mnemonic) {
        JMenu m = new JMenu(Text.t(text));
        if (!ExternalEditor.MAC) m.setMnemonic(mnemonic);
        return m;
    }

    private static JMenuItem item(String text, KeyStroke key, java.awt.event.ActionListener l) {
        JMenuItem i = new JMenuItem(Text.t(text));
        if (key != null) i.setAccelerator(key);
        i.addActionListener(l);
        return i;
    }

    /** Run > Code: the same choices as the drop-down lists (language, flow, values) as radio items. */
    private JMenu targetMenu() {
        JMenu m = new JMenu(Text.t("Code Language and Mode"));
        m.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                m.removeAll();
                Target cur = Settings.target();
                ButtonGroup g1 = new ButtonGroup(), g2 = new ButtonGroup(), g3 = new ButtonGroup();
                for (Target.Lang l : Target.Lang.values()) {
                    if (l == Target.Lang.CPP98 || l == Target.Lang.PYTHON || l == Target.Lang.JAVA || l == Target.Lang.ARDUINO
                            || l == Target.Lang.SYSTEMVERILOG) m.addSeparator();
                    JRadioButtonMenuItem i = new JRadioButtonMenuItem(l.display + "   (" + Text.t(l.note) + ")", l == cur.lang());
                    i.addActionListener(ev -> Settings.setTarget(Settings.target().withLang(l)));
                    g1.add(i);
                    m.add(i);
                }
                m.addSeparator();
                for (Target.Flow f : Target.Flow.values()) {
                    JRadioButtonMenuItem i = new JRadioButtonMenuItem(Text.t(f.display + " flow")
                            + "   (" + Text.t(f == Target.Flow.STRUCTURED ? "if/else, loops, switch" : "labels and jumps") + ")", f == cur.flow());
                    i.addActionListener(ev -> Settings.setTarget(Settings.target().withFlow(f)));
                    i.setEnabled(!cur.lang().fpga());
                    g2.add(i);
                    m.add(i);
                }
                m.addSeparator();
                for (Target.Values v : Target.Values.values()) {
                    JRadioButtonMenuItem i = new JRadioButtonMenuItem(Text.t(v.display + " values")
                            + "   (" + Text.t(v == Target.Values.SAFE ? "numbers and text, all checks" : "double numbers, no checks") + ")", v == cur.values());
                    i.addActionListener(ev -> Settings.setTarget(Settings.target().withValues(v)));
                    i.setEnabled(!cur.embedded());
                    g3.add(i);
                    m.add(i);
                }
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        return m;
    }

    /** Linux: custom Linux images (Buildroot, Yocto, OpenWrt) for the BeagleBone Black and the Raspberry Pi 5. */
    private JMenu linuxMenu() {
        JMenu m = menu("Linux", KeyEvent.VK_L);
        m.add(item("New Linux Image...", null, e -> LinuxWindow.open(this, LinuxWindow.Then.NEW)));
        m.add(item("Open Linux Image Folder...", null, e -> LinuxWindow.openFolder(this)));
        m.add(item("Linux Images Window", null, e -> LinuxWindow.open(this, LinuxWindow.Then.NOTHING)));
        m.addSeparator();
        m.add(item("Build Image", null, e -> LinuxWindow.open(this, LinuxWindow.Then.BUILD)));
        m.add(item("Write Image to SD Card...", null, e -> LinuxWindow.open(this, LinuxWindow.Then.WRITE)));
        m.add(item("Check This Computer", null, e -> LinuxWindow.open(this, LinuxWindow.Then.CHECK)));
        m.addSeparator();
        m.add(item("Terminal on the Board (UART, SSH)", null, e -> TerminalWindow.open(this)));
        m.add(item("Linux Images Guide", null, e -> HelpWindow.showTopic(this, "linux")));
        return m;
    }

    private JMenuItem gitItem(JMenuItem i) {
        gitItems.add(i);
        return i;
    }

    private Action action(String name, KeyStroke key, java.awt.event.ActionListener l) {
        Action a = new AbstractAction(Text.t(name)) {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                l.actionPerformed(e);
            }
        };
        a.putValue(Action.ACCELERATOR_KEY, key);
        return a;
    }

    /** Settings: one entry per page, each opening the Settings window at that page. */
    private JMenu settingsMenu() {
        JMenu m = menu("Settings", KeyEvent.VK_S);
        m.add(item("General...", ExternalEditor.MAC ? null : KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, MENU), e -> Settings.showDialog(this, Settings.Page.GENERAL)));
        m.add(item(App.nativeEdition() ? "Updates..." : "Packages and Updates...", null, e -> PackagesDialog.show(this, false)));
        if (App.nativeEdition()) return m;   // no languages, no boards
        m.addSeparator();
        m.add(item("C, C++ and Python...", null, e -> Settings.showDialog(this, Settings.Page.DESKTOP)));
        m.add(item("Java and Kotlin...", null, e -> Settings.showDialog(this, Settings.Page.JVM)));
        m.add(item("Android...", null, e -> Settings.showDialog(this, Settings.Page.ANDROID)));
        m.addSeparator();
        m.add(item("Arduino Boards...", null, e -> Settings.showDialog(this, Settings.Page.ARDUINO)));
        m.add(item("ESP32 Boards...", null, e -> Settings.showDialog(this, Settings.Page.ESP32)));
        m.add(item("AVR C (bare metal)...", null, e -> Settings.showDialog(this, Settings.Page.AVR)));
        m.add(item("PIC32...", null, e -> Settings.showDialog(this, Settings.Page.PIC32)));
        m.add(item("STM32...", null, e -> Settings.showDialog(this, Settings.Page.STM32)));
        m.add(item("FPGA Boards...", null, e -> Settings.showDialog(this, Settings.Page.FPGA)));
        return m;
    }

    /**
     * Devices: one submenu per family with its boards (or chips, or languages for this computer).
     * Picking one makes it the target: the language of that family and that board, as the toolbar
     * lists would. The current choice has its dot; each family ends with its page of Settings.
     */
    private JMenu devicesMenu() {
        JMenu m = menu("Devices", KeyEvent.VK_D);
        m.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                m.removeAll();
                Target cur = Settings.target();
                Target.Lang lang = cur.lang();
                Board board = Settings.board(lang);

                JMenu pc = new JMenu(Text.t("This Computer (C, C++, Python, Java, Kotlin)"));
                for (Target.Lang l : Target.Lang.values()) {
                    if (l.embedded() || l == Target.Lang.ANDROID) continue;
                    if (l == Target.Lang.CPP98 || l == Target.Lang.PYTHON || l == Target.Lang.JAVA) pc.addSeparator();
                    pc.add(choice(l.display + "   (" + l.tag() + ")", l == lang, () -> Settings.setTarget(cur.withLang(l))));
                }
                pc.addSeparator();
                pc.add(item("C, C++ and Python Settings...", null, ev -> Settings.showDialog(MainFrame.this, Settings.Page.DESKTOP)));
                pc.add(item("Java and Kotlin Settings...", null, ev -> Settings.showDialog(MainFrame.this, Settings.Page.JVM)));
                m.add(pc);
                JMenu phone = new JMenu(Text.t("Android Phones and Tablets"));
                phone.add(choice(Text.t("Android app (Kotlin)"), lang == Target.Lang.ANDROID,
                        () -> Settings.setTarget(cur.withLang(Target.Lang.ANDROID))));
                phone.addSeparator();
                phone.add(item("Android Settings...", null, ev -> Settings.showDialog(MainFrame.this, Settings.Page.ANDROID)));
                m.add(phone);
                m.addSeparator();

                m.add(family(Text.t("Arduino Boards"), Board::isArduinoAvr, Target.Lang.ARDUINO, lang, board, Settings.Page.ARDUINO,
                        Text.t("Arduino Settings...")));
                m.add(family(Text.t("ESP32 Boards"), Board::isEsp32, Target.Lang.ARDUINO, lang, board, Settings.Page.ESP32,
                        Text.t("ESP32 Settings...")));
                m.add(family(Text.t("AVR C (bare metal)"), Board::isArduinoAvr, Target.Lang.AVR, lang, board, Settings.Page.AVR,
                        Text.t("AVR C Settings...")));
                m.add(family(Text.t("PIC32 Chips"), Board::isPic32, Target.Lang.PIC32, lang, board, Settings.Page.PIC32,
                        Text.t("PIC32 Settings...")));
                m.add(family(Text.t("STM32 C (bare metal)"), Board::isStm32, Target.Lang.STM32, lang, board, Settings.Page.STM32,
                        Text.t("STM32 Settings...")));
                m.add(family(Text.t("STM32 Boards (Arduino)"), Board::isStm32, Target.Lang.ARDUINO, lang, board, Settings.Page.STM32,
                        Text.t("STM32 Settings...")));
                m.add(family(Text.t("FPGA Boards"), Board::isFpga, lang.fpga() ? lang : Target.Lang.SYSTEMVERILOG, lang, board,
                        Settings.Page.FPGA, Text.t("FPGA Settings...")));
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        return m;
    }

    /** A device family: its boards as choices, then its Settings page. */
    private JMenu family(String title, java.util.function.Predicate<Board> member, Target.Lang famLang, Target.Lang lang,
                         Board board, Settings.Page page, String settingsText) {
        JMenu f = new JMenu(title);
        for (Board b : Board.values()) {
            if (!member.test(b)) continue;
            boolean on = lang == famLang && board == b;
            f.add(choice(b.display, on, () -> {
                Settings.setTarget(Settings.target().withLang(famLang));
                Settings.setBoard(b);
            }));
        }
        f.addSeparator();
        f.add(item(settingsText, null, ev -> Settings.showDialog(this, page)));
        return f;
    }

    private static JRadioButtonMenuItem choice(String text, boolean selected, Runnable pick) {
        JRadioButtonMenuItem i = new JRadioButtonMenuItem(text, selected);
        i.addActionListener(e -> pick.run());
        return i;
    }

    private JMenu examplesMenu(String title) {
        title = Text.t(title);
        JMenu m = new JMenu(title);
        for (String[] ex : EXAMPLES) m.add(item(ex[1], null, e -> openExample(ex[0])));
        if (App.nativeEdition()) {
            // the board examples the AVR chips run (no SPI, I2C or MQTT); opening one picks a chip if none is
            JMenu chips = new JMenu(Text.t("AVR Chips (ATmega8, 16, 32, Mega 2560)"));
            for (String[] ex : BOARD_EXAMPLES) {
                if (ex[0].contains("spi_") || ex[0].contains("i2c_") || ex[0].contains("mqtt_")) continue;
                chips.add(item(ex[1], null, e -> {
                    if (Settings.nativeChip() == null) Settings.setNativeChip(AvrChip.ATMEGA2560);
                    openExample(ex[0]);
                }));
            }
            m.addSeparator();
            m.add(chips);
            return m;   // the phone and FPGA examples need their languages
        }
        JMenu arduino = new JMenu(Text.t("Arduino Boards (Uno, Nano, Mega ...)"));
        JMenu esp32 = new JMenu(Text.t("ESP32 Boards"));
        JMenu pic32 = new JMenu(Text.t("PIC32 Boards"));
        JMenu stm32 = new JMenu(Text.t("STM32 Boards"));
        for (ExampleFamily f : ExampleFamily.values()) {
            JMenu sub = switch (f) {
                case ARDUINO -> arduino;
                case ESP32 -> esp32;
                case PIC32 -> pic32;
                case STM32 -> stm32;
            };
            for (String[] ex : BOARD_EXAMPLES) {
                if (exampleFits(ex[0], f)) sub.add(item(ex[1], null, e -> openExample(ex[0], f)));
            }
        }
        JMenu fpga = new JMenu(Text.t("FPGA Boards (Artix-7, GateMate)"));
        for (String[] ex : FPGA_EXAMPLES) fpga.add(item(ex[1], null, e -> openExample(ex[0])));
        JMenu android = new JMenu(Text.t("Android Phones"));
        for (String[] ex : ANDROID_EXAMPLES) android.add(item(ex[1], null, e -> openExample(ex[0])));
        m.addSeparator();
        m.add(android);
        m.add(arduino);
        m.add(esp32);
        m.add(pic32);
        m.add(stm32);
        m.add(fpga);
        return m;
    }

    /** Edit > Open Class: one entry per class to open its window. */
    private JMenu classesMenu() {
        JMenu m = new JMenu(Text.t("Open Class"));
        m.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                m.removeAll();
                if (lib.defs().isEmpty()) {
                    JMenuItem none = new JMenuItem(Text.t("(no classes)"));
                    none.setEnabled(false);
                    m.add(none);
                }
                for (BlockDef d : lib.defs()) {
                    m.add(item(d.name + (d.module == null ? "" : "   [" + Path.of(d.module).getFileName() + "]"), null,
                            ev -> openEditor(d)));
                }
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        return m;
    }

    private void installMacHandlers() {
        if (!ExternalEditor.MAC || !Desktop.isDesktopSupported()) return;
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> AppIcon.showAbout(this));
            if (d.isSupported(Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler(e -> Settings.showDialog(this));
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler((e, r) -> {
                if (confirmDiscard()) r.performQuit();
                else r.cancelQuit();
            });
        } catch (RuntimeException ignored) {
            // not on this platform
        }
    }

    /** Status text on the left, zoom control (- 100% +) on the right. */
    private JComponent statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(status, BorderLayout.CENTER);
        JPanel zoomBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton out = smallButton("\u2212", "Zoom out (Ctrl+-, or the mouse wheel)", e -> canvas.zoomOut());
        JButton pct = smallButton("100%", "Actual size (Ctrl+0). Ctrl+9 fits the diagram in the window.", e -> canvas.zoomReset());
        JButton in = smallButton("+", "Zoom in (Ctrl+=, or the mouse wheel)", e -> canvas.zoomIn());
        pct.setPreferredSize(new Dimension(58, pct.getPreferredSize().height));
        canvas.onZoom(() -> pct.setText(Math.round(canvas.zoom() * 100) + "%"));
        zoomBox.add(out);
        zoomBox.add(pct);
        zoomBox.add(in);
        zoomBox.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 6));
        bar.add(zoomBox, BorderLayout.EAST);
        return bar;
    }

    private static JButton smallButton(String text, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(11f));
        b.addActionListener(l);
        return b;
    }

    /** EVIA logo and name at the top of the palette; click for About. */
    private JComponent brand() {
        JLabel l = new JLabel(App.nativeEdition() ? "<html>EVIA Paradigma<br><span style='font-size:11px'>Native</span></html>"
                : Text.t(" EVIA Paradigma"));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 15f));
        for (Image img : AppIcon.icons()) {
            if (img.getWidth(null) == 40) l.setIcon(new ImageIcon(img));
        }
        l.setBorder(BorderFactory.createEmptyBorder(10, 10, 2, 10));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText(Text.t("About EVIA Paradigma"));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { AppIcon.showAbout(MainFrame.this); }
        });
        return l;
    }

    private static JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(Text.t(text));
        b.addActionListener(l);
        return b;
    }

    private void updateTitle() {
        StringBuilder t = new StringBuilder(APP);
        if (project != null) t.append(" - ").append(project.name()).append(" [project]");
        else if (currentFile != null) t.append(" - ").append(currentFile.getName());
        setTitle(t.toString());
    }

    // ================================================================ class editors

    private ClassEditor openEditor(BlockDef d) {
        ClassEditor ed = editors.computeIfAbsent(d, k -> {
            ClassEditor e = new ClassEditor(lib, k);
            e.setLocationRelativeTo(this);
            return e;
        });
        ed.refresh();
        ed.setVisible(true);
        ed.toFront();
        return ed;
    }

    private void closeEditorsOfDeletedDefs() {
        editors.entrySet().removeIf(en -> {
            if (lib.defs().contains(en.getKey())) {
                en.getValue().refresh();
                return false;
            }
            en.getValue().dispose();
            return true;
        });
    }

    private void closeEditors() {
        editors.values().forEach(Window::dispose);
        editors.clear();
    }

    private void newClass() {
        if (worker == null) PalettePanel.newDefinition(this, lib);
    }

    // ================================================================ run

    private void run() {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        clearOutput();

        Block start;
        try {
            start = Interpreter.prepare(lib);   // compiles every block of every method
        } catch (FlowException e) {
            reportError(e);
            return;
        }

        int delay = speed.getValue();
        lib.setLocked(true);
        runAction.setEnabled(false);
        stopAction.setEnabled(true);
        status.setText(Text.t("Running..."));

        worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                new Interpreter().run(start, new Interpreter.Host() {
                    @Override public void step(Block b) {
                        if (delay <= 0) return;               // full speed: don't flood the EDT
                        SwingUtilities.invokeLater(() -> b.canvas().setActive(b));
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override public void print(String text) {
                        SwingUtilities.invokeLater(() -> console.append(text + "\n"));
                    }

                    @Override public String input(String var) {
                        String[] result = new String[1];
                        try {
                            SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showInputDialog(
                                    MainFrame.this, "Enter a value for  " + var + " :", "Input",
                                    JOptionPane.QUESTION_MESSAGE));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        } catch (InvocationTargetException e) {
                            throw new RuntimeException(e.getCause());
                        }
                        if (result[0] != null) print("> " + var + " = " + result[0]);
                        return result[0];
                    }

                    @Override public boolean cancelled() {
                        return isCancelled() || Thread.currentThread().isInterrupted();
                    }

                    @Override public void board(String event) {
                        SwingUtilities.invokeLater(() -> console.append("   board " + event + "\n"));
                    }

                    @Override public void sleep(long ms) {
                        if (delay <= 0) return;               // full speed: virtual time only
                        try {
                            Thread.sleep(Math.min(ms, 60_000));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    console.append(">> finished\n");
                    status.setText(Text.t("Finished."));
                } catch (CancellationException e) {
                    console.append(">> stopped\n");
                    status.setText(Text.t("Stopped."));
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof FlowException fe) {
                        reportError(fe);
                    } else {
                        console.append(">> internal error: " + c + "\n");
                        c.printStackTrace();
                    }
                    status.setText(Text.t("Error."));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (FlowCanvas c : lib.canvases()) c.setActive(null);
                lib.setLocked(false);
                runAction.setEnabled(true);
                stopAction.setEnabled(false);
                worker = null;
            }
        };
        worker.execute();
    }

    // ================================================================ code generation

    private String programName() {
        if (project != null) return Project.safeName(project.name());
        return currentFile == null ? "program" : currentFile.getName().replaceFirst("\\.[^.]*$", "");
    }

    private CodeViewer codeViewer() {
        if (codeViewer == null) {
            codeViewer = new CodeViewer();
            codeViewer.setLocationRelativeTo(this);
            codeViewer.setRegenerator(() -> {
                if (lastSourceView != null) viewSource(lastSourceView);
                else generateCode();
            });
            // an Android app goes into the project's build folder, or ~/EVIA Projects/Android/<program>
            codeViewer.setAndroidDir(() -> project != null ? project.buildDir().resolve("android")
                    : Project.defaultParent().resolve("Android").resolve(programName()));
        }
        return codeViewer;
    }

    // ================================================================ the Native edition: executables

    private final JTextField exeInput = new JTextField();
    private volatile Process exeProcess;
    private java.io.Writer exeStdin;

    /** Where Build puts the executable: the project's build folder, next to the program file, or a temporary folder. */
    private Path executablePath() throws IOException {
        if (project != null) {
            Files.createDirectories(project.buildDir());
            return project.buildDir().resolve(programName());
        }
        if (currentFile != null) return currentFile.toPath().toAbsolutePath().resolveSibling(programName());
        return Files.createTempDirectory("evia-native").resolve(programName());
    }

    private JButton buildButton, runButton, exportButton;
    private JMenuItem buildItem, runItem, exportItem;
    private final ChipRunner chipRunner = new ChipRunner(text -> SwingUtilities.invokeLater(() -> console.append(text)));
    private boolean uploading;

    /** The Native edition's buttons and menu items say what they do for the target: an executable, or a chip. */
    private void nativeLabels() {
        if (buildButton == null || buildItem == null) return;
        boolean chip = Settings.nativeChip() != null;
        buildButton.setText(chip ? Text.t("Build .hex") : Text.t("Build executable"));
        buildButton.setToolTipText(chip ? Text.t("Compile the blocks straight to the chip's flash, an Intel HEX file (F7)")
                : Text.t("Compile the blocks straight to a program for this computer (F7)"));
        runButton.setText(chip ? Text.t("Upload") : Text.t("Run executable"));
        runButton.setToolTipText(chip ? Text.t("Build, write it to the chip, then show what it prints; Input sends it lines (F6)")
                : Text.t("Build, then run the program; Input goes to it from the field under the output (F6)"));
        buildItem.setText(chip ? Text.t("Build .hex") : Text.t("Build Executable"));
        runItem.setText(chip ? Text.t("Upload to the Chip") : Text.t("Run Executable"));
        exportButton.setEnabled(!chip);
        exportItem.setEnabled(!chip);
    }

    /** Build .hex (F7) and Upload (F6) for an AVR chip. */
    private void buildChip(AvrChip chip, boolean upload) {
        if (uploading) return;
        if (chipRunner.running()) chipRunner.stop();
        long clock = Settings.nativeClock();
        long started = System.nanoTime();
        Path hex;
        NativeCompiler.AvrResult r;
        try {
            r = NativeCompiler.compileAvr(lib, chip, clock);
            Path exe = executablePath();
            hex = exe.resolveSibling(exe.getFileName() + ".hex");
            Files.writeString(hex, r.hex(), StandardCharsets.US_ASCII);
        } catch (FlowException e) {
            clearOutput();
            reportError(e);
            return;
        } catch (IllegalStateException | IllegalArgumentException e) {
            console.setText(">> " + e.getMessage() + "\n");
            status.setText(e.getMessage());
            return;
        } catch (IOException e) {
            status.setText("Could not write the .hex: " + e.getMessage());
            return;
        }
        status.setText(String.format("Built %s: %,d bytes of flash (of %,d), %,d bytes of RAM, %s at %s MHz, in %d ms.",
                hex, r.flashBytes(), chip.flashBytes() - chip.bootloaderBytes(), r.ramBytes(), chip.display(),
                clock % 1_000_000 == 0 ? String.valueOf(clock / 1_000_000) : String.valueOf(clock / 1e6),
                (System.nanoTime() - started) / 1_000_000));
        if (!upload) return;
        if (Settings.serialPort().isEmpty() && !Settings.nativeUpload().equals("usbasp") && !ChipRunner.choose(this, chip)) return;
        clearOutput();
        uploading = true;
        chipRunner.upload(chip, hex, r.hex(), () -> {
            exeInput.setEnabled(true);
            exeInput.requestFocusInWindow();
        }, () -> uploading = false);
    }

    /** Build Executable (F7) and Run Executable (F6): straight from the blocks to a program, no compiler. */
    private void buildExecutable(boolean run) {
        if (App.nativeEdition() && Settings.nativeChip() != null) {
            buildChip(Settings.nativeChip(), run);
            return;
        }
        if (worker != null || exeProcess != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        Target t = Settings.target();
        Path exe;
        long started = System.nanoTime();
        try {
            NativeCompiler.Result r = NativeCompiler.compile(lib, t);
            exe = executablePath();
            Files.write(exe, r.executable());
            exe.toFile().setExecutable(true);
            status.setText(String.format("Built %s: %,d bytes of machine code for Linux x86-64 in %d ms (%s values).",
                    exe, r.executable().length, (System.nanoTime() - started) / 1_000_000, t.values().display.toLowerCase()));
        } catch (FlowException e) {
            clearOutput();
            reportError(e);
            return;
        } catch (IOException e) {
            status.setText("Could not write the executable: " + e.getMessage());
            return;
        }
        if (!run) return;
        boolean here = System.getProperty("os.name", "").toLowerCase().contains("linux")
                && System.getProperty("os.arch", "").matches("amd64|x86_64");
        clearOutput();
        if (!here) {
            console.append(">> " + exe.getFileName() + " is a Linux x86-64 program; this computer is "
                    + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ". Copy it to a Linux PC to run it.\n");
            return;
        }
        Path file = exe;
        Thread th = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(file.toString()).redirectErrorStream(true).start();
                exeProcess = p;
                exeStdin = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> {
                    exeInput.setEnabled(true);
                    exeInput.requestFocusInWindow();
                });
                try (java.io.Reader in = new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        String chunk = new String(buf, 0, n);
                        SwingUtilities.invokeLater(() -> console.append(chunk));
                    }
                }
                int rc = p.waitFor();
                SwingUtilities.invokeLater(() -> console.append("\n>> " + file.getFileName() + " exited with code " + rc + "\n"));
            } catch (IOException | InterruptedException e) {
                SwingUtilities.invokeLater(() -> console.append(">> " + e.getMessage() + "\n"));
            } finally {
                exeProcess = null;
                exeStdin = null;
                SwingUtilities.invokeLater(() -> exeInput.setEnabled(false));
            }
        }, "evia-executable");
        th.setDaemon(true);
        th.start();
    }

    /** Export App: the program as an app for any x86-64 Linux, in the forms chosen (see AppExport). */
    private void exportApp() {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        NativeCompiler.Result r;
        try {
            r = NativeCompiler.compile(lib, Settings.target());
        } catch (FlowException e) {
            clearOutput();
            reportError(e);
            return;
        }
        String base = programName();
        String pretty = base.isEmpty() ? "EVIA App" : Character.toUpperCase(base.charAt(0)) + base.substring(1).replace('_', ' ');
        JTextField name = new JTextField(pretty, 28);
        JTextField command = new JTextField(AppExport.commandFor(pretty), 28);
        JTextField version = new JTextField("1.0", 10);
        JTextField about = new JTextField(Text.t("A program made with EVIA Paradigma Native"), 28);
        JCheckBox exe = new JCheckBox(Text.t("The executable on its own (runs on any x86-64 Linux as it is)"), true);
        JCheckBox tgz = new JCheckBox(Text.t("Portable .tar.gz with install.sh (any Linux, no root needed)"), true);
        JCheckBox deb = new JCheckBox(Text.t(".deb package (Ubuntu, Debian, Linux Mint ...)"), true);
        Path start = project != null ? project.buildDir().resolve("app")
                : currentFile != null ? currentFile.toPath().toAbsolutePath().resolveSibling(command.getText() + "-app")
                : Path.of(System.getProperty("user.home"), command.getText() + "-app");
        JTextField folder = new JTextField(start.toString(), 28);
        JButton browse = new JButton(Text.t("Browse..."));
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(folder.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showDialog(this, Text.t("Choose")) == JFileChooser.APPROVE_OPTION) folder.setText(fc.getSelectedFile().getPath());
        });
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(3, 4, 3, 4);
        Object[][] rows = {{Text.t("App name:"), name}, {Text.t("Command:"), command}, {Text.t("Version:"), version},
                {Text.t("Description:"), about}};
        for (Object[] row : rows) {
            g.gridx = 0; g.gridy++; g.gridwidth = 1;
            p.add(new JLabel((String) row[0]), g);
            g.gridx = 1;
            p.add((Component) row[1], g);
        }
        for (JCheckBox b : new JCheckBox[]{exe, tgz, deb}) {
            g.gridx = 0; g.gridy++; g.gridwidth = 2;
            p.add(b, g);
        }
        g.gridx = 0; g.gridy++; g.gridwidth = 1;
        p.add(new JLabel(Text.t("Folder:")), g);
        JPanel f = new JPanel(new BorderLayout(4, 0));
        f.add(folder, BorderLayout.CENTER);
        f.add(browse, BorderLayout.EAST);
        g.gridx = 1;
        p.add(f, g);
        g.gridx = 0; g.gridy++; g.gridwidth = 2;
        JLabel note = new JLabel("<html><div style='width:430px;color:#777777'>" + Text.t("For Linux on 64-bit Intel and AMD processors. The program needs nothing else there: no libraries, no Java. Its menu entry opens a terminal, because Print and Input talk through one.") + "</div></html>");
        p.add(note, g);
        int a = JOptionPane.showConfirmDialog(this, p, Text.t("Export App"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (a != JOptionPane.OK_OPTION) return;
        try {
            List<Path> files = AppExport.export(r.executable(), new AppExport.Options(name.getText().strip(),
                    command.getText().strip(), version.getText().strip(), about.getText().strip(),
                    exe.isSelected(), tgz.isSelected(), deb.isSelected()), Path.of(folder.getText().strip()));
            clearOutput();
            console.append(">> exported " + name.getText().strip() + " to " + folder.getText().strip() + ":\n");
            for (Path x : files) console.append("   " + x.getFileName() + "   (" + String.format("%,d", Files.size(x)) + " bytes)\n");
            console.append("\n   Executable:  copy it to any x86-64 Linux and run ./" + command.getText().strip() + "\n"
                    + "   .tar.gz:     tar xzf the file, then ./install.sh (or sudo ./install.sh --system)\n"
                    + "   .deb:        sudo apt install ./" + command.getText().strip() + "_" + version.getText().strip() + "_amd64.deb\n");
            status.setText("Exported " + files.size() + " file(s) to " + folder.getText().strip());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), Text.t("Export App"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendToExecutable() {
        java.io.Writer w = exeStdin;
        String line = exeInput.getText();
        exeInput.setText("");
        if (chipRunner.running()) {
            console.append(line + "\n");
            chipRunner.send(line);
            return;
        }
        if (w == null) return;
        console.append(line + "\n");
        try {
            w.write(line + "\n");
            w.flush();
        } catch (IOException e) {
            console.append(">> could not send input: " + e.getMessage() + "\n");
        }
    }

    private void stopExecutable() {
        if (chipRunner.running()) {
            chipRunner.stop();
            exeInput.setEnabled(false);
        }
        Process p = exeProcess;
        if (p != null) p.destroyForcibly();
    }

    /** What the code window shows: null = the whole program, else the block/method/class whose lines are marked. */
    private Object lastSourceView;

    private void generateCode() {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        Target t = Settings.target();
        try {
            String code = CodeGenerator.generate(lib, t);
            lastSourceView = null;
            codeViewer().show(code, programName(), null, t);
            String where = "";
            if (project != null) {
                Path file = project.codeFile(t);
                try {
                    Files.createDirectories(project.buildDir());
                    Files.writeString(file, code, StandardCharsets.UTF_8);
                    where = " Saved to " + project.dir.relativize(file) + ".";
                } catch (IOException e) {
                    where = " (could not write " + file + ": " + e.getMessage() + ")";
                }
            }
            status.setText("Generated " + code.lines().count() + " lines of " + t.describe() + "." + where);
        } catch (FlowException e) {
            clearOutput();
            reportError(e);
        }
    }

    /** "View source code" on a block, method or class: the generated code with its lines marked. */
    private void viewSource(Object what) {
        if (worker != null) return;
        for (FlowCanvas c : lib.canvases()) c.clearMarks();
        Target t = Settings.target();
        try {
            CodeGenerator.Result r = CodeGenerator.generateWithMap(lib, t);
            List<int[]> ranges = r.sourceMap().get(what);
            lastSourceView = what;
            codeViewer().show(r.code(), programName(), ranges, t);
            if (ranges == null || ranges.isEmpty()) {
                status.setText(what instanceof Block
                        ? "This block is not reachable from Start/Enter, so it produces no code."
                        : "No code for this.");
            } else {
                int lines = ranges.stream().mapToInt(x -> x[1] - x[0]).sum();
                status.setText("Highlighted " + lines + " line(s) of " + t.lang() + " generated from " + describe(what) + ".");
            }
        } catch (FlowException e) {
            clearOutput();
            reportError(e);
        }
    }

    private static String describe(Object o) {
        if (o instanceof Block b) return "the " + b.title() + " block";
        if (o instanceof Method m) return "method " + m.qualified();
        if (o instanceof BlockDef d) return "class " + d.name;
        return "it";
    }

    /** Prints the error and marks the failing block; opens the class window at the method it is in. */
    private void reportError(FlowException e) {
        Block b = e.block;
        Method where = b == null ? null : b.canvas().owner;
        String msg = e.getMessage() == null ? "" : e.getMessage();
        boolean named = where != null && (msg.contains(where.qualified()) || msg.contains(where.cls.name));
        console.append(">> error" + (where != null && !named ? " in " + where.qualified() : "") + ": " + msg + "\n");
        if (b != null) {
            b.canvas().setError(b);
            if (where != null) openEditor(where.cls).showMethod(where);
        }
    }

    // ================================================================ files

    private void markSaved() {
        savedSnapshot = ProgramIO.snapshot(lib);
    }

    private boolean isDirty() {
        return !ProgramIO.snapshot(lib).equals(savedSnapshot);
    }

    /** Before a restart for an update: the same question as closing the window. */
    boolean confirmDiscardForRestart() {
        return confirmDiscard();
    }

    /** A sentence in the status bar, e.g. that updates are available. */
    void notice(String text) {
        status.setText(text);
    }

    /** Asks about unsaved changes. Returns false if the user cancelled. */
    private boolean confirmDiscard() {
        if (worker != null) {
            JOptionPane.showMessageDialog(this, Text.t("Stop the running program first."));
            return false;
        }
        if (!isDirty()) return true;
        int r = JOptionPane.showConfirmDialog(this, "Save changes to " + (currentFile == null ? "the program" : currentFile.getName()) + "?",
                APP, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) return save();
        return r == JOptionPane.NO_OPTION;
    }

    /** Empties Output and shows it (a program is about to run or be built). */
    private void clearOutput() {
        console.setText("");
        bottomTabs.setSelectedIndex(0);
    }

    /** Where Claude Code starts: the open program's folder, else the project's. */
    private File workFolder() {
        if (currentFile != null) return currentFile.getAbsoluteFile().getParentFile();
        return project != null ? project.dir.toFile() : null;
    }

    private void exit() {
        if (!confirmDiscard()) return;
        claude.stop();
        dispose();
        System.exit(0);
    }

    private void resetTo(File file, Project p) {
        closeEditors();
        currentFile = file;
        project = p;
        if (p != null && p.target() != null) Settings.setTarget(p.target());   // the project's language
        clearOutput();
        markSaved();
        updateTitle();
    }

    private void newProgram() {
        if (!confirmDiscard()) return;
        canvas.clear();
        lib.clear();
        canvas.addBlock(BlockType.START, 300, 30);
        resetTo(null, null);
        status.setText(Text.t("New program. Drag blocks from the palette."));
    }

    private JFileChooser chooser(boolean forOpen) {
        File dir = currentFile != null ? currentFile.getAbsoluteFile().getParentFile() : project != null ? project.dir.toFile() : null;
        JFileChooser fc = new JFileChooser(dir);
        if (forOpen) {
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma programs and projects (*.evia, *.eviaproj)", "evia", "eviaproj"));
        } else {
            fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma programs (*.evia)", "evia"));
        }
        return fc;
    }

    private void open() {
        if (worker != null) return;
        JFileChooser fc = chooser(true);
        fc.setDialogTitle(Text.t("Open program or project"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openFile(fc.getSelectedFile());
    }

    /** Opens a program, a project file or a project folder (also used for files the OS opens with the app). */
    void openFile(File file) {
        if (!confirmDiscard()) return;
        try {
            Path path = file.toPath();
            if (Files.isDirectory(path) || file.getName().endsWith(Project.EXT)) {
                Project p = Files.isDirectory(path) ? Project.inFolder(path) : Project.load(path);
                if (p == null) throw new IOException(file + " is not an EVIA Paradigma project folder (no " + Project.EXT + " file)");
                ProgramIO.load(lib, p.mainProgram());
                resetTo(p.mainProgram().toFile(), p);
                status.setText("Opened project " + p.name() + " (" + p.dir + ")");
            } else {
                ProgramIO.load(lib, path);
                resetTo(file, Project.ofProgram(path));
                status.setText("Opened " + file.getName());
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openProject() {
        if (worker != null) return;
        JFileChooser fc = new JFileChooser(Files.isDirectory(Project.defaultParent()) ? Project.defaultParent().toFile() : null);
        fc.setDialogTitle(Text.t("Open project folder"));
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma projects (*.eviaproj)", "eviaproj"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f.isFile() && !f.getName().endsWith(Project.EXT)) f = f.getParentFile();
        openFile(f);
    }

    private void closeProject() {
        if (project == null) {
            status.setText(Text.t("No project is open."));
            return;
        }
        if (!confirmDiscard()) return;
        canvas.clear();
        lib.clear();
        canvas.addBlock(BlockType.START, 300, 30);
        resetTo(null, null);
    }

    private boolean save() {
        if (currentFile == null) return saveAs();
        return saveTo(currentFile);
    }

    private boolean saveAs() {
        JFileChooser fc = chooser(false);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getPath() + ".evia");
        if (f.exists() && !f.equals(currentFile) && JOptionPane.showConfirmDialog(this, f.getName() + " exists. Replace it?",
                "Save As", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false;
        if (saveTo(f)) {
            project = Project.ofProgram(f.toPath());
            updateTitle();
            return true;
        }
        return false;
    }

    private boolean saveTo(File f) {
        try {
            ProgramIO.save(lib, f.toPath());
            currentFile = f;
            markSaved();
            updateTitle();
            int modules = lib.modules().size();
            status.setText("Saved " + f.getName() + (modules > 0 ? " and " + modules + " module(s)" : ""));
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** Copies a bundled example (and the modules it uses) to a temporary folder and opens it there. */
    static Path extractExample(String name) throws IOException {
        Path dir = Files.createTempDirectory("evia-example");
        dir.toFile().deleteOnExit();
        List<String> files = new ArrayList<>(List.of(EXAMPLE_FILES));
        files.add(name);
        for (String f : files) {
            try (InputStream in = MainFrame.class.getResourceAsStream("examples/" + f)) {
                if (in == null) throw new IOException("example " + f + " is missing");
                Path target = dir.resolve(f);
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().deleteOnExit();
            }
        }
        return dir.resolve(name);
    }

    private void openExample(String name) {
        openExample(name, null);
    }

    /** Opens an example; a board family also sets the language and a board of that family. */
    private void openExample(String name, ExampleFamily family) {
        if (!confirmDiscard()) return;
        try {
            ProgramIO.load(lib, extractExample(name));
            for (BlockDef d : lib.defs()) d.module = null;   // keep the example self-contained when saved
            lib.changed();
            resetTo(null, null);
            setTitle(APP + " - example " + name);
            if (family == ExampleFamily.PIC32) {
                Settings.setTarget(Settings.target().withLang(Target.Lang.PIC32));
                if (name.contains("esk2")) Settings.setBoard(Board.PIC32_ESK2);
            } else if (family == ExampleFamily.STM32) {
                if (Settings.target().lang() != Target.Lang.ARDUINO && Settings.target().lang() != Target.Lang.STM32) {
                    Settings.setTarget(Settings.target().withLang(Target.Lang.STM32));
                }
                if (Settings.target().lang() == Target.Lang.ARDUINO && !Settings.board().isStm32()) {
                    Settings.setBoard(Settings.board(Target.Lang.STM32));
                }
            } else if (family != null) {
                if (Settings.target().lang() != Target.Lang.ARDUINO && Settings.target().lang() != Target.Lang.AVR) {
                    Settings.setTarget(Settings.target().withLang(Target.Lang.ARDUINO));   // its code is for a board
                }
                Board b = Settings.board();
                if (family == ExampleFamily.ESP32 && !b.isEsp32()) Settings.setBoard(Board.ESP32);
                if (family == ExampleFamily.ARDUINO && !b.isArduinoAvr()) Settings.setBoard(Board.UNO);
                if (family == ExampleFamily.ESP32 && Settings.target().lang() == Target.Lang.AVR) {
                    Settings.setTarget(Settings.target().withLang(Target.Lang.ARDUINO));   // an ESP32 has no AVR C
                }
            } else if (name.startsWith("boards/") && !Settings.target().embedded()) {
                Settings.setTarget(Settings.target().withLang(Target.Lang.ARDUINO));
            }
            if (name.startsWith("android/") && Settings.target().lang() != Target.Lang.ANDROID) {
                Settings.setTarget(Settings.target().withLang(Target.Lang.ANDROID));   // its code is a phone app
            }
            if (name.startsWith("fpga/") && !Settings.target().lang().fpga()) {
                Settings.setTarget(Settings.target().withLang(Target.Lang.SYSTEMVERILOG));   // its code is for an FPGA
            }
            status.setText(name.startsWith("android/")
                    ? "Example " + name + ". Run (F5) simulates the phone; Generate Code (F7), then Build and Install on Phone."
                    : name.startsWith("fpga/")
                    ? "Example " + name + ". Run (F5) simulates it; Generate Code (F7) makes the hardware design for your FPGA board."
                    : name.startsWith("boards/")
                    ? "Example " + name + ". Run (F5) simulates the board's pins; Generate Code (F7) makes the code for "
                            + Settings.board(Settings.target().lang()).display + "."
                    : "Example " + name + ". Press Run (F5). Save it to keep your changes.");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Example", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================================================================ modules

    private File modulesDir() {
        if (project != null) return project.modulesDir().toFile();
        return currentFile == null ? null : currentFile.getAbsoluteFile().getParentFile();
    }

    private void importModule() {
        if (worker != null) return;
        JFileChooser fc = new JFileChooser(modulesDir());
        fc.setDialogTitle(Text.t("Import module"));
        fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma modules (*.eviam)", "eviam"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<BlockDef> added = ProgramIO.importModule(lib, fc.getSelectedFile().toPath());
            StringBuilder names = new StringBuilder();
            for (BlockDef d : added) names.append(names.isEmpty() ? "" : ", ").append(d.name);
            status.setText("Imported " + fc.getSelectedFile().getName() + ": " + (names.isEmpty() ? "no new classes" : names));
            if (currentFile == null) {
                console.append(">> note: save the program so it remembers the module (stored as a path relative to the program)\n");
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Import failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportModule() {
        if (worker != null) return;
        if (lib.defs().isEmpty()) {
            JOptionPane.showMessageDialog(this, Text.t("There are no classes yet. Create one with Edit > New Class."));
            return;
        }
        JPanel boxes = new JPanel(new GridLayout(0, 1));
        List<JCheckBox> checks = new ArrayList<>();
        for (BlockDef d : lib.defs()) {
            JCheckBox c = new JCheckBox(d.name + (d.module == null ? "" : "   (now in " + Path.of(d.module).getFileName() + ")"),
                    d.module == null);
            checks.add(c);
            boxes.add(c);
        }
        Object[] msg = {"Classes to move into the module file:", new JScrollPane(boxes)};
        if (JOptionPane.showConfirmDialog(this, msg, "Export classes as module", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        List<BlockDef> chosen = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) if (checks.get(i).isSelected()) chosen.add(lib.defs().get(i));
        if (chosen.isEmpty()) return;

        File dir = modulesDir();
        JFileChooser fc = new JFileChooser(dir);
        fc.setDialogTitle(Text.t("Save module"));
        fc.setFileFilter(new FileNameExtensionFilter("EVIA Paradigma modules (*.eviam)", "eviam"));
        fc.setSelectedFile(new File(dir, Project.safeName(chosen.size() == 1 ? chosen.get(0).name : "classes") + ProgramIO.MODULE_EXT));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().endsWith(ProgramIO.MODULE_EXT)) f = new File(f.getPath() + ProgramIO.MODULE_EXT);
        try {
            if (f.getAbsoluteFile().getParentFile() != null) Files.createDirectories(f.getAbsoluteFile().getParentFile().toPath());
            ProgramIO.exportModule(lib, chosen, f.toPath());
            status.setText("Saved " + chosen.size() + " class(es) to " + f.getName()
                    + (currentFile != null ? "" : " - save the program to keep the link to the module"));
            if (currentFile != null) save();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================================================================ projects

    private void newProject() {
        if (!confirmDiscard()) return;
        JTextField name = new JTextField("MyProject", 22);
        JTextField location = new JTextField(Project.defaultParent().toString(), 30);
        JButton browse = new JButton(Text.t("Browse..."));
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(location.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) location.setText(fc.getSelectedFile().getPath());
        });
        boolean hasGit = GitService.available();
        JCheckBox git = new JCheckBox(hasGit ? "Create a Git repository (branch main) and commit"
                : "Create a Git repository (git is not installed)", hasGit);
        git.setEnabled(hasGit);
        JLabel preview = new JLabel(" ");
        Runnable upd = () -> preview.setText("<html><small>Folder: " + Path.of(location.getText(), name.getText().trim())
                + "<br>with main.evia, modules/, build/, README.md, .gitignore</small></html>");
        upd.run();
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
        };
        name.getDocument().addDocumentListener(dl);
        location.getDocument().addDocumentListener(dl);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0; g.gridy = 0; p.add(new JLabel(Text.t("Project name:")), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; p.add(name, g);
        g.gridx = 0; g.gridy = 1; g.fill = GridBagConstraints.NONE; p.add(new JLabel(Text.t("Location:")), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; p.add(location, g);
        g.gridx = 2; g.fill = GridBagConstraints.NONE; p.add(browse, g);
        g.gridx = 1; g.gridy = 2; g.gridwidth = 2; p.add(git, g);
        g.gridy = 3; p.add(preview, g);

        while (true) {
            if (JOptionPane.showConfirmDialog(this, p, "New Project", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            if (Project.validName(name.getText().trim())) break;
            JOptionPane.showMessageDialog(this, Text.t("Choose a folder name without / \\ : * ? \" < > |"));
        }
        try {
            Project proj = Project.create(Path.of(location.getText().trim()), name.getText().trim());
            proj.setTarget(Settings.target());
            canvas.clear();
            lib.clear();
            starterProgram(canvas, proj.name());
            ProgramIO.save(lib, proj.mainProgram());
            resetTo(proj.mainProgram().toFile(), proj);
            status.setText("Created project " + proj.name() + " in " + proj.dir);
            if (git.isSelected()) {
                gitTask("Creating repository", () -> {
                    GitService.init(proj.dir);
                    return "Initialized Git repository (branch main) in " + proj.dir;
                }, () -> commitWithMessage(proj.dir, "Create project " + proj.name()));
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "New Project", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Start -> Print "Hello from NAME!" -> End. */
    static void starterProgram(FlowCanvas c, String name) {
        Block start = c.addBlock(BlockType.START, 300, 30);
        Block print = c.addBlock(BlockType.PRINT, "\"Hello from " + name.replace("\\", "\\\\").replace("\"", "\\\"") + "!\"",
                null, 300, 125);
        Block end = c.addBlock(BlockType.END, 300, 220);
        c.connect(start, 0, print);
        c.connect(print, 0, end);
    }

    // ================================================================ git

    /** The folder git works in: the project, else the folder of the saved program. */
    private Path gitDir() {
        if (project != null) return project.dir;
        return currentFile == null ? null : currentFile.getAbsoluteFile().getParentFile().toPath();
    }

    private void updateGitMenu() {
        Path dir = gitDir();
        boolean installed = GitService.available();
        boolean repo = dir != null && !gitBusy && installed && GitService.isRepo(dir);
        gitInitItem.setEnabled(!gitBusy && !repo);
        for (JMenuItem i : gitItems) i.setEnabled(repo);
        gitInitItem.setText(installed ? "Initialize Repository" : "Initialize Repository (install git first)");
    }

    private interface GitStep {
        String run() throws IOException;
    }

    /** Runs a git step in the background, logs its output to the console, then runs {@code next} (on the EDT). */
    private void gitTask(String what, GitStep step, Runnable next) {
        if (gitBusy) {
            status.setText(Text.t("Git is still busy..."));
            return;
        }
        gitBusy = true;
        status.setText(what + "...");
        console.append(">> git: " + what.toLowerCase() + "\n");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return step.run();
            }

            @Override protected void done() {
                gitBusy = false;
                try {
                    String out = get();
                    if (out != null && !out.isBlank()) console.append(out.strip() + "\n");
                    status.setText(what + ": done.");
                    if (next != null) next.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    String m = e.getCause().getMessage();
                    console.append(">> git error: " + m + "\n");
                    status.setText(what + " failed.");
                    String hint = m != null && (m.contains("Authentication") || m.contains("could not read Username")
                            || m.contains("Permission denied") || m.contains("terminal prompts disabled"))
                            ? "\n\nGit has no GitHub login on this computer yet. Sign in once in a terminal, e.g.\n"
                              + "  gh auth login        (GitHub CLI)\n  or: git push   (Git Credential Manager opens a browser)\n"
                              + "or set up an SSH key, then try again."
                            : "";
                    JOptionPane.showMessageDialog(MainFrame.this, shorten(m) + hint, "Git", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String shorten(String s) {
        if (s == null) return "unknown error";
        return s.length() > 1500 ? s.substring(0, 1500) + "..." : s;
    }

    private boolean requireGit() {
        if (GitService.available()) return true;
        int r = JOptionPane.showConfirmDialog(this, "Git is not installed on this computer.\nOpen the download page (git-scm.com)?",
                "Git", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) HelpWindow.browse(this, "https://git-scm.com/downloads");
        return false;
    }

    /** Saves the program (asking for a file the first time) and returns the git folder, or null. */
    private Path saveForGit() {
        if (!requireGit()) return null;
        if (currentFile == null || isDirty()) {
            if (!save()) return null;
        }
        return gitDir();
    }

    private void gitInit() {
        Path dir = saveForGit();
        if (dir == null) return;
        gitTask("Creating repository", () -> {
            GitService.init(dir);
            Path ignore = dir.resolve(".gitignore");
            if (!Files.exists(ignore)) Files.writeString(ignore, "build/\n*.exe\n.DS_Store\n", StandardCharsets.UTF_8);
            return "Initialized Git repository (branch main) in " + dir;
        }, () -> commitWithMessage(dir, "Initial commit"));
    }

    private void gitCommit() {
        Path dir = saveForGit();
        if (dir == null) return;
        try {
            if (!GitService.isRepo(dir)) {
                gitInit();
                return;
            }
            String changes = GitService.status(dir);
            if (changes.isBlank()) {
                status.setText(Text.t("Nothing to commit - everything is committed."));
                JOptionPane.showMessageDialog(this, Text.t("Nothing to commit: all changes are already committed."), "Commit",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!GitDialogs.ensureIdentity(this, dir)) return;
            String msg = GitDialogs.commitMessage(this, changes, "Update " + programName());
            if (msg == null) return;
            gitTask("Committing", () -> GitService.commitAll(dir, msg), null);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Commit", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Commits everything with a fixed message (asking for the identity if needed). */
    private void commitWithMessage(Path dir, String msg) {
        try {
            if (GitService.status(dir).isBlank()) return;
            if (!GitDialogs.ensureIdentity(this, dir)) {
                console.append(">> git: no commit made (no name/e-mail). Use Git > Commit later.\n");
                return;
            }
            gitTask("Committing", () -> GitService.commitAll(dir, msg), null);
        } catch (IOException e) {
            console.append(">> git error: " + e.getMessage() + "\n");
        }
    }

    private void gitPush() {
        Path dir = gitDir();
        if (dir == null || !requireGit()) return;
        try {
            if (GitService.remoteUrl(dir).isEmpty()) {
                gitConnect();
                return;
            }
            if (isDirty() || !GitService.status(dir).isBlank()) {
                int r = JOptionPane.showConfirmDialog(this, "There are changes that are not committed.\nCommit them before pushing?",
                        "Push", JOptionPane.YES_NO_CANCEL_OPTION);
                if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
                if (r == JOptionPane.YES_OPTION) {
                    gitCommit();
                    return;
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Push", JOptionPane.ERROR_MESSAGE);
            return;
        }
        gitTask("Pushing to GitHub", () -> GitService.push(dir), null);
    }

    private void gitPull() {
        Path dir = gitDir();
        if (dir == null || !requireGit()) return;
        if (isDirty() && !save()) return;
        gitTask("Pulling", () -> GitService.pull(dir), () -> {
            // the program files may have changed on disk
            if (currentFile != null) {
                try {
                    ProgramIO.load(lib, currentFile.toPath());
                    closeEditors();
                    markSaved();
                    status.setText("Pulled and reloaded " + currentFile.getName());
                } catch (IOException e) {
                    console.append(">> could not reload: " + e.getMessage() + "\n");
                }
            }
        });
    }

    private void gitConnect() {
        Path dir = saveForGit();
        if (dir == null) return;
        try {
            String current = GitService.isRepo(dir) ? GitService.remoteUrl(dir) : "";
            GitDialogs.Remote r = GitDialogs.connect(this, current, project != null ? project.name() : programName());
            if (r == null) return;
            if (!GitService.isRepo(dir)) GitService.init(dir);
            GitService.setRemote(dir, r.url());
            console.append(">> git: origin = " + r.url() + "\n");
            status.setText("Connected to " + r.url());
            if (!r.pushNow()) return;
            String changes = GitService.status(dir);
            if (!changes.isBlank()) {
                if (!GitDialogs.ensureIdentity(this, dir)) return;
                String msg = GitDialogs.commitMessage(this, changes, "Update " + programName());
                if (msg == null) return;
                gitTask("Committing", () -> GitService.commitAll(dir, msg),
                        () -> gitTask("Pushing to GitHub", () -> GitService.push(dir), null));
            } else {
                gitTask("Pushing to GitHub", () -> GitService.push(dir), null);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Connect to GitHub", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void gitOpenWeb() {
        Path dir = gitDir();
        if (dir == null) return;
        try {
            String remote = GitService.remoteUrl(dir);
            String web = GitService.webUrl(remote);
            if (web != null) HelpWindow.browse(this, web);
            else if (remote.isEmpty()) gitConnect();
            else JOptionPane.showMessageDialog(this, "The remote is not on GitHub:\n" + remote);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Git", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void gitShow(String what) {
        Path dir = gitDir();
        if (dir == null) return;
        try {
            String text;
            if (what.equals("History")) {
                text = GitService.log(dir);
            } else {
                String s = GitService.status(dir);
                String remote = GitService.remoteUrl(dir);
                text = "Folder:  " + dir + "\nBranch:  " + GitService.branch(dir) + "\nRemote:  "
                        + (remote.isEmpty() ? "(none - use Git > Connect to GitHub)" : remote) + "\n\n"
                        + (s.isBlank() ? "Nothing to commit." : "Changes:\n" + s);
            }
            GitDialogs.showText(this, "Git " + what, text);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Git", JOptionPane.ERROR_MESSAGE);
        }
    }
}
