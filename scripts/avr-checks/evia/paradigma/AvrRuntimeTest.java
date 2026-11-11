package evia.paradigma;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compiles a program in the native compiler's own language (.er) together with the AVR runtime, the way --avr
 * compiles a lowered EVIA program, so random tests can reach the back end directly (see check-avr.sh).
 *
 *   AvrRuntimeTest CHIP test.er out.hex
 */
public final class AvrRuntimeTest {
    public static void main(String[] args) throws Exception {
        AvrChip chip = AvrChip.of(args[0]);
        NativeLang lang = new NativeLang();
        lang.add(NativeCompiler.read("avr/float.er"), "float.er");
        lang.add(chip.runtime(16000000), "chip.er");
        lang.add(NativeCompiler.read("avr/base.er"), "base.er");
        lang.add(Files.readString(Path.of(args[1])), args[1]);
        int[] words = new AvrGen(lang, chip).compile();
        Files.writeString(Path.of(args[2]), AvrAsm.hex(words));
    }
}
