#!/usr/bin/env bash
# The native AVR compiler (--avr): programs compiled straight to Intel HEX (no avr-gcc anywhere), run in simavr
# (scripts/simavr/evia_sim) and compared with the interpreter.
#
#  1. Board programs (examples/boards/*.evia) for the ATmega8, ATmega16, ATmega32 and ATmega2560, with the same
#     button (pin 4) and analog signal (A0) as the interpreter's simulated board: the same UART output and the
#     built-in LED switching as often. Analog readings may differ by 32 counts: a line takes the chip 2-3 ms
#     (printing it), which the interpreter does not spend, and the signal moves a count every 2 ms. On the ATmega16
#     and ATmega32 (MightyCore's pins) LED_BUILTIN is pin 0, not 13.
#  2. Programs that only use numbers (examples/*.evia, tests/programs/*.evia) on the ATmega2560, with their .input
#     typed into the UART: the same output. Numbers are 32-bit floats on these chips, so programs whose results
#     need more digits than a float has are listed in FLOAT32_DIFFERS and only have to build. Fast values, as in
#     check-native.sh: err_* programs (errors that safe values report) are not run.
#
# Usage: [EVIA_SIM=path] scripts/check-avr.sh [jar]
set -uo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
EVIA_SIM="${EVIA_SIM:-build/evia_sim}"
OUT=build/avr-checks
rm -rf "$OUT" && mkdir -p "$OUT"
if [ ! -x "$EVIA_SIM" ]; then echo "SKIP  no simavr at $EVIA_SIM (scripts/simavr/build.sh builds it)"; exit 0; fi
# chip, built-in LED, button (pin 4), ADC channel of A0, LED_BUILTIN
CHIPS=("atmega8 B5 D4 0 13" "atmega16 B0 B4 0 0" "atmega32 B0 B4 0 0" "atmega2560 B7 G5 0 13")
FLOAT32_DIFFERS=" numbers "
ok=0; failed=0; refused=0
fail() { echo "FAIL  $*"; failed=$((failed + 1)); }
levels() { grep -oE "$1" "$2" | grep -oE '(HIGH|LOW|[01])$' | sed 's/HIGH/1/;s/LOW/0/' | sed '/1/,$!d' | tr -d '\n'; }

