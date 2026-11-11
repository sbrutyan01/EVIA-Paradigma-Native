package evia.paradigma;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The built-in uploader against a bootloader running in simavr (evia_sim --boot ... --pty): uploads the program,
 * then prints what it sends for a few seconds (see check-avr.sh).
 *
 *   AvrUploadTest CHIP program.hex PORT SECONDS
 */
public final class AvrUploadTest {
    public static void main(String[] args) throws Exception {
        AvrChip chip = AvrChip.of(args[0]);
        try (AvrUpload.Wire w = AvrUpload.serial(args[2], 115200)) {
            AvrUpload.upload(w, chip, AvrUpload.image(Files.readString(Path.of(args[1]))), s -> { });
            long end = System.currentTimeMillis() + 1000L * Integer.parseInt(args[3]);
            StringBuilder out = new StringBuilder();
            while (System.currentTimeMillis() < end) {
                int c = w.read(100);
                if (c >= 0 && c != '\r') out.append((char) c);
            }
            System.out.print(out);
        }
    }
}
