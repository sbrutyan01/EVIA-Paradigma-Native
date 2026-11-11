# Third-party software

EVIA Paradigma itself is MIT licensed (see `LICENSE`). The jar and the installers also contain:

| Component | Used for | License |
|---|---|---|
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) 3.8, © FormDev Software GmbH | the IntelliJ look: light theme and Darcula | Apache License 2.0 |
| [JediTerm](https://github.com/JetBrains/jediterm) 3.76, © JetBrains s.r.o. | the Terminal window's terminal emulator | LGPL v3 or Apache License 2.0 (used under Apache 2.0) |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) 2.4.0, © JetBrains s.r.o. | needed by JediTerm and pty4j | Apache License 2.0 |
| [pty4j](https://github.com/JetBrains/pty4j) 0.13.13, © JetBrains s.r.o. | the Claude Code tab's local pseudo-terminal (its native libraries included) | Eclipse Public License 1.0 (source: the link) |
| [JNA](https://github.com/java-native-access/jna) 5.14.0 (jna and jna-platform), © Timothy Wall and contributors | needed by pty4j | Apache License 2.0 or LGPL v2.1 (used under Apache 2.0) |
| [jSerialComm](https://github.com/Fazecast/jSerialComm) 2.11.4, © Fazecast, Inc. | the Terminal window's serial ports (UART) | Apache License 2.0 or LGPL v3 (used under Apache 2.0) |
| [JSch](https://github.com/mwiede/jsch) 2.28.8 (mwiede's fork), © ymnk, JCraft, Inc. and contributors | the Terminal window's SSH | BSD-style licence |
| [SLF4J](https://www.slf4j.org) 2.0.9 (api and nop), © QOS.ch | JediTerm's logging, switched off | MIT License |
| [Inter](https://github.com/rsms/inter), © The Inter Project Authors | interface font (as "EVIA UI", see below) | SIL Open Font License 1.1 |
| [Noto Sans Armenian](https://github.com/notofonts/armenian), © The Noto Project Authors | the Armenian letters of the interface font | SIL Open Font License 1.1 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), © The JetBrains Mono Project Authors | code font | SIL Open Font License 1.1 |

"EVIA UI" is Inter with the Armenian letters of Noto Sans Armenian added, because Java does not take missing
letters from another font. It is built by `scripts/fonts/make-ui-font.py` and renamed, as the OFL asks. The font
licenses are in `src/main/resources/evia/paradigma/fonts/`.

## Microchip device headers

The PIC32 checks in CI (`scripts/check-pic32.sh`) read Microchip's device headers, fetched at test time from the Pinguino project. They are not part of this repository, the jar or the installers.

The FPGA pin locations in `Fpga.java` are facts read from the boards' published constraint and design files (Digilent master XDC files, Olimex KiCad design, Cologne Chip examples). No code or file text from them is copied. The FPGA checks in CI (`scripts/check-fpga.sh`) use the OSS CAD Suite, downloaded at test time; it is not part of this repository, the jar or the installers.

The **installers** (from 0.9.0) carry no toolchains: they are online installers. The toolchains are **packages of the EVIA server** ([evia.am/packages](https://evia.am/packages/index.json)), each passed on unchanged and fetched only when you ask for it (on the first start, in Settings → Packages and Updates, or with `--install`): **arduino-cli** 1.5.1 (GNU GPL v3, [arduino/arduino-cli](https://github.com/arduino/arduino-cli)), **avr-gcc** with avr-libc and **avrdude** (GNU GPL, avr-libc under a BSD licence; avr-gcc 16.1.0 from [ZakKemble/avr-gcc-build](https://github.com/ZakKemble/avr-gcc-build) on Windows, Arduino's avr-gcc 7.3.0 build on Linux and macOS, avrdude 8.0 from Arduino), the **Arm GNU Toolchain** 15.2.1 with newlib ([xPack build](https://github.com/xpack-dev-tools/arm-none-eabi-gcc-xpack); GCC and binutils GNU GPL v3 with the runtime library exception, newlib under BSD-style licences), **OpenOCD** 0.12.0 ([xPack build](https://github.com/xpack-dev-tools/openocd-xpack); GNU GPL v2 or later), **pic32prog** ([sergev/pic32prog](https://github.com/sergev/pic32prog) at a pinned commit, built by this repository's pic32prog workflow with libusb and hidapi linked in; GNU GPL v2, libusb LGPL 2.1, hidapi BSD) and the **OSS CAD Suite** ([YosysHQ/oss-cad-suite-build](https://github.com/YosysHQ/oss-cad-suite-build), release 2026-11-09; each tool under its own free licence, all in its `license/` folder). Each package has a `SOURCES.txt` with where it came from and where its source is; `packaging/fetch-toolchains.sh` pins every file by SHA-256, and the index pins every package archive by SHA-256 again. The 0.8.0 installers carried avr-gcc and the OSS CAD Suite inside them in the same way. The jar has none of them. Arduino sketches for STM32 boards use STMicroelectronics' core, which arduino-cli fetches from STMicroelectronics when you install it (Settings → STM32 → Install). **Microchip's XC32 is never passed on**: its licence does not allow it. Settings, PIC32, Get XC32 downloads Microchip's own free XC32 installer from microchip.com, checks it against Microchip's published file, asks you to accept Microchip's licence, and runs Microchip's installer.
