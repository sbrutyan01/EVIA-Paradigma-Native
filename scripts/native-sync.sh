#!/usr/bin/env bash
# On dell: keeps https://evia.am/native in step with the latest GitHub release of EVIA Paradigma Native, which is
# where the application looks for updates (Settings > Updates, and a quiet check at every start).
#
#   scripts/native-sync.sh             copy a new release if there is one (the timer runs this every 10 minutes)
#   scripts/native-sync.sh --force     copy the latest release again
#   scripts/native-sync.sh --install   install it as a systemd user timer (no sudo): evia-native-sync.timer
#
# It reads GitHub's public API, so it needs no token. Each release goes to native/<version>/ (the jar, which the
# application installs as its update, and the installers), with index.json (the format of the EVIA package server:
# one package, "app") and index.html (a download page). The two newest versions are kept.
set -euo pipefail
REPO="${NATIVE_REPO:-sbrutyan01/EVIA-Paradigma-Native}"
ROOT="${NATIVE_ROOT:-/var/www/evia/native}"
say() { echo "native-sync: $*"; }

if [ "${1:-}" = "--install" ]; then
    mkdir -p "$HOME/.local/bin" "$HOME/.config/systemd/user"
    install -m 755 "$0" "$HOME/.local/bin/evia-native-sync"
    cat > "$HOME/.config/systemd/user/evia-native-sync.service" <<EOF
[Unit]
Description=Copy the latest release of EVIA Paradigma Native to evia.am/native

[Service]
Type=oneshot
Environment=NATIVE_ROOT=$ROOT
ExecStart=$HOME/.local/bin/evia-native-sync
EOF
    cat > "$HOME/.config/systemd/user/evia-native-sync.timer" <<EOF
[Unit]
Description=Check for a new release of EVIA Paradigma Native every 10 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=10min
Persistent=true

[Install]
WantedBy=timers.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable --now evia-native-sync.timer
    say "installed: $(systemctl --user is-active evia-native-sync.timer) (systemctl --user list-timers evia-native-sync.timer)"
    exit 0
fi

mkdir -p "$ROOT" || { say "$ROOT is not writable"; exit 1; }
json=$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest")
tag=$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
ver="${tag#v}"
if [ "${1:-}" != "--force" ] && [ -f "$ROOT/$ver/.complete" ]; then
    exit 0   # nothing new
fi
say "copying $tag"
tmp="$ROOT/.$ver.part"
rm -rf "$tmp" && mkdir -p "$tmp"
printf '%s' "$json" | python3 -c '
import json, sys
for a in json.load(sys.stdin)["assets"]:
    print(a["name"], a["size"], a["browser_download_url"])' | while read -r name size url; do
    curl -fsSL --retry 3 -o "$tmp/$name" "$url"
    got=$(wc -c < "$tmp/$name" | tr -d ' ')
    [ "$got" = "$size" ] || { say "$name: $got bytes, expected $size"; exit 1; }
done
touch "$tmp/.complete"
rm -rf "${ROOT:?}/$ver" && mv "$tmp" "$ROOT/$ver"

# the index the application reads, and a page for people
python3 - "$ROOT" "$ver" "$REPO" <<'PY'
import hashlib, html, json, os, sys, time
root, ver, repo = sys.argv[1], sys.argv[2], sys.argv[3]
d = os.path.join(root, ver)
files = sorted(f for f in os.listdir(d) if not f.startswith("."))
jar = next(f for f in files if f.endswith(".jar"))
sha = hashlib.sha256(open(os.path.join(d, jar), "rb").read()).hexdigest()
index = {"format": 1, "name": "EVIA Paradigma Native", "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
         "packages": [{"id": "app", "name": "EVIA Paradigma Native", "group": "Application", "recommended": True,
                       "about": "The application itself. A newer version installs next to the one you have and starts "
                                "the next time; nothing needs to be uninstalled or installed again.",
                       "version": ver,
                       "files": {"any": {"version": ver, "file": ver + "/" + jar,
                                         "size": os.path.getsize(os.path.join(d, jar)), "sha256": sha, "unpacked": 0}}}]}
tmp = os.path.join(root, "index.json.tmp")
with open(tmp, "w") as fh:
    json.dump(index, fh, indent=1)
    fh.write("\n")
os.replace(tmp, os.path.join(root, "index.json"))
rows = "\n".join(f'<li><a href="{ver}/{html.escape(f)}">{html.escape(f)}</a> ({os.path.getsize(os.path.join(d, f)) / 1048576:.1f} MB)</li>'
                 for f in files if f != "LICENSE")
page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>EVIA Paradigma Native {ver}</title>
<style>body{{font-family:system-ui,sans-serif;max-width:760px;margin:40px auto;padding:0 16px;line-height:1.5}}
a{{color:#2563eb}} code{{background:#f1f1f1;padding:1px 4px}}</style></head><body>
<h1>EVIA Paradigma Native {ver}</h1>
<p>Visual block programming compiled straight to executables: no compiler, assembler or linker.
Source and release notes: <a href="https://github.com/{repo}">github.com/{repo}</a>.</p>
<ul>
{rows}
</ul>
<p>An installed copy updates itself from here: <i>Settings &gt; Updates</i>.</p>
</body></html>
"""
with open(os.path.join(root, "index.html"), "w") as fh:
    fh.write(page)
PY
# keep the two newest versions
ls -1 "$ROOT" | grep -E '^[0-9]' | sort -V | head -n -2 | while read -r old; do rm -rf "${ROOT:?}/$old"; done
say "evia.am/native now serves $ver"
