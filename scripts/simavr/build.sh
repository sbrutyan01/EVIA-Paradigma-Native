#!/usr/bin/env bash
# Builds evia_sim (see evia_sim.c) from simavr's sources, with only the chips EVIA
# Paradigma's bare-metal runtime knows and without simavr's ELF loader (no libelf needed).
#   scripts/simavr/build.sh SIMAVR_CHECKOUT AVR_LIBC_INCLUDE_DIR OUTPUT
# AVR_LIBC_INCLUDE_DIR: avr-libc's headers (e.g. /usr/lib/avr/include); simavr's cores use them.
set -euo pipefail
SRC="$1/simavr"; INC="$2"; OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
GEN="$(mktemp -d)"
cat > "$GEN/sim_core_config.h" <<'C'
#ifndef __SIM_CORE_CONFIG_H__
#define __SIM_CORE_CONFIG_H__
#define CONFIG_SIMAVR_VERSION "evia"
#define CONFIG_MEGA328 1
#define CONFIG_MEGA2560 1
#define CONFIG_MEGA32U4 1
#define CONFIG_MEGA8 1
#define CONFIG_MEGA16 1
#define CONFIG_MEGA32 1
#endif
C
cat > "$GEN/sim_core_decl.h" <<'C'
#ifndef __SIM_CORE_DECL_H__
#define __SIM_CORE_DECL_H__
#include "sim_core_config.h"
extern avr_kind_t mega328, mega2560, mega32u4, mega8, mega16, mega32;
extern avr_kind_t * avr_kind[];
#ifdef AVR_KIND_DECL
avr_kind_t * avr_kind[] = { &mega328, &mega2560, &mega32u4, &mega8, &mega16, &mega32, NULL };
#endif
#endif
C
sim=$(ls "$SRC"/sim/*.c | grep -v -E '/(sim_elf|sim_dwarf|run_avr)\.c$')
cores=""
for c in sim_mega328 sim_mega2560 sim_mega32u4 sim_megax8 sim_mega8 sim_mega16 sim_mega32 sim_megax; do
  [ -f "$SRC/cores/$c.c" ] && cores="$cores $SRC/cores/$c.c"
done
cc -O2 -std=gnu99 -w -I"$GEN" -I"$SRC/sim" -I"$SRC/cores" -idirafter "$INC" \
   -o "$OUT" "$HERE/evia_sim.c" $sim $cores -lm -lpthread
rm -rf "$GEN"
echo "built $OUT"
