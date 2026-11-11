#!/usr/bin/env bash
# Copies everything from the GitHub repository into a self-hosted GitLab: the branches, the tags,
# and every release with its installers attached, so the GitLab is a complete copy that can serve
# the downloads on its own.
#
#   GITLAB_TOKEN=glpat-... scripts/mirror-to-gitlab.sh              # every release
#   GITLAB_TOKEN=glpat-... scripts/mirror-to-gitlab.sh v0.6.7       # only that one
#   GITLAB_TOKEN=glpat-... scripts/mirror-to-gitlab.sh --prune      # also delete GitLab-only refs
#
# Run it where both can be reached - the workstation itself. GitHub is read over its public API, so
# only the GitLab needs a token: User settings > Access tokens, scope api. The token is read from
# the environment, never written to a file or to .git/config, and masked out of the output.
#
#   GITLAB_URL      the GitLab, default https://git.evia.am
#   GITLAB_PROJECT  the project path, default evia/evia-paradigma-native
#   GITHUB_REPO     where to copy from, default sbrutyan01/EVIA-Paradigma-Native
#   KEEP            a folder to leave the downloaded installers in (default: a temporary one)
set -uo pipefail
cd "$(dirname "$0")/.."

URL="${GITLAB_URL:-https://git.evia.am}"
PROJECT="${GITLAB_PROJECT:-evia/evia-paradigma-native}"
GH_REPO="${GITHUB_REPO:-sbrutyan01/EVIA-Paradigma-Native}"
PRUNE=0
ONLY=""

for a in "$@"; do
    case "$a" in
        --prune) PRUNE=1 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        v*) ONLY="$a" ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done

die() { echo "mirror: $*" >&2; exit 1; }
say() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
[ -n "${GITLAB_TOKEN:-}" ] || die "set GITLAB_TOKEN (GitLab > User settings > Access tokens, scope: api)"
command -v curl > /dev/null || die "curl is needed"
command -v python3 > /dev/null || die "python3 is needed"

ENC=$(printf '%s' "$PROJECT" | sed 's|/|%2F|g')
API="$URL/api/v4/projects/$ENC"
auth=(--header "PRIVATE-TOKEN: $GITLAB_TOKEN")
mask() { sed "s|$GITLAB_TOKEN|***|g"; }

curl -fsS "${auth[@]}" "$API" > /dev/null || die "cannot reach $API - is the GitLab up and the token valid?"
echo "from : https://github.com/$GH_REPO"
echo "to   : $URL/$PROJECT"

WORK="${KEEP:-$(mktemp -d)}"
mkdir -p "$WORK"
[ -n "${KEEP:-}" ] || trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------- the refs
say "branches and tags"
git fetch -q --tags "https://github.com/$GH_REPO.git" '+refs/heads/*:refs/remotes/mirror/*' \
    || die "could not fetch from GitHub"
remote="${URL%%://*}://oauth2:$GITLAB_TOKEN@${URL#*://}/$PROJECT.git"
push=(git push "$remote" '+refs/remotes/mirror/*:refs/heads/*' '+refs/tags/*:refs/tags/*')
[ "$PRUNE" = 1 ] && push+=(--prune)
"${push[@]}" 2>&1 | mask || die "the push failed (see above)"

# ---------------------------------------------------------------- the releases, with their assets
say "releases"
gh_json="$WORK/releases.json"
curl -fsS "https://api.github.com/repos/$GH_REPO/releases?per_page=100" > "$gh_json" \
    || die "could not list the GitHub releases"

python3 - "$gh_json" "${ONLY:-}" <<'PY' > "$WORK/plan.tsv"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
only = sys.argv[2] if len(sys.argv) > 2 else ""
for r in data:
    if r.get("draft") or (only and r["tag_name"] != only):
        continue
    for a in r.get("assets", []):
        print("\t".join([r["tag_name"], a["name"], a["browser_download_url"]]))
    if not r.get("assets"):
        print("\t".join([r["tag_name"], "", ""]))
PY
[ -s "$WORK/plan.tsv" ] || die "no releases to copy${ONLY:+ for $ONLY}"

tags=$(cut -f1 "$WORK/plan.tsv" | sort -u -V)
for tag in $tags; do
    notes="docs/release-notes/$tag.md"
    body="$WORK/body-$tag.json"
    python3 - "$tag" "$notes" "$gh_json" <<'PY' > "$body"
import json, os, sys
tag, notes, ghj = sys.argv[1], sys.argv[2], sys.argv[3]
text = ""
if os.path.exists(notes):
    text = open(notes, encoding="utf-8").read()
else:                                   # fall back to the GitHub release's own body
    for r in json.load(open(ghj, encoding="utf-8")):
        if r["tag_name"] == tag:
            text = r.get("body") or ""
            break
print(json.dumps({"tag_name": tag, "name": "EVIA Paradigma " + tag.lstrip("v"), "description": text}))
PY
    if curl -fsS "${auth[@]}" "$API/releases/$tag" > /dev/null 2>&1; then
        curl -fsS -X PUT "${auth[@]}" -H "Content-Type: application/json" --data @"$body" \
             "$API/releases/$tag" > /dev/null && echo "  updated $tag" || echo "  ! $tag not updated"
    else
        curl -fsS -X POST "${auth[@]}" -H "Content-Type: application/json" --data @"$body" \
             "$API/releases" > /dev/null && echo "  created $tag" || echo "  ! $tag not created"
    fi

    # the installers: fetched from GitHub, uploaded to the GitLab, then linked on the release
    while IFS=$'\t' read -r t name dl; do
        [ "$t" = "$tag" ] && [ -n "$name" ] || continue
        f="$WORK/$name"
        [ -s "$f" ] || curl -fsSL -o "$f" "$dl" || { echo "    ! $name did not download"; continue; }
        up=$(curl -fsS "${auth[@]}" --form "file=@$f" "$API/uploads") \
            || { echo "    ! $name did not upload"; continue; }
        path=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("full_path") or d.get("url",""))' <<< "$up")
        [ -n "$path" ] || { echo "    ! $name: no path came back"; continue; }
        curl -fsS -X POST "${auth[@]}" \
             --data-urlencode "name=$name" --data-urlencode "url=$URL$path" \
             "$API/releases/$tag/assets/links" > /dev/null 2>&1 \
            && echo "    + $name ($(wc -c < "$f") bytes)" \
            || echo "    = $name already linked"
    done < "$WORK/plan.tsv"
done

say "done"
echo "releases : $URL/$PROJECT/-/releases"
[ -n "${KEEP:-}" ] && echo "installers left in $KEEP"
echo
echo "To have the website offer these instead of GitHub's, build it with DOWNLOAD_BASE set to"
echo "wherever they are served from, for example:"
echo "  DOWNLOAD_BASE=$URL/$PROJECT/-/releases/<tag>/downloads  python3 scripts/build-site.py"
echo "A private address only works for visitors on this network; a published site needs a public one."
