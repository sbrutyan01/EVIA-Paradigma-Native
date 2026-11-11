package evia.paradigma;

import javax.swing.*;
import java.awt.*;

/**
 * Drop-down lists Language / Flow / Values (bound to {@link Settings#target()}) and, for the
 * microcontroller languages, Board (bound to {@link Settings#board()}). Every bar in the application
 * shows the same choice; changing one changes them all.
 */
final class TargetBar extends JPanel {
    private final JComboBox<Target.Lang> lang = new JComboBox<>(Target.Lang.values());
    private final JComboBox<Target.Flow> flow = new JComboBox<>(Target.Flow.values());
    private final JComboBox<Target.Values> values = new JComboBox<>(Target.Values.values());
    private final JComboBox<Board> board = new JComboBox<>();
    private final JLabel boardLabel = new JLabel(Text.t(" Board:"));
    /** Native edition: what Build makes - an executable for Linux x86-64 (null) or a program for an AVR chip. */
    private final JComboBox<Object> machine = new JComboBox<>();
    private final JComboBox<String> clock = new JComboBox<>(new String[]{"16 MHz", "8 MHz", "20 MHz", "12 MHz", "1 MHz"});
    private final JLabel clockLabel = new JLabel(Text.t(" Clock:"));
    private static final String LINUX = "Linux x86-64";
    private boolean updating;
    private static final String valuesTip = "<html><b>Safe</b>: numbers and text with the interpreter's checks (undefined variables, division by zero ...)"
            + "<br><b>Fast</b>: plain <code>double</code> numbers, text only in Print, no runtime checks</html>";

    TargetBar() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        lang.setToolTipText(Text.t("Language of the generated code"));
        lang.setMaximumRowCount(Target.Lang.values().length);
        lang.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                  boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Target.Lang l) setText(l.display + "  (" + l.tag() + ")");
                return this;
            }
        });
        flow.setToolTipText("<html><b>Structured</b>: if/else, for, while, do-while and switch (labels only where the wiring needs them)"
                + "<br><b>Goto</b>: every block is a label and every wire a jump - closest to the block diagram</html>");
        values.setToolTipText(valuesTip);
        add(new JLabel(Text.t("Language:")));
        add(lang);
        add(new JLabel(Text.t(" Flow:")));
        add(flow);
        add(new JLabel(Text.t(" Values:")));
        add(values);
        board.setToolTipText("<html>The board that Build and Upload use. For microcontrollers the generated code is the same for every board:"
                + "<br>pins are written as LED_BUILTIN, A0 ... and each board's headers say where they are."
                + "<br>For an FPGA the board decides the chip, the clock and the pin constraints.</html>");
        board.setMaximumRowCount(Board.values().length);
        add(boardLabel);
        add(board);
        for (JComboBox<?> c : new JComboBox<?>[]{lang, flow, values, board}) c.setFocusable(false);
        if (App.nativeEdition()) {
            // no languages: the values only, and what the executable runs on
            for (Component c : getComponents()) c.setVisible(false);
            removeAll();
            machine.addItem(LINUX);
            for (AvrChip c : AvrChip.values()) machine.addItem(c);
            machine.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                      boolean selected, boolean focus) {
                    super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof AvrChip c) setText(c.display());
                    return this;
                }
            });
            machine.setToolTipText(Text.t("<html>What Build makes, straight from the blocks:<br><b>Linux x86-64</b>: a program for Linux on 64-bit Intel and AMD processors<br><b>An AVR chip</b>: its flash (Intel HEX), which Upload writes to the board</html>"));
            clock.setToolTipText(Text.t("The chip's clock (its crystal): millis, Delay and the serial port's speed depend on it"));
            add(new JLabel(Text.t(" Target:")));
            add(machine);
            add(clockLabel);
            add(clock);
            add(new JLabel(Text.t(" Values:")));
            values.setVisible(true);
            add(values);
            for (JComboBox<?> c : new JComboBox<?>[]{machine, clock}) c.setFocusable(false);
            machine.addActionListener(e -> {
                if (!updating) Settings.setNativeChip(machine.getSelectedItem() instanceof AvrChip c ? c : null);
            });
            clock.addActionListener(e -> {
                if (!updating && clock.getSelectedItem() instanceof String v) {
                    Settings.setNativeClock(Math.round(Double.parseDouble(v.replace(" MHz", "")) * 1_000_000));
                }
            });
        }
        refresh();
        lang.addActionListener(e -> changed());
        flow.addActionListener(e -> changed());
        values.addActionListener(e -> changed());
        board.addActionListener(e -> {
            if (!updating && board.getSelectedItem() instanceof Board b) Settings.setBoard(b);
        });
        Settings.onChange(() -> SwingUtilities.invokeLater(this::refresh));
    }

    private void changed() {
        if (updating) return;
        Settings.setTarget(new Target((Target.Lang) lang.getSelectedItem(), (Target.Flow) flow.getSelectedItem(),
                (Target.Values) values.getSelectedItem()));
    }

    private void refresh() {
        Target t = Settings.target();
        updating = true;
        try {
            lang.setSelectedItem(t.lang());
            flow.setSelectedItem(t.flow());
            values.setSelectedItem(t.values());
            flow.setEnabled(!t.lang().fpga());
            values.setEnabled(!t.embedded());
            values.setToolTipText(t.lang().fpga() ? "FPGAs use whole numbers: signed 32-bit, text only in Print"
                    : t.embedded() ? "Microcontrollers use fast values: numbers only, text only in Print" : valuesTip);
            board.removeAllItems();
            for (Board b : Board.values()) if (b.supports(t.lang())) board.addItem(b);
            board.setSelectedItem(Settings.board(t.lang()));
            board.setVisible(t.embedded() && !App.nativeEdition());
            boardLabel.setVisible(t.embedded() && !App.nativeEdition());
            if (App.nativeEdition()) {
                AvrChip chip = Settings.nativeChip();
                machine.setSelectedItem(chip != null ? chip : LINUX);
                clock.setSelectedItem(Settings.nativeClock() % 1_000_000 == 0 ? Settings.nativeClock() / 1_000_000 + " MHz" : "16 MHz");
                clock.setVisible(chip != null);
                clockLabel.setVisible(chip != null);
                // the chips have fast values only: 32-bit floats
                values.setEnabled(chip == null);
                if (chip != null) values.setSelectedItem(Target.Values.FAST);
                values.setToolTipText(chip != null ? Text.t("AVR chips use fast values: numbers (32-bit floats), text only in Print") : Text.t(valuesTip));
            }
            revalidate();
        } finally {
            updating = false;
        }
    }

    @Override public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}
