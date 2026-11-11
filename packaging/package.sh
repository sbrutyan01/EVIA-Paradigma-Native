#!/usr/bin/env bash
# Builds a native installer with jpackage (JDK 25), bundling a minimal Java runtime,
# so users don't need Java installed.
#
#   packaging/package.sh linux        -> packages/evia-paradigma_<ver>_amd64.deb
#   packaging/package.sh windows      -> packages/EVIA-Paradigma-<ver>-windows-x64.exe
#   packaging/package.sh macos-arm64  -> packages/EVIA-Paradigma-<ver>-macos-arm64.dmg
#   packaging/package.sh macos-x64    -> packages/EVIA-Paradigma-<ver>-macos-x64.dmg
#
# EDITION=native builds EVIA Paradigma Native instead: the same jar started with -Devia.edition=native (no code
# generation; Build makes executables straight from the blocks), with names of its own so that both editions can be
# installed side by side: EVIA-Paradigma-Native-<ver>-windows-x64.exe, evia-paradigma-native_<ver>_amd64.deb ...
#
# Expects the application jar in dist/input/ (from "mvn package"). Run from the repository root.
set -euo pipefail

KIND="${1:?usage: package.sh linux|windows|macos-arm64|macos-x64}"
VERSION=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' pom.xml | head -n 1)
EDITION="${EDITION:-native}"   # this project builds EVIA Paradigma Native
NAME="EVIA Paradigma"
SLUG="EVIA-Paradigma"          # in the installers' file names
DEB=evia-paradigma
DESC="Visual block programming with classes and C++20 generation"
WIN_UUID=6695b4ed-ebb6-48bf-80c4-46391ab56990
MAC_ID=io.github.sbrutyan01.eviaparadigma
LINUX_RES=packaging/linux
ASSOC=(--file-associations packaging/evia-files.properties --file-associations packaging/evia-project.properties)
EXTRA=()
if [ "$EDITION" = native ]; then
  NAME="EVIA Paradigma Native"
  SLUG="EVIA-Paradigma-Native"
  DEB=evia-paradigma-native
  DESC="Visual block programming compiled straight to executables"
  WIN_UUID=3c0f2b6e-8f3e-4b7a-9d55-6a1e2f7c4b19
  MAC_ID=io.github.sbrutyan01.eviaparadigmanative
  LINUX_RES=packaging/linux-native
  ASSOC=()                     # .evia files stay with EVIA Paradigma
  EXTRA=(--java-options -Devia.edition=native)
  TOOLCHAINS=/nonexistent      # it needs none
fi
JAR=$(ls dist/input/evia-paradigma-*.jar | head -n 1)
WORK=build/jpackage
OUT=packages
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
echo "Packaging $NAME $VERSION for $KIND using $(jpackage --version)"

# ---- 1. application image: launcher + jar + minimal runtime (java.base, java.desktop, java.prefs)
image_opts=(
  --name "$NAME"
  --vendor "Sergey Brutyan (sbrutyan01)"
  --copyright "Copyright (c) 2026 Sergey Brutyan (sbrutyan01). MIT License."
  --description "$DESC"
  --input dist/input
  --main-jar "$(basename "$JAR")"
  --main-class evia.paradigma.App
  --add-modules java.base,java.desktop,java.prefs,java.logging
  --jlink-options "--strip-debug --no-man-pages --no-header-files --strip-native-commands"
  --dest "$WORK/image"
  "${EXTRA[@]}"
)

# ---- the toolchains that ride along (packaging/fetch-toolchains.sh puts them in build/toolchains):
# avr-gcc with avrdude, and the OSS CAD Suite. Left out when that folder is not there (a quick local
# build), so the installer is then the small one without them.
TOOLCHAINS="${TOOLCHAINS:-build/toolchains}"
add_toolchains() {   # destination folder inside the application image
  if [ ! -d "$TOOLCHAINS/avr-gcc" ] || [ ! -d "$TOOLCHAINS/oss-cad-suite" ]; then
    echo "No toolchains in $TOOLCHAINS: building without them (run packaging/fetch-toolchains.sh $KIND first)"
    return
  fi
  echo "Adding the toolchains: $(cat "$TOOLCHAINS"/*/EVIA-VERSION | tr '\n' ';')"
  mkdir -p "$1"
  cp -R "$TOOLCHAINS/avr-gcc" "$TOOLCHAINS/oss-cad-suite" "$1/"
}

