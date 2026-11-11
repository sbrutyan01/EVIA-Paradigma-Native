#!/usr/bin/env bash
# The native compiler (--native): every program in examples/ and tests/programs/, safe and fast values, compiled
# straight to a Linux x86-64 executable (no compiler or linker anywhere), run, and compared with the interpreter:
# the same stdout, stderr and exit code. Fast values: as in check-targets.sh, programs with text in variables are
# expected to be refused, and err_* programs are only checked with safe values.
#
# Usage: scripts/check-native.sh [jar]      (Linux on x86-64 only)
set -uo pipefail
JAR="${1:-$(ls target/evia-paradigma-*.jar | sort -V | tail -n 1)}"
if [ "$(uname -s)-$(uname -m)" != "Linux-x86_64" ]; then echo "SKIP  the executables are for Linux x86-64"; exit 0; fi
OUT=build/native-checks
rm -rf "$OUT" && mkdir -p "$OUT"
ok=0; refused=0; failed=0
for src in examples/*.evia tests/programs/*.evia; do
  name=$(basename "$src" .evia)
  input="$(dirname "$src")/$name.input"
  [ -f "$input" ] || input=/dev/null
  java -jar "$JAR" --run "$src" < "$input" > "$OUT/$name.expected.out" 2> "$OUT/$name.expected.err"
  echo $? > "$OUT/$name.expected.rc"
  sed -i '/^Picked up \(_JAVA_OPTIONS\|JAVA_TOOL_OPTIONS\|JDK_JAVA_OPTIONS\)/d' "$OUT/$name.expected.err"
  # a fresh machine: Java's preferences log their first use on stderr, which is not the program's
  sed -i '/java.util.prefs/d; /Created user preferences directory/d' "$OUT/$name.expected.err"
  for values in safe fast; do
    [[ "$name" == err_* && "$values" == fast ]] && continue
    id="$name.$values"
    if ! java -jar "$JAR" --native "$src" --values "$values" -o "$OUT/$id" > /dev/null 2> "$OUT/$id.gen.err"; then
      if [ "$values" = fast ] && grep -q "fast values only hold numbers" "$OUT/$id.gen.err"; then
        refused=$((refused + 1)); continue
      fi
      echo "FAIL  $id: not compiled"; head -n 20 "$OUT/$id.gen.err"; failed=$((failed + 1)); continue
    fi
    timeout 60 "./$OUT/$id" < "$input" > "$OUT/$id.out" 2> "$OUT/$id.err"
    echo $? > "$OUT/$id.rc"
    if diff -q "$OUT/$name.expected.out" "$OUT/$id.out" > /dev/null && diff -q "$OUT/$name.expected.err" "$OUT/$id.err" > /dev/null \
       && diff -q "$OUT/$name.expected.rc" "$OUT/$id.rc" > /dev/null; then
      ok=$((ok + 1))
    else
      echo "FAIL  $id: differs from the interpreter"
      diff "$OUT/$name.expected.out" "$OUT/$id.out" | head -n 10
      diff "$OUT/$name.expected.err" "$OUT/$id.err" | head -n 5
      echo "  exit code: expected $(cat "$OUT/$name.expected.rc"), got $(cat "$OUT/$id.rc")"
      failed=$((failed + 1))
    fi
  done
done
# ---- the numbers: printing and reading, exactly as Java does, for edge cases and random doubles
javac -cp "$JAR" -d "$OUT/classes" scripts/native-checks/evia/paradigma/NumberCheck.java
java -cp "$OUT/classes:$JAR" evia.paradigma.NumberCheck "$OUT/number-check" "${NUMBER_CASES:-50000}"
"./$OUT/number-check" < "$OUT/number-check.in" > "$OUT/number-check.out"
if diff -q "$OUT/number-check.exp" "$OUT/number-check.out" > /dev/null; then
  echo "ok    $(wc -l < "$OUT/number-check.exp") numbers read and written exactly as Java does"
else
  echo "FAIL  numbers differ from Java's:"; diff "$OUT/number-check.exp" "$OUT/number-check.out" | head -n 10
  failed=$((failed + 1))
fi

# ---- Export App: the executable, the .tar.gz (installed for a throwaway user) and the .deb
EXP="$OUT/export"
if java -jar "$JAR" --export-app examples/factorial.evia "$EXP" --name "Factorial Check" --version 1.0 > /dev/null; then
  got=$(echo 6 | "$EXP/factorial-check")
  tar xzf "$EXP/factorial-check-1.0-linux-x86_64.tar.gz" -C "$EXP"
  fake="$(pwd)/$EXP/home"
  mkdir -p "$fake"
  HOME="$fake" sh "$EXP/factorial-check-1.0-linux-x86_64/install.sh" > /dev/null
  installed=$(echo 6 | "$fake/.local/bin/factorial-check")
  HOME="$fake" sh "$EXP/factorial-check-1.0-linux-x86_64/uninstall.sh" > /dev/null
  deb_ok=1
  if command -v dpkg-deb > /dev/null; then
    mkdir -p "$EXP/deb"
    dpkg-deb -x "$EXP/factorial-check_1.0_amd64.deb" "$EXP/deb" && dpkg-deb -I "$EXP/factorial-check_1.0_amd64.deb" | grep -q "Package: factorial-check" \
      && [ "$(echo 6 | "$EXP/deb/usr/bin/factorial-check")" = "Enter a value for n: 6! = 720" ] || deb_ok=0
  fi
  if [ "$got" = "Enter a value for n: 6! = 720" ] && [ "$installed" = "$got" ] && [ ! -e "$fake/.local/bin/factorial-check" ] && [ $deb_ok = 1 ]; then
    echo "ok    Export App: the executable, the .tar.gz (install.sh, uninstall.sh) and the .deb"
  else
    echo "FAIL  Export App: '$got' '$installed' deb=$deb_ok"; failed=$((failed + 1))
  fi
else
  echo "FAIL  Export App did not write the app"; failed=$((failed + 1))
fi

echo "$ok native executables identical to the interpreter, $refused refused by fast values (text in variables), $failed failed"
[ "$failed" -eq 0 ]
