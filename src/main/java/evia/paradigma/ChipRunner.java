package evia.paradigma;

import com.fazecast.jSerialComm.SerialPort;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Native edition's AVR chips, after Build has written the .hex: Upload (through the board's bootloader, built
 * in, or through an ISP programmer with avrdude), then the board's serial port in the Output window - what the
 * program prints arrives there, and the Input field sends it lines.
 */
final class ChipRunner {
    /** How a program gets to the chip: the setting's value and what the menus call it. */
    record Method(String key, String display) {
        @Override public String toString() {
            return display;
        }
    }

    static final List<Method> METHODS = List.of(
            new Method("bootloader", "The board's bootloader over USB / serial (built in, no avrdude)"),
            new Method("usbasp", "USBasp programmer (avrdude)"),
            new Method("stk500v1 -b 19200", "Arduino as ISP (avrdude)"),
            new Method("avrispmkII", "AVRISP mkII (avrdude)"));

    private final Consumer<String> out;
    private volatile SerialPort monitor;

    /** out: text for the Output window (called on any thread). */
    ChipRunner(Consumer<String> out) {
        this.out = out;
    }

    boolean running() {
        return monitor != null;
    }

    /**
     * Uploads the .hex, then shows the serial port, in the background. The port must be set (see choose).
     * done runs on the event thread when the upload has finished or failed.
     */
    void upload(AvrChip chip, Path hex, String hexText, Runnable started, Runnable done) {
        String port = Settings.serialPort();
        String how = Settings.nativeUpload();
        Thread th = new Thread(() -> {
            boolean ok = false;
            try {
                if (how.equals("bootloader")) {
                    out.accept(">> uploading to the " + chip.display() + " on " + port + " through its bootloader\n");
                    long t = System.currentTimeMillis();
                    AvrUpload.viaBootloader(port, chip, hexText, s -> out.accept(">> " + s + "\n"));
                    out.accept(String.format(">> uploaded in %.1f s%n", (System.currentTimeMillis() - t) / 1000.0));
                } else {
                    List<String> cmd = new ArrayList<>(List.of(Settings.avrdude(), "-p", chip.key(), "-c"));
                    cmd.addAll(Settings.split(how));
                    if (!how.equals("usbasp") && !port.isEmpty()) cmd.addAll(List.of("-P", port));
                    cmd.addAll(List.of("-U", "flash:w:" + hex + ":i"));
                    out.accept("$ " + String.join(" ", cmd) + "\n");
                    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    try (InputStream in = p.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.accept(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                    int rc = p.waitFor();
                    if (rc != 0) throw new IOException("avrdude failed (exit code " + rc + ")");
                }
                ok = true;
            } catch (IOException e) {
                out.accept(">> " + e.getMessage() + "\n");
                if (how.equals("bootloader")) out.accept(BOOTLOADER_HELP);
                else if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) out.accept(AVRDUDE_HELP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SwingUtilities.invokeLater(done);
            }
            if (ok && !port.isEmpty()) watch(port, started);
        }, "evia-upload");
        th.setDaemon(true);
        th.start();
    }

    /** The serial port in the Output window (115200 baud, as the runtime's UART), until stop. */
    private void watch(String port, Runnable started) {
        SerialPort p;
        try {
            p = SerialPort.getCommPort(port);
        } catch (RuntimeException e) {
            return;
        }
        p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!p.openPort()) {
            out.accept(">> can't open " + port + " to show what the program prints\n");
            return;
        }
        monitor = p;
        out.accept(">> " + port + " at 115200 baud: what the program prints (Stop Executable closes the port)\n");
        SwingUtilities.invokeLater(started);
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);
        byte[] buf = new byte[1024];
        StringBuilder pending = new StringBuilder();
        while (monitor == p) {
            int n = p.readBytes(buf, buf.length);
            if (n < 0) break;   // the board went away
            if (n == 0) continue;
            pending.append(new String(buf, 0, n, StandardCharsets.UTF_8).replace("\r", ""));
            out.accept(pending.toString());
            pending.setLength(0);
        }
        p.closePort();
        if (monitor == p) monitor = null;
        out.accept("\n>> " + port + " closed\n");
    }

    /** A line typed in the Input field, to the chip. */
    void send(String line) {
        SerialPort p = monitor;
        if (p == null) return;
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        p.writeBytes(b, b.length);
    }

    void stop() {
        monitor = null;
    }

    /**
     * Chip Upload settings: the serial port and how to upload. Returns false when cancelled. Asked the first time
     * Upload has no port.
     */
    static boolean choose(Component parent, AvrChip chip) {
        JComboBox<Object> port = new JComboBox<>();
        port.setEditable(true);
        for (SerialPorts.Port p : SerialPorts.scan()) port.addItem(p);
        String current = Settings.serialPort();
        if (!current.isEmpty()) port.setSelectedItem(current);
        else if (port.getItemCount() > 0) port.setSelectedIndex(0);
        JComboBox<Method> method = new JComboBox<>(METHODS.toArray(new Method[0]));
        for (Method m : METHODS) if (m.key().equals(Settings.nativeUpload())) method.setSelectedItem(m);
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        c.gridx = 0; c.gridy = 0;
        p.add(new JLabel(Text.t("Serial port:")), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(port, c);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        p.add(new JLabel(Text.t("Upload with:")), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        p.add(method, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        String board = chip == null ? "" : "<br>" + Text.t("This chip:") + " " + chip.board() + ".";
        p.add(new JLabel("<html><div style='width:420px'>" + Text.t("The bootloader needs no other software: it is how an Arduino Mega 2560 and boards with Optiboot (MiniCore for the ATmega8, MightyCore for the ATmega16 and ATmega32) are programmed over USB. A chip without a bootloader is programmed with an ISP programmer through avrdude, which Settings > Packages can install. After the upload the port shows what the program prints.") + board + "</div></html>"), c);
        int r = JOptionPane.showConfirmDialog(parent, p, Text.t("Chip Upload"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return false;
        Object sel = port.getSelectedItem();
        String name = sel instanceof SerialPorts.Port sp ? sp.name() : sel == null ? "" : sel.toString().trim();
        Settings.setSerialPort(name);
        if (method.getSelectedItem() instanceof Method m) Settings.setNativeUpload(m.key());
        return true;
    }

    private static final String BOOTLOADER_HELP = """
            >> The bootloader did not take the program. Check that:
                 - the port is the board's (Run > Chip Upload Settings...); on Linux the user must be in the dialout group
                 - the chip is the one in Target, at the clock in Clock
                 - the board has a bootloader at 115200 baud: an Arduino Mega 2560, or Optiboot from MiniCore (ATmega8)
                   or MightyCore (ATmega16, ATmega32). Without one, use an ISP programmer (Upload with: USBasp ...)
            """;

    private static final String AVRDUDE_HELP = """
            >> avrdude was not found. Install it (Linux: sudo apt install avrdude; Settings > Packages has it too),
               or upload through the board's bootloader, which needs nothing else.
            """;
}