smoke_test() {   # run the bundled app in command-line mode on an example program
  echo "Smoke test: $*"
  "$@" --run examples/example.evia | tee "$WORK/smoke.out"
  grep -q "average of evens so far: 4" "$WORK/smoke.out"
  if [ "$EDITION" = native ]; then   # and the native compiler: an executable, run where it can run
    "$@" --native examples/example.evia -o "$WORK/smoke-exe"
    if [ "$(uname -s)-$(uname -m)" = Linux-x86_64 ]; then
      "$WORK/smoke-exe" | tee "$WORK/smoke-exe.out"
      grep -q "average of evens so far: 4" "$WORK/smoke-exe.out"
    fi
  fi
}

case "$KIND" in
  linux)
    jpackage --type app-image "${image_opts[@]}" --app-version "$VERSION" --icon packaging/icons/evia.png
    add_toolchains "$WORK/image/$NAME/lib/toolchains"   # the .deb keeps bin/, lib/ and share/ only
    smoke_test "$WORK/image/$NAME/bin/$NAME"
    jpackage --type deb --app-image "$WORK/image/$NAME" --name "$NAME" --app-version "$VERSION" \
      --vendor "Sergey Brutyan (sbrutyan01)" \
      --description "$DESC" \
      --license-file LICENSE \
      "${ASSOC[@]}" \
      --icon packaging/icons/evia.png \
      --linux-package-name "$DEB" \
      --linux-deb-maintainer sbrutyan01@gmail.com \
      --linux-menu-group "Development;IDE" \
      --linux-app-category devel \
      --linux-shortcut \
      --resource-dir "$LINUX_RES" \
      --about-url https://github.com/sbrutyan01/EVIA-Paradigma \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.deb "$OUT/${DEB}_${VERSION}_amd64.deb"
    # And a generic AppImage from the same application image, for the distributions apt does not
    # serve. Skipped rather than fatal when appimagetool cannot be fetched: a release should not
    # be lost because one extra format could not be built.
    if ! EDITION="$EDITION" packaging/appimage.sh "$WORK/image/$NAME" "$VERSION"; then
      echo "WARNING: the AppImage was not built; the .deb above is unaffected" >&2
    fi
    ;;

  windows)
    # second launcher "evia.exe" is a console program for the command line: evia --run x.evia
    jpackage --type app-image "${image_opts[@]}" --app-version "$VERSION" --icon packaging/icons/evia.ico \
      --add-launcher evia=packaging/evia-cli.properties
    add_toolchains "$WORK/image/$NAME/app/toolchains"   # app/ is the folder jpackage's installer is sure to keep
    smoke_test "$WORK/image/$NAME/evia.exe"
    jpackage --type exe --app-image "$WORK/image/$NAME" --name "$NAME" --app-version "$VERSION" \
      --vendor "Sergey Brutyan (sbrutyan01)" \
      --description "$DESC" \
      --license-file LICENSE \
      "${ASSOC[@]}" \
      --icon packaging/icons/evia.ico \
      --win-menu --win-menu-group "EVIA" \
      --win-shortcut --win-shortcut-prompt \
      --win-dir-chooser \
      --win-upgrade-uuid "$WIN_UUID" \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.exe "$OUT/${SLUG}-${VERSION}-windows-x64.exe"
    ;;

  macos-arm64|macos-x64)
    ARCH="${KIND#macos-}"
    # macOS bundle versions must start with 1 or higher; the visible version is set below
    MACVER="$VERSION"
    [[ "$VERSION" == 0.* ]] && MACVER="1.${VERSION#0.}"
    jpackage --type app-image "${image_opts[@]}" --app-version "$MACVER" --icon packaging/icons/evia.icns \
      --mac-package-identifier "$MAC_ID" \
      --mac-package-name "$NAME" \
      "${ASSOC[@]}"
    APP="$WORK/image/$NAME.app"
    add_toolchains "$APP/Contents/Resources/toolchains"   # code signing allows extra files in Resources only
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Contents/Info.plist"
    codesign --force --deep --sign - "$APP"          # ad-hoc signature after editing Info.plist
    smoke_test "$APP/Contents/MacOS/$NAME"
    jpackage --type dmg --app-image "$APP" --name "$NAME" --app-version "$MACVER" \
      --mac-package-identifier "$MAC_ID" \
      --dest "$WORK/installer"
    mv "$WORK"/installer/*.dmg "$OUT/${SLUG}-${VERSION}-macos-${ARCH}.dmg"
    ;;

  *)
    echo "unknown package kind: $KIND" >&2
    exit 2
    ;;
esac

ls -l "$OUT"
