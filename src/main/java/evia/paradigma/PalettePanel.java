package evia.paradigma;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Palette for one canvas. Primitive blocks first, then the user's custom blocks.
 * Each entry is a JLabel that starts a Swing drag carrying "TYPE:..." or "DEF:id";
 * double-clicking an entry also adds it. Rebuilds itself whenever the library changes.
 */
final class PalettePanel extends JPanel {
    private final Library lib;
    private final FlowCanvas target;
    private final Runnable rebuild = this::build;
    private final Runnable rebuildLater = () -> SwingUtilities.invokeLater(this::build);   // the target changed
    private int dropOffset;

    PalettePanel(Library lib, FlowCanvas target) {
        this.lib = lib;
        this.target = target;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        build();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        lib.addListener(rebuild);
        if (App.nativeEdition()) Settings.onChange(rebuildLater);
        build();
    }

    @Override
    public void removeNotify() {
        lib.removeListener(rebuild);
        Settings.removeListener(rebuildLater);
        super.removeNotify();
    }

    private void build() {
        removeAll();
        add(header(Text.t("Blocks")));
        for (BlockType t : BlockType.values()) {
            if (t == BlockType.CUSTOM || t.isBoard() || t.isPhone() || !target.allows(t)) continue;
            add(item(Text.t(t.title), t.color, Text.t(t.hint), "TYPE:" + t.name(), null, null));
            add(Box.createVerticalStrut(5));
        }

        if (App.nativeEdition()) {
            AvrChip chip = Settings.nativeChip();
            add(Box.createVerticalStrut(10));
            if (chip == null) {
                // an executable on this computer: of the board blocks, only time
                add(header(Text.t("Time")));
                for (BlockType t : new BlockType[]{BlockType.DELAY, BlockType.MILLIS}) {
                    add(item(Text.t(t.title), t.color, Text.t(t.hint), "TYPE:" + t.name(), null, null));
                    add(Box.createVerticalStrut(5));
                }
            } else {
                // a program for an AVR chip: the board blocks the chip has
                add(header(chip.display()));
                for (BlockType t : BlockType.values()) {
                    if (!t.isBoard() || !AvrChip.supports(t)) continue;
                    add(item(Text.t(t.title), t.color, "<html>" + Text.t(t.hint) + "<br><i>"
                                    + Text.t("On the chip itself; Run simulates an Arduino board.") + "</i></html>",
                            "TYPE:" + t.name(), null, null));
                    add(Box.createVerticalStrut(5));
                }
            }
        }

        add(Box.createVerticalStrut(10));
        if (!App.nativeEdition()) add(header(Text.t("Phone")));
        for (BlockType t : BlockType.values()) {
            if (App.nativeEdition() || !t.isPhone() || !target.allows(t)) continue;
            add(item(Text.t(t.title), t.color, "<html>" + Text.t(t.hint) + "<br><i>"
                            + Text.t("Language Android reads the phone's sensors; Run simulates them.") + "</i></html>",
                    "TYPE:" + t.name(), null, null));
            add(Box.createVerticalStrut(5));
        }

        add(Box.createVerticalStrut(10));
        if (!App.nativeEdition()) add(header(Text.t("Board")));
        for (BlockType t : BlockType.values()) {
            if (App.nativeEdition() || !t.isBoard() || !target.allows(t)) continue;
            add(item(Text.t(t.title), t.color, "<html>" + Text.t(t.hint) + "<br><i>" + Text.t("Arduino / AVR C code; Run simulates an Arduino Uno.") + "</i></html>",
                    "TYPE:" + t.name(), null, null));
            add(Box.createVerticalStrut(5));
        }

        add(Box.createVerticalStrut(10));
        add(header(Text.t("Classes")));
        JButton newBtn = new JButton(Text.t("+ New class..."));
        newBtn.setAlignmentX(LEFT_ALIGNMENT);
        newBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        newBtn.addActionListener(e -> newDefinition(this, lib));
        add(newBtn);
        add(Box.createVerticalStrut(6));

        if (lib.defs().isEmpty()) {
            add(note(Text.t("None yet. Create one, or select\nblocks and press Ctrl+G to group\nthem into a class.")));
        }
        for (BlockDef d : lib.defs()) {
            boolean self = target.owner != null && target.owner.cls == d;
            for (Method m : d.callable()) {
                boolean simple = d.callable().size() == 1 && m.name.equals("run");
                String label = simple ? d.signature() : d.name + "." + m.signature();
                String tip = "<html><b>" + d.name + "." + m.signature() + "</b>"
                        + (d.module != null ? " &nbsp;<i>(module " + java.nio.file.Path.of(d.module).getFileName() + ")</i>" : "")
                        + "<br>" + Text.t("exits:") + " " + String.join(", ", m.exitNames())
                        + "<br>" + Text.t("Drag to call it. Double-click to open the class.")
                        + (self ? "<br><i>Calling it from its own class makes it recursive.</i>" : "") + "</html>";
                add(item(label, d.color, tip, "DEF:" + d.id + "/" + m.name, () -> lib.openEditor(d), d));
                add(Box.createVerticalStrut(5));
            }
        }

        add(Box.createVerticalStrut(12));
        add(note(target.isBody() ? """
                Inside a method:
                - Enter: where it starts
                - Exit: leaves it; each exit
                  name is an output port
                - this.x = field of this object
                  (parameters are fields too;
                  fields keep their value
                  between calls)
                - plain names = program
                  variables"""
                : """
                How to use:
                - Drag a block onto the canvas
                - Drag from a bottom port to
                  another block to connect
                - Double-click a block to edit
                - Drag on empty space to select
                  several, Ctrl+click to add
                - Ctrl+G: group selection into
                  a class
                - Right-click for more options

                Loops (For, While, Do-While):
                  wire the end of the body back
                  into the loop block

                Expressions:
                  + - * / %  == != < <= > >=
                  && || !  ( )  "strings"  this.x"""));
        add(Box.createVerticalGlue());
        revalidate();
        repaint();
    }