# ---- 1. board programs
for src in examples/boards/*.evia; do
  name=$(basename "$src" .evia)
  java -jar "$JAR" --run "$src" > "$OUT/$name.interp" 2> "$OUT/$name.events" < /dev/null
  sed -i '/^Picked up/d; /java.util.prefs/d; /Created user preferences directory/d' "$OUT/$name.events"
  for chip in "${CHIPS[@]}"; do
    set -- $chip
    mcu=$1; led=$2; button=$3; adc=$4; builtin=$5
    base="$OUT/$name-$mcu"
    sed "s/pin 13\b/pin $builtin/" "$OUT/$name.interp" > "$base.interp"
    if ! java -jar "$JAR" --avr "$src" --chip "$mcu" -o "$base.hex" > /dev/null 2> "$base.gen"; then
      if grep -q "not in the native AVR compiler yet" "$base.gen"; then refused=$((refused + 1)); continue; fi
      fail "$name $mcu: not compiled"; head -n 5 "$base.gen"; continue
    fi
    "$EVIA_SIM" "$mcu" "$base.hex" --watch "$led" --button "$button" --adc "$adc" --uart-loop --seconds 120 \
        > "$base.raw" 2> "$base.pins"
    rc=$?
    if [ "$rc" != 0 ]; then fail "$name $mcu: simavr (exit code $rc)"; tail -3 "$base.pins"; continue; fi
    tr -d '\r' < "$base.raw" > "$base.out"
    if [ "$name" = analog_meter ]; then
      if [ "$(wc -l < "$base.interp")" != "$(wc -l < "$base.out")" ] || ! paste -d'|' "$base.interp" "$base.out" | awk -F'|' '
          { a = $1; b = $2; sub(/^A0 = /, "", a); sub(/^A0 = /, "", b); split(a, x, " "); split(b, y, " ");
            if ((substr($1,1,4) == "A0 =") != (substr($2,1,4) == "A0 =")) bad = 1;
            else if (substr($1,1,4) == "A0 =" && (x[1] - y[1] > 32 || y[1] - x[1] > 32)) bad = 1;
            else if (substr($1,1,4) != "A0 =" && $1 != $2) bad = 1 }
          END { exit bad }'; then
        fail "$name $mcu: output"; paste -d'|' "$base.interp" "$base.out" | head -5; continue
      fi
    elif ! diff -q "$base.interp" "$base.out" > /dev/null; then
      fail "$name $mcu: output differs from the interpreter"; diff "$base.interp" "$base.out" | head -6; continue
    fi
    want=$(levels 'pin 13 (HIGH|LOW)' "$OUT/$name.events"); got=$(levels "P$led [01]" "$base.pins")
    if [ "${#want}" != "${#got}" ]; then fail "$name $mcu: LED switched ${#got} times, the interpreter ${#want} times"; continue; fi
    ok=$((ok + 1))
  done
done

# ---- 2. programs with numbers only, on the Mega 2560
for src in examples/*.evia tests/programs/*.evia; do
  name=$(basename "$src" .evia)
  [[ "$name" == err_* ]] && continue
  input="$(dirname "$src")/$name.input"
  [ -f "$input" ] || input=/dev/null
  base="$OUT/$name"
  if ! java -jar "$JAR" --avr "$src" --chip atmega2560 -o "$base.hex" > /dev/null 2> "$base.gen"; then
    if grep -q "only hold numbers\|not in the native AVR compiler yet" "$base.gen"; then refused=$((refused + 1)); continue; fi
    fail "$name: not compiled"; head -n 5 "$base.gen"; continue
  fi
  if [[ "$FLOAT32_DIFFERS" == *" $name "* ]]; then ok=$((ok + 1)); continue; fi
  java -jar "$JAR" --run "$src" < "$input" > "$base.interp" 2> /dev/null
  "$EVIA_SIM" atmega2560 "$base.hex" --input "$input" --seconds 120 > "$base.raw" 2> "$base.sim"
  tr -d '\r' < "$base.raw" > "$base.out"
  if diff -q "$base.interp" "$base.out" > /dev/null; then
    ok=$((ok + 1))
  else
    fail "$name: differs from the interpreter"; diff "$base.interp" "$base.out" | head -n 8
  fi
done
# ---- 3. random tests of the back end itself: 32-bit ints (every operator, conditions, calls with 2-5 arguments)
#         and floats (+ - * / %, comparisons, conversions, printing), on the smallest and the biggest chip
RT="$OUT/random"
mkdir -p "$RT/classes"
javac -cp "$JAR" -d "$RT/classes" scripts/avr-checks/evia/paradigma/AvrRuntimeTest.java
runs=0; wrong=0
random_run() {   # kind seed size mcu
  local id="$RT/$1-$2-$4"
  python3 "scripts/avr-checks/random_$1.py" "$2" "$id" "$3"
  if ! java -cp "$RT/classes:$JAR" evia.paradigma.AvrRuntimeTest "$4" "$id.er" "$id.hex" 2> "$id.gen"; then
    fail "random $1 $2 $4: not compiled"; head -n 3 "$id.gen"; return
  fi
  "$EVIA_SIM" "$4" "$id.hex" --seconds 30 2> /dev/null | tr -d '\r' > "$id.out"
  runs=$((runs + $(wc -l < "$id.exp")))
  if ! diff -q "$id.exp" "$id.out" > /dev/null; then
    wrong=$((wrong + 1)); fail "random $1 $2 $4: differs"; diff "$id.exp" "$id.out" | head -n 4
  fi
}
for seed in $(seq 1 "${AVR_RANDOM_SEEDS:-8}"); do
  random_run ints "$seed" 30 atmega8
  random_run ints "$seed" 30 atmega2560
  random_run floats "$seed" 35 atmega8
  random_run floats "$seed" 400 atmega2560
done
[ "$wrong" = 0 ] && echo "ok    random tests: $runs lines of results on the ATmega8 and ATmega2560, as Java and IEEE float32 have them"

echo "AVR: $ok passed, $refused not for these chips, $failed failed"
[ "$failed" = 0 ]
