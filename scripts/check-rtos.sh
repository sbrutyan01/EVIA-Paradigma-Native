#!/usr/bin/env bash
# The RTOS kernel of the native AVR compiler (avr/rtos.er): examples/rtos/*.evia built for the ATmega32 and the
# ATmega2560 (a 3-byte return address in every task frame) and run in simavr. When tasks print depends on the
# chip's timing, so the check takes the lines in any order (<name>.lines, sorted) and then checks what the
# kernel promises:
#  - the consumer (priority 2) prints each item right after the producer signals it (preemption on signal);
#  - the main program's mutex-held lines come in order; the wait with a time limit times out;
#  - the LED task toggles every 250 ms while a task that never sleeps runs (preemption by the tick).
# A program that breaks the rules has to be refused: a task with parameters, a stack below 128 bytes.
#
# Usage: [EVIA_SIM=path] scripts/check-rtos.sh [jar]
set -uo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | grep -v original | sort -V | tail -n 1)}"
EVIA_SIM="${EVIA_SIM:-build/evia_sim}"
OUT=build/rtos-checks
rm -rf "$OUT" && mkdir -p "$OUT"
if [ ! -x "$EVIA_SIM" ]; then echo "SKIP  no simavr at $EVIA_SIM (scripts/simavr/build.sh builds it)"; exit 0; fi
ok=0; failed=0
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }

for src in examples/rtos/*.evia; do
  name=$(basename "$src" .evia)
  for chip in "atmega32 B0" "atmega2560 B7"; do
    set -- $chip
    mcu=$1; led=$2; base="$OUT/$name-$mcu"
    if ! java -jar "$JAR" --avr "$src" --chip "$mcu" -o "$base.hex" > /dev/null 2> "$base.gen"; then
      fail "$name $mcu: not compiled"; head -n 3 "$base.gen"; continue
    fi
    # the idle task runs for ever: simavr stops at the time limit (exit code 3)
    "$EVIA_SIM" "$mcu" "$base.hex" --watch "$led" --seconds 4 > "$base.raw" 2> "$base.pins"
    tr -d '\r' < "$base.raw" > "$base.out"
    if ! sort "$base.out" | diff -q - "examples/rtos/$name.lines" > /dev/null; then
      fail "$name $mcu: other lines than examples/rtos/$name.lines"; sort "$base.out" | diff - "examples/rtos/$name.lines" | head -6; continue
    fi
    if [ "$name" = rtos_demo ]; then
      seq=$(grep -E '^(produced|consumed|timeout)' "$base.out" | tr '\n' ',')
      want="produced 10,consumed 10,produced 20,consumed 20,produced 30,consumed 30,produced 40,consumed 40,produced 50,consumed 50,timeout after 200 ms,"
      if [ "$seq" != "$want" ]; then fail "$name $mcu: producer and consumer out of step: $seq"; continue; fi
      if [ "$(grep -E '^main' "$base.out" | tr '\n' ',')" != "main 1,main 2,main 3,main done," ]; then
        fail "$name $mcu: the main program's lines out of order"; continue
      fi
      # the LED: 10 switches, 250 ms apart (within 3 ms), beside the spinner
      if ! grep -E "^\[[0-9]+ ms\] P$led [01]" "$base.pins" | sed -E 's/^\[([0-9]+) ms.*/\1/' | awk '
          NR > 1 { d = $1 - prev; if (d < 247 || d > 253) bad = 1 }
          { prev = $1; n++ }
          END { exit bad || n != 10 }'; then
        fail "$name $mcu: the LED did not switch every 250 ms"; grep "P$led" "$base.pins" | head -12 | tr '\n' ' '; echo; continue
      fi
    fi
    ok=$((ok + 1))
  done
done

# programs the compiler has to refuse
python3 -I - "$OUT" <<'PY'
import sys
out = sys.argv[1]
head = "# EVIA Paradigma program v3\n"
main = "B|M|1|START|300|20||-|-|\nB|M|2|END|300|110||-|-|\nL|M|1|0|2\n"
task = "B|T:task|1|ENTER|60|30||-|-|\nB|T:task|2|EXIT|60|125|done|-|-|\nL|T:task|1|0|2\n"
open(out + "/params.evia", "w").write(head + "C|T|6ebe96\nF|T|x|0\nM|T|task|x\n" + task + main)
open(out + "/stack.evia", "w").write(head + "C|T|6ebe96\nF|T|stack|64\nM|T|task|\n" + task + main)
PY
for bad in "params:can't have parameters" "stack:must be at least 128"; do
  file=${bad%%:*}; msg=${bad#*:}
  if java -jar "$JAR" --avr "$OUT/$file.evia" --chip atmega32 -o "$OUT/$file.hex" > /dev/null 2> "$OUT/$file.gen"; then
    fail "$file: compiled, should be refused"
  elif ! grep -q "$msg" "$OUT/$file.gen"; then
    fail "$file: refused, but not with '$msg'"; head -n 2 "$OUT/$file.gen"
  else
    ok=$((ok + 1))
  fi
done

echo "rtos: $ok ok, $failed failed"
[ "$failed" = 0 ]
