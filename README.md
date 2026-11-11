<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.png">
    <img src="docs/logo.png" alt="EVIA" width="200">
  </picture>
</p>

<h1 align="center">EVIA Paradigma Native</h1>

<p align="center">
  Visual block programming compiled straight to executables: no compiler, no assembler, no linker.
  <br><br>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma-Native/actions/workflows/build.yml"><img src="https://github.com/sbrutyan01/EVIA-Paradigma-Native/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/sbrutyan01/EVIA-Paradigma-Native/releases/latest"><img src="https://img.shields.io/badge/version-1.2.0-blue" alt="version 1.2.0"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
</p>

Drag blocks onto a canvas, wire them together, define **classes** with fields, a constructor and methods, keep your
work in **projects** under **Git**, and press **Run** to try the program. Then **Build executable** (F7): the blocks
become a **Linux x86-64 program** written by EVIA Paradigma Native itself, machine code and executable file included.
Nothing else is needed on the computer: no C compiler, no assembler, no linker, no libraries. Or pick an **AVR
chip** as the target (ATmega8, ATmega16, ATmega32, ATmega2560): **Build .hex** writes its flash, and **Upload** puts it
on the board through the board's bootloader, with no avr-gcc and no avrdude.

EVIA Paradigma Native is a fork of [EVIA Paradigma](https://github.com/sbrutyan01/EVIA-Paradigma) 0.11.0, which
generates source code in many languages for computers, phones, microcontrollers and FPGAs. This project goes the other
way: no languages, the program itself.

## Download

| System | File |
|---|---|
| Windows 10 / 11 (x64) | `EVIA-Paradigma-Native-1.2.0-windows-x64.exe` |
| macOS, Apple Silicon / Intel | `EVIA-Paradigma-Native-1.2.0-macos-arm64.dmg` / `-macos-x64.dmg` |
| Ubuntu 22.04+ / Debian 12+ | `evia-paradigma-native_1.2.0_amd64.deb` (`/opt/evia-paradigma-native`, command `evia-paradigma-native`) |
| Any Linux (x86-64) | `EVIA-Paradigma-Native-1.2.0-x86_64.AppImage` |
| Any OS with Java 25 | `evia-paradigma-native-1.2.0.jar` |

From the [latest release](https://github.com/sbrutyan01/EVIA-Paradigma-Native/releases/latest). Each installer
carries its own Java runtime. It installs next to EVIA Paradigma if you have both.

## What the executable is

| | |
|---|---|
| File | a static ELF executable for Linux on x86-64, about 33 KB, that talks to the kernel with system calls only (no libc, no shared libraries): it runs on any x86-64 Linux |
| Behaviour | exactly Run's: the same output, the same error messages (`>> error: ...` on stderr), the same exit code, the 400-call limit on nested methods, Input with its prompt |
| Numbers | written as Java writes them (the shortest digits that read back: `0.30000000000000004`, `1.0E-5`) and read as `Double.parseDouble` reads them, correctly rounded |
| Values | **Safe**: numbers and text with every check; **Fast**: plain doubles, text only in Print |
| Blocks | Start, Set, Print, Input, If/Else, For, While, Do-While, End, classes and objects, Delay and Millis |

On Windows and macOS, EVIA Paradigma Native builds the same Linux executables (copy them to a Linux PC, or run them
in WSL). Windows and ARM executables are the next steps.

## Using it

- **Run** (F5) runs the diagram in the editor, block by block, with the running block highlighted.
- **Build executable** (F7) writes the program: into the project's `build/` folder, or next to the program file.
- **Run executable** (F6) builds and runs it; type Input into the field under the output.
- **Values** in the toolbar: Safe or Fast.

## Export App

**Export app...** (Ctrl+Shift+E) turns the program into something to hand to other people, for any Linux on
x86-64. Pick the app's name, command, version and description, and the forms:

| Form | What it is | On the other computer |
|---|---|---|
| The executable | the program itself, static, nothing else needed | `./factorial-calculator` |
| `.tar.gz` | the program, a menu entry, an icon, `install.sh` and `uninstall.sh` | `tar xzf ...` then `./install.sh` (for you, in `~/.local`, no root) or `sudo ./install.sh --system` |
| `.deb` | `/usr/bin/COMMAND`, a menu entry and an icon | `sudo apt install ./factorial-calculator_1.0_amd64.deb` |

The menu entry opens a terminal (Print and Input talk through one) and keeps it open when the program ends, until
Enter is pressed. Everything is written by EVIA Paradigma Native itself, tar, gzip and the .deb included.

The command line does the same:

```
java -jar evia-paradigma-native-1.2.0.jar --native program.evia -o program [--values fast]
./program
java -jar evia-paradigma-native-1.2.0.jar --run program.evia        # the interpreter
java -jar evia-paradigma-native-1.2.0.jar --export-app program.evia ~/apps --name "My App" --version 1.0
```

## AVR chips

**Target** in the toolbar picks what Build makes: a Linux x86-64 executable, or the program for an AVR chip, with the
chip's **Clock**. With a chip, the palette has the board blocks it runs.

| | |
|---|---|
| Chips | ATmega8, ATmega16, ATmega32 (MightyCore's pin numbers), ATmega2560 (Arduino Mega) |
| Blocks | everything above, plus Pin Mode, Digital Read / Write, Analog Read / Write (PWM), UART Open / Write / Read, Delay, Millis |
| Numbers | fast values: IEEE 32-bit floats (in AVR assembly), printed as Run prints them; text only in Print |
| Print and Input | the serial port, 115200 baud; Input reads a line |
| Size | blink with its prints: 5.5 KB, of the 7.5 KB the ATmega8 has beside its bootloader |

- **Build .hex** (F7) writes the Intel HEX file and tells how much flash and RAM the program takes.
- **Upload** (F6) builds and writes it through the board's bootloader over USB: the Arduino Mega 2560's (STK500 v2), or
  Optiboot (STK500 v1) as MiniCore (ATmega8) and MightyCore (ATmega16, ATmega32) install it. Every page is read back.
  Then the Output window shows what the program prints, and the Input field sends it lines.
- **Run → Chip Upload Settings...**: the serial port, or an ISP programmer through avrdude (USBasp, Arduino as ISP,
  AVRISP mkII) for a chip without a bootloader.
- **File → Open Example → AVR Chips**: blink, button, fade, analog meter, a class with a pin, UART echo.

```
java -jar evia-paradigma-native-1.2.0.jar --avr blink.evia --chip atmega8 -o blink.hex --upload /dev/ttyUSB0
```

## How it works

The blocks are lowered to a small internal language: every block becomes a label and every wire a jump, a class a
block of memory with its fields, a method a function that returns the number of the Exit it left through. The
runtime (strings, buffered output, Input, errors, big whole numbers for exact number conversion) is written in the
same language, in `src/main/resources/evia/paradigma/native/`. `NativeLang` compiles both to x86-64 with
`NativeAsm`, and `NativeElf` writes the file. For AVR chips `AvrGen` compiles the same language to AVR machine code
(`AvrAsm`, with `AvrFloat` for the floats and the runtime in `native/avr/`), and `AvrUpload` speaks the bootloaders'
protocols over the serial port.

## Building from source

```
mvn package                     # target/evia-paradigma-native-1.2.0.jar
scripts/check-native.sh         # every example and test against the interpreter, 100,000 numbers against Java
scripts/check-avr.sh            # AVR programs in simavr against the interpreter, random tests, uploads (needs simavr:
                                #   scripts/simavr/build.sh, as in .github/workflows/build.yml)
scripts/check-app.sh            # the editor, classes, modules, projects and Git
packaging/package.sh linux      # the .deb and the AppImage (windows, macos-arm64, macos-x64 on those systems)
```

Java 25 and Maven. MIT License.
