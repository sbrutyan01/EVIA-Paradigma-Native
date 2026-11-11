#!/usr/bin/env bash
# Publishes a release: brings main up to date, tags it, and pushes the tag. Pushing the tag is all
# that is needed - the release workflow then builds the installers, creates the GitHub release with
# docs/release-notes/<tag>.md as its body, and the site rebuilds with that version's links.
#
#   scripts/publish-release.sh v0.6.7                    # GitHub
#   scripts/publish-release.sh v0.6.7 --gitlab           # GitHub, then the self-hosted GitLab
#   scripts/publish-release.sh v0.6.7 --gitlab --force-gitlab
#   scripts/publish-release.sh v0.6.7 --dry-run          # say what would happen, change nothing
#
# It finds the remotes by their URL instead of their names, deletes a tag called "main" if one is
# shadowing the branch, and refuses to tag a commit that is not what GitHub has. Nothing is forced
# unless you ask: --force-gitlab is only for a GitLab project created with its own initial commit,
# whose history is unrelated and which therefore rejects an ordinary push.
set -uo pipefail
cd "$(dirname "$0")/.."

TAG="" GITLAB=0 FORCE_GITLAB=0 DRY=0
for a in "$@"; do
    case "$a" in
        --gitlab) GITLAB=1 ;;
        --force-gitlab) GITLAB=1; FORCE_GITLAB=1 ;;
        --dry-run) DRY=1 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        v*) TAG="$a" ;;
        *) echo "unknown argument: $a" >&2; exit 2 ;;
    esac
done

die()  { echo "publish: $*" >&2; exit 1; }
say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
run()  { if [ "$DRY" = 1 ]; then echo "   would run: $*"; else "$@"; fi; }

[ -n "$TAG" ] || die "which tag? e.g. scripts/publish-release.sh v0.6.7"
NOTES="docs/release-notes/$TAG.md"
[ -f "$NOTES" ] || die "no $NOTES - the release body comes from that file"

# ---------------------------------------------------------------- the remotes, found by URL
remote_for() {    # a pattern, e.g. github.com
    git remote -v | awk -v p="$1" '$2 ~ p && $3 == "(push)" {print $1; exit}'
}
GH=$(remote_for 'github\.com')
GL=$(remote_for '192\.168\.|gitlab|git\.evia\.am')
[ -n "$GH" ] || die "no remote points at github.com - add one, or push by URL"
echo "github : $GH  ($(git remote get-url "$GH"))"
[ "$GITLAB" = 1 ] && { [ -n "$GL" ] || die "--gitlab asked for, but no remote points at a GitLab"; \
                       echo "gitlab : $GL  ($(git remote get-url "$GL"))"; }

# ---------------------------------------------------------------- a tag called "main" shadows the branch
if git rev-parse -q --verify refs/tags/main > /dev/null; then
    say "a tag named \"main\" is shadowing the branch"
    echo "   every command that says main has been guessing; removing the tag"
    run git tag -d main
fi

# ---------------------------------------------------------------- main, up to date with GitHub
say "main"
run git fetch -q "$GH" main
if [ "$DRY" = 0 ]; then
    git switch -q main || die "cannot switch to main"
    head=$(git rev-parse HEAD) theirs=$(git rev-parse "$GH/main")
    if [ "$head" != "$theirs" ]; then
        git merge --ff-only "$GH/main" || die "main and $GH/main have diverged: sort that out first,
    and do not merge FETCH_HEAD from the GitLab - its history is unrelated"
    fi
    echo "   at $(git rev-parse --short HEAD)  $(git log -1 --pretty=%s)"
fi

# ---------------------------------------------------------------- the tag
say "$TAG"
if git rev-parse -q --verify "refs/tags/$TAG" > /dev/null; then
    at=$(git rev-parse "refs/tags/$TAG^{commit}") head=$(git rev-parse HEAD)
    if [ "$at" != "$head" ]; then
        die "$TAG already exists on commit $(git rev-parse --short "$at"), not on
    $(git rev-parse --short "$head"). Delete it first with: git tag -d $TAG"
    fi
    echo "   exists already, on the right commit"
else
    run git -c user.name="Sergey Brutyan" -c user.email=sbrutyan01@gmail.com \
        tag -a "$TAG" -m "EVIA Paradigma ${TAG#v}"
    [ "$DRY" = 0 ] && echo "   created on $(git rev-parse --short "$TAG^{commit}")"
fi

# ---------------------------------------------------------------- push
say "pushing $TAG to $GH"
if ! run git push "$GH" "refs/tags/$TAG"; then
    echo
    echo "   The push failed, so nothing has been released." >&2
    echo "   \"Password authentication is not supported\" means git needs a token or a key:" >&2
    echo "     - a token: github.com/settings/tokens, scope repo, and give it as the PASSWORD" >&2
    echo "       (the username does not matter), or run  gh auth login  if gh is installed;" >&2
    echo "     - or SSH: add this machine's ~/.ssh/id_ed25519.pub at github.com/settings/keys" >&2
    echo "       and switch the remote with" >&2
    echo "         git remote set-url $GH git@github.com:sbrutyan01/EVIA-Paradigma-Native.git" >&2
    echo "   The tag is made and waiting; re-run this script once git can authenticate." >&2
    exit 1
fi
echo "   that is all GitHub needs: the release workflow builds the installers, creates the release"
echo "   from $NOTES, and the site rebuilds with this version's download links."

if [ "$GITLAB" = 1 ]; then
    say "pushing main and $TAG to $GL"
    refs=("refs/heads/main:refs/heads/main" "refs/tags/$TAG:refs/tags/$TAG")
    [ "$FORCE_GITLAB" = 1 ] && refs=("+refs/heads/main:refs/heads/main" "+refs/tags/$TAG:refs/tags/$TAG")
    if ! run git push "$GL" "${refs[@]}"; then
        echo
        echo "   If that was rejected as non-fast-forward, the GitLab project has its own initial"
        echo "   commit and an unrelated history. Check what would be replaced:"
        echo "       git log --oneline -3 $GL/main"
        echo "   and if it is only GitLab's placeholder, run again with --force-gitlab."
        echo "   If it asked for a password, that is your GitLab login (or a token as the password)."
        exit 1
    fi
fi

say "done"
echo "release : https://github.com/sbrutyan01/EVIA-Paradigma-Native/releases/tag/$TAG   (a few minutes)"
echo "gitlab  : https://git.evia.am/evia/evia-paradigma-native"
echo
echo "All of the releases and their installers can be copied to the GitLab afterwards with:"
echo "    GITLAB_TOKEN=... scripts/mirror-to-gitlab.sh"
