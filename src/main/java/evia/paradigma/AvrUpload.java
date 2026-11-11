package evia.paradigma;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Uploads a program (Intel HEX) to an AVR chip through its serial bootloader, with no avrdude: STK500 version 1 as
 * Optiboot speaks it (MiniCore's ATmega8, MightyCore's ATmega16 and ATmega32) and STK500 version 2 as the Arduino
 * Mega 2560's bootloader speaks it. The board is reset through DTR and RTS, as the Arduino IDE does; every page is
 * written, read back and compared.
 */
final class AvrUpload {
    private AvrUpload() {}

    /** The serial line to the bootloader (a real port, or a test's stand-in). */
    interface Wire extends AutoCloseable {
        void write(byte[] b) throws IOException;

        /** The next byte, or -1 after timeoutMs without one. */
        int read(int timeoutMs) throws IOException;

        /** Pulses the reset line (DTR and RTS), then waits for the bootloader to start. */
        void reset() throws IOException;

        /** Drops whatever arrived and was not read. */
        void drain() throws IOException;

        @Override void close() throws IOException;
    }

    /** The flash image of an Intel HEX file, from address 0 (gaps are 0xFF). */
    static byte[] image(String hex) {
        byte[] mem = new byte[0];
        int base = 0, end = 0;
        for (String raw : hex.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) != ':') throw new IllegalArgumentException("not Intel HEX: " + line);
            byte[] r = new byte[(line.length() - 1) / 2];
            for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(line.substring(1 + 2 * i, 3 + 2 * i), 16);
            int sum = 0;
            for (byte x : r) sum += x & 0xFF;
            if ((sum & 0xFF) != 0) throw new IllegalArgumentException("bad checksum: " + line);
            int n = r[0] & 0xFF, addr = (r[1] & 0xFF) << 8 | (r[2] & 0xFF), type = r[3] & 0xFF;
            switch (type) {
                case 0 -> {
                    int at = base + addr;
                    if (at + n > mem.length) {
                        int old = mem.length;
                        mem = Arrays.copyOf(mem, Math.max(at + n, mem.length * 2));
                        Arrays.fill(mem, old, mem.length, (byte) 0xFF);
                    }
                    System.arraycopy(r, 4, mem, at, n);
                    end = Math.max(end, at + n);
                }
                case 1 -> { }
                case 2 -> base = ((r[4] & 0xFF) << 8 | (r[5] & 0xFF)) << 4;
                case 4 -> base = ((r[4] & 0xFF) << 8 | (r[5] & 0xFF)) << 16;
                default -> { }   // start addresses: not needed
            }
        }
        return Arrays.copyOf(mem, end);
    }

    /** Uploads through the bootloader on a serial port. Messages go to log as the upload goes. */
    static void viaBootloader(String port, AvrChip chip, String hex, Consumer<String> log) throws IOException {
        try (Wire w = serial(port, 115200)) {
            upload(w, chip, image(hex), log);
        }
    }

    /** The upload itself, over any wire. */
    static void upload(Wire w, AvrChip chip, byte[] image, Consumer<String> log) throws IOException {
        int room = chip.flashBytes() - chip.bootloaderBytes();
        if (image.length > room) throw new IOException("the program is " + image.length + " bytes; the " + chip.display() + " has room for " + room);
        w.reset();
        if (chip.stk500v2()) new V2(w).upload(chip, image, log);
        else new V1(w).upload(chip, image, log);
    }

    private static byte[] pad(byte[] image, int page) {
        int n = (image.length + page - 1) / page * page;
        byte[] b = Arrays.copyOf(image, Math.max(n, page));
        Arrays.fill(b, image.length, b.length, (byte) 0xFF);
        return b;
    }

    private static String hex(int[] sig) {
        return String.format("%02X %02X %02X", sig[0], sig[1], sig[2]);
    }

    private static void checkSignature(AvrChip chip, int[] got) throws IOException {
        if (!Arrays.equals(got, chip.signature())) {
            String other = "";
            for (AvrChip c : AvrChip.values()) if (Arrays.equals(got, c.signature())) other = " - that is an " + c.display();
            throw new IOException("the chip says it is " + hex(got) + other + ", not an " + chip.display() + " (" + hex(chip.signature()) + ")");
        }
    }

    // ================================================================ STK500 version 1 (Optiboot)

    private static final class V1 {
        static final int OK = 0x10, INSYNC = 0x14, EOP = 0x20;
        final Wire w;

        V1(Wire w) {
            this.w = w;
        }

        /** Sends a command and its CRC_EOP; returns the n bytes between INSYNC and OK. */
        byte[] cmd(int n, int... bytes) throws IOException {
            byte[] b = new byte[bytes.length + 1];
            for (int i = 0; i < bytes.length; i++) b[i] = (byte) bytes[i];
            b[bytes.length] = EOP;
            return send(b, n);
        }

        byte[] send(byte[] b, int n) throws IOException {
            w.write(b);
            int c = w.read(1000);
            if (c != INSYNC) throw new IOException(c < 0 ? "the bootloader stopped answering" : String.format("the bootloader is out of sync (0x%02X)", c));
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                int x = w.read(1000);
                if (x < 0) throw new IOException("the bootloader stopped answering");
                data[i] = (byte) x;
            }
            c = w.read(1000);
            if (c != OK) throw new IOException(String.format("the bootloader refused a command (0x%02X)", c));
            return data;
        }

        void sync() throws IOException {
            for (int attempt = 0; attempt < 10; attempt++) {
                w.drain();
                w.write(new byte[]{0x30, EOP});   // STK_GET_SYNC
                if (w.read(300) == INSYNC && w.read(300) == OK) {
                    w.drain();
                    return;
                }
            }
            throw new IOException("no bootloader answered: is it the right port, and does the board have Optiboot (MiniCore, MightyCore)?");
        }

        void address(int byteAddress) throws IOException {
            int word = byteAddress / 2;
            cmd(0, 0x55, word & 0xFF, (word >> 8) & 0xFF);   // STK_LOAD_ADDRESS
        }

        void upload(AvrChip chip, byte[] image, Consumer<String> log) throws IOException {
            sync();
            byte[] s = cmd(3, 0x75);                          // STK_READ_SIGN
            checkSignature(chip, new int[]{s[0] & 0xFF, s[1] & 0xFF, s[2] & 0xFF});
            cmd(0, 0x50);                                     // STK_ENTER_PROGMODE
            int page = chip.pageBytes();
            byte[] b = pad(image, page);
            log.accept(String.format("writing %,d bytes (%d pages of %d)...", image.length, b.length / page, page));
            for (int at = 0; at < b.length; at += page) {
                address(at);
                byte[] m = new byte[5 + page];
                m[0] = 0x64;                                  // STK_PROG_PAGE
                m[1] = (byte) (page >> 8);
                m[2] = (byte) page;
                m[3] = 'F';
                System.arraycopy(b, at, m, 4, page);
                m[4 + page] = EOP;
                send(m, 0);
            }
            log.accept("checking...");
            for (int at = 0; at < b.length; at += page) {
                address(at);
                byte[] got = cmd(page, 0x74, page >> 8, page & 0xFF, 'F');   // STK_READ_PAGE
                if (!Arrays.equals(got, Arrays.copyOfRange(b, at, at + page))) {
                    throw new IOException(String.format("the flash at 0x%04X is not what was written", at));
                }
            }
            cmd(0, 0x51);                                     // STK_LEAVE_PROGMODE: the program starts
        }
    }

    // ================================================================ STK500 version 2 (the Mega 2560's bootloader)

    private static final class V2 {
        final Wire w;
        int seq;

        V2(Wire w) {
            this.w = w;
        }

        /** One message and its answer's body; the answer must start with the command and STATUS_CMD_OK. */
        byte[] cmd(byte[] body, int timeoutMs) throws IOException {
            int n = body.length;
            byte[] m = new byte[n + 6];
            m[0] = 0x1B;
            m[1] = (byte) seq;
            m[2] = (byte) (n >> 8);
            m[3] = (byte) n;
            m[4] = 0x0E;
            System.arraycopy(body, 0, m, 5, n);
            int sum = 0;
            for (int i = 0; i < n + 5; i++) sum ^= m[i];
            m[n + 5] = (byte) sum;
            w.write(m);
            byte[] answer = answer(timeoutMs);
            seq = (seq + 1) & 0xFF;
            if (answer.length < 2 || answer[0] != body[0]) throw new IOException("the bootloader answered another command");
            if (answer[1] != 0) throw new IOException(String.format("the bootloader refused a command (status 0x%02X)", answer[1] & 0xFF));
            return answer;
        }

        byte[] answer(int timeoutMs) throws IOException {
            int c;
            do {
                c = w.read(timeoutMs);
                if (c < 0) throw new IOException("the bootloader stopped answering");
            } while (c != 0x1B);
            int s = need(timeoutMs), hi = need(timeoutMs), lo = need(timeoutMs), token = need(timeoutMs);
            int n = hi << 8 | lo;
            if (token != 0x0E || s != seq || n > 300) throw new IOException("the bootloader's answer is garbled");
            byte[] body = new byte[n];
            int sum = 0x1B ^ s ^ hi ^ lo ^ token;
            for (int i = 0; i < n; i++) {
                body[i] = (byte) need(timeoutMs);
                sum ^= body[i] & 0xFF;
            }
            if ((sum ^ need(timeoutMs)) != 0) throw new IOException("the bootloader's answer has a bad checksum");
            return body;
        }

        int need(int timeoutMs) throws IOException {
            int c = w.read(timeoutMs);
            if (c < 0) throw new IOException("the bootloader stopped answering");
            return c;
        }

        void address(AvrChip chip, int byteAddress) throws IOException {
            int word = byteAddress / 2;
            if (chip.flashBytes() > 128 * 1024) word |= 0x80000000;   // the bootloader then sets RAMPZ too
            cmd(new byte[]{0x06, (byte) (word >>> 24), (byte) (word >> 16), (byte) (word >> 8), (byte) word}, 1000);   // CMD_LOAD_ADDRESS
        }

        void upload(AvrChip chip, byte[] image, Consumer<String> log) throws IOException {
            IOException last = null;
            boolean on = false;
            for (int attempt = 0; attempt < 5 && !on; attempt++) {
                try {
                    w.drain();
                    cmd(new byte[]{0x01}, 400);                // CMD_SIGN_ON
                    on = true;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (!on) throw new IOException("no bootloader answered: is it the right port? (" + last.getMessage() + ")");
            // CMD_ENTER_PROGMODE_ISP with the timings avrdude sends
            cmd(new byte[]{0x10, (byte) 0xC8, 0x64, 0x19, 0x20, 0x00, 0x53, 0x03, (byte) 0xAC, 0x53, 0x00, 0x00}, 1000);
            int[] sig = new int[3];
            for (int i = 0; i < 3; i++) {
                byte[] a = cmd(new byte[]{0x1B, 0x04, 0x30, 0x00, (byte) i, 0x00}, 1000);   // CMD_READ_SIGNATURE_ISP
                sig[i] = a[2] & 0xFF;
            }
            checkSignature(chip, sig);
            int page = chip.pageBytes();
            byte[] b = pad(image, page);
            log.accept(String.format("writing %,d bytes (%d pages of %d)...", image.length, b.length / page, page));
            for (int at = 0; at < b.length; at += page) {
                address(chip, at);
                byte[] m = new byte[10 + page];
                m[0] = 0x13;                                   // CMD_PROGRAM_FLASH_ISP, page mode
                m[1] = (byte) (page >> 8);
                m[2] = (byte) page;
                m[3] = (byte) 0xC1;
                m[4] = 0x0A;
                m[5] = 0x40;
                m[6] = 0x4C;
                m[7] = 0x20;
                System.arraycopy(b, at, m, 10, page);
                cmd(m, 2000);
            }
            log.accept("checking...");
            for (int at = 0; at < b.length; at += page) {
                address(chip, at);
                byte[] a = cmd(new byte[]{0x14, (byte) (page >> 8), (byte) page, 0x20}, 2000);   // CMD_READ_FLASH_ISP
                if (a.length < page + 2 || !Arrays.equals(Arrays.copyOfRange(a, 2, 2 + page), Arrays.copyOfRange(b, at, at + page))) {
                    throw new IOException(String.format("the flash at 0x%05X is not what was written", at));
                }
            }
            cmd(new byte[]{0x11, 0x01, 0x01}, 1000);           // CMD_LEAVE_PROGMODE_ISP: the program starts
        }
    }

    // ================================================================ the serial port

    static Wire serial(String name, int baud) throws IOException {
        SerialPort p;
        try {
            p = SerialPort.getCommPort(name);
        } catch (RuntimeException e) {
            throw new IOException("there is no serial port " + name + " (is the board plugged in?)");
        }
        p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!p.openPort()) throw new IOException("can't open " + name + " (in use, or no permission: on Linux the user must be in the dialout group)");
        return new Wire() {
            final byte[] one = new byte[1];

            @Override public void write(byte[] b) throws IOException {
                if (p.writeBytes(b, b.length) != b.length) throw new IOException("can't write to " + name);
            }

            @Override public int read(int timeoutMs) {
                p.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, timeoutMs, 0);
                return p.readBytes(one, 1) == 1 ? one[0] & 0xFF : -1;
            }

            @Override public void reset() {
                p.clearDTR();
                p.clearRTS();
                sleep(250);
                p.setDTR();
                p.setRTS();
                sleep(50);
            }

            @Override public void drain() {
                p.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
                byte[] junk = new byte[256];
                while (p.bytesAvailable() > 0 && p.readBytes(junk, junk.length) > 0) {
                    // dropped
                }
            }

            @Override public void close() {
                p.closePort();
            }
        };
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