    private static JLabel header(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return l;
    }

    private static JTextArea note(String text) {
        JTextArea a = new JTextArea(text);
        a.setEditable(false);
        a.setOpaque(false);
        a.setFont(Fonts.ui(Font.PLAIN, 11));
        a.setForeground(Theme.muted());
        a.setAlignmentX(LEFT_ALIGNMENT);
        return a;
    }

    private JLabel item(String text, Color color, String tip, String transfer, Runnable onOpen, BlockDef def) {
        JLabel item = new JLabel(text, SwingConstants.CENTER);
        item.setOpaque(true);
        item.setBackground(color);
        item.setForeground(new Color(30, 30, 40));   // pastel blocks: dark text in both themes
        item.setFont(item.getFont().deriveFont(Font.BOLD, 13f));
        Color edge = color.darker();
        item.setBorder(def == null
                ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge, 1, true),
                        BorderFactory.createEmptyBorder(7, 10, 7, 10))
                : BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 1, 3, 3, edge),
                        BorderFactory.createEmptyBorder(7, 10, 5, 8)));
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        item.setAlignmentX(LEFT_ALIGNMENT);
        item.setToolTipText(tip);
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        item.setTransferHandler(new TransferHandler() {
            @Override protected Transferable createTransferable(JComponent c) {
                return new StringSelection(transfer);
            }
            @Override public int getSourceActions(JComponent c) {
                return COPY;
            }
        });
        item.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) popup(e);
                else if (SwingUtilities.isLeftMouseButton(e)) {
                    item.getTransferHandler().exportAsDrag(item, e, TransferHandler.COPY);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) popup(e);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                if (onOpen != null) {
                    onOpen.run();
                } else if (!target.isLocked()) {
                    Rectangle view = target.getVisibleRect();
                    int off = (dropOffset++ % 8) * 20;
                    Block b = target.createFromTransfer(transfer,
                            new Point(view.x + 140 + off, view.y + 80 + off));
                    if (b != null) target.select(b);
                }
            }
            private void popup(MouseEvent e) {
                if (def == null) return;
                JPopupMenu m = new JPopupMenu();
                JMenuItem open = new JMenuItem("Open class...");
                open.addActionListener(ev -> lib.openEditor(def));
                m.add(open);
                if (!App.nativeEdition()) {
                    JMenuItem src = new JMenuItem("View source code");
                    src.addActionListener(ev -> lib.viewSource(def));
                    m.add(src);
                }
                JMenuItem del = new JMenuItem("Delete class");
                del.addActionListener(ev -> deleteDefinition(def));
                m.add(del);
                m.show(item, e.getX(), e.getY());
            }
        });
        return item;
    }

    private void deleteDefinition(BlockDef d) {
        if (lib.isUsed(d)) {
            JOptionPane.showMessageDialog(this, "'" + d.name + "' is still used somewhere. Delete the blocks that call it first.",
                    "Delete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete the class '" + d.name + "'?", "Delete",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            lib.remove(d);
        }
    }

    /** Asks for a name, creates an empty definition and opens its editor. */
    static void newDefinition(Component parent, Library lib) {
        String name = (String) JOptionPane.showInputDialog(parent, "Name of the new class:",
                "New class", JOptionPane.PLAIN_MESSAGE, null, null, lib.uniqueName("MyBlock"));
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || lib.nameTaken(name, null)) {
            JOptionPane.showMessageDialog(parent, "Choose a unique, non-empty name.", "New class",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        lib.openEditor(lib.create(name));
    }
}
