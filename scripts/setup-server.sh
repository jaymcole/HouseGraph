#!/usr/bin/env bash
#
# Automates as much of docs/remote-server-setup.md as can be automated on a
# Mac (Parts 3-9). It still stops to ask you to do the two things only a human
# can do: paste a deploy key into GitHub's UI, and flip switches in System
# Settings. Safe to re-run — every step checks whether it already did its job.
#
# Usage:
#   extras/setup-server.sh --graphs-repo git@github.com:you/my-graphs.git
#
# See extras/setup-server.sh --help for every flag, or just run it with no
# flags and answer the prompts.

set -euo pipefail

# --- Defaults (matches docs/remote-server-setup.md) --------------------------

SOURCE_DIR="$HOME/HouseGraph-source"
INSTALL_DIR="$HOME/HouseGraph"
SOURCE_REPO_URL="https://github.com/jaymcole/HouseGraph.git"
GRAPHS_REPO_URL=""
GRAPHS_BRANCH="main"
POLL_SECONDS=60
SSH_KEY="$HOME/.ssh/housegraph_deploy"
NON_INTERACTIVE=0
SKIP_DEPLOY_KEY=0
SKIP_LAUNCHD=0
SKIP_PMSET=0
SKIP_PLUGINS=0
PLUGIN_URLS=()

# --- Helpers -------------------------------------------------------------

bold()   { printf '\033[1m%s\033[0m\n' "$*"; }
step()   { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
info()   { printf '    %s\n' "$*"; }
warn()   { printf '\033[1;33m    warning: %s\033[0m\n' "$*"; }
fail()   { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

confirm() {
    # confirm "question" -> 0 (yes) or 1 (no). Defaults to yes in non-interactive mode.
    if [[ "$NON_INTERACTIVE" == 1 ]]; then
        return 0
    fi
    local reply
    read -r -p "    $* [Y/n] " reply || true
    [[ -z "$reply" || "$reply" =~ ^[Yy] ]]
}

pause_for_human() {
    if [[ "$NON_INTERACTIVE" == 1 ]]; then
        warn "non-interactive mode: skipping manual step — $*"
        return
    fi
    read -r -p "    Press Enter once you've done that... " _
}

usage() {
    cat <<EOF
$(bold "extras/setup-server.sh") — automate docs/remote-server-setup.md

Options:
  --graphs-repo URL        Git URL of your graphs repository (required)
  --branch NAME            Branch to follow (default: $GRAPHS_BRANCH)
  --source-dir PATH        Where to clone HouseGraph's own source (default: $SOURCE_DIR)
  --install-dir PATH       Where to install housegraph.jar (default: $INSTALL_DIR)
  --poll-seconds N         How often the daemon polls for changes (default: $POLL_SECONDS)
  --install-plugin URL     A node library to install (repeatable)
  --skip-deploy-key        Don't generate/configure an SSH deploy key
  --skip-launchd           Don't install the LaunchAgent (Part 8)
  --skip-pmset             Don't touch macOS sleep settings (Part 9)
  --skip-plugins           Don't prompt about node libraries (Part 6)
  --non-interactive        Never prompt; accept defaults and skip manual pauses
  -h, --help                Show this help

Example:
  extras/setup-server.sh --graphs-repo git@github.com:you/my-graphs.git
EOF
}

# --- Parse args ------------------------------------------------------------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --graphs-repo) GRAPHS_REPO_URL="$2"; shift 2 ;;
        --branch) GRAPHS_BRANCH="$2"; shift 2 ;;
        --source-dir) SOURCE_DIR="$2"; shift 2 ;;
        --install-dir) INSTALL_DIR="$2"; shift 2 ;;
        --poll-seconds) POLL_SECONDS="$2"; shift 2 ;;
        --install-plugin) PLUGIN_URLS+=("$2"); shift 2 ;;
        --skip-deploy-key) SKIP_DEPLOY_KEY=1; shift ;;
        --skip-launchd) SKIP_LAUNCHD=1; shift ;;
        --skip-pmset) SKIP_PMSET=1; shift ;;
        --skip-plugins) SKIP_PLUGINS=1; shift ;;
        --non-interactive) NON_INTERACTIVE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown option: $1 (see --help)" ;;
    esac
done

if [[ -z "$GRAPHS_REPO_URL" ]]; then
    if [[ "$NON_INTERACTIVE" == 1 ]]; then
        fail "--graphs-repo is required in non-interactive mode"
    fi
    read -r -p "Git URL of your graphs repository (e.g. git@github.com:you/my-graphs.git): " GRAPHS_REPO_URL
    [[ -n "$GRAPHS_REPO_URL" ]] || fail "A graphs repository URL is required."
fi

REPO_DIR_NAME=$(basename "$GRAPHS_REPO_URL" .git)

bold "HouseGraph server setup"
info "Source repo:   $SOURCE_REPO_URL -> $SOURCE_DIR"
info "Install dir:    $INSTALL_DIR"
info "Graphs repo:    $GRAPHS_REPO_URL ($GRAPHS_BRANCH)"
info "Poll interval:  ${POLL_SECONDS}s"

# --- Part "Before you start": prerequisites ---------------------------------

step "Checking prerequisites"

if ! command -v java >/dev/null 2>&1; then
    fail "java not found. Install JDK 21+ from https://adoptium.net/ or: brew install --cask temurin@21"
fi
JAVA_VERSION=$(java -version 2>&1 | head -n1 | grep -oE '"[0-9]+' | tr -d '"' || true)
if [[ -z "$JAVA_VERSION" || "$JAVA_VERSION" -lt 21 ]]; then
    fail "java 21+ required, found: $(java -version 2>&1 | head -n1)"
fi
info "java $JAVA_VERSION found"

if ! command -v git >/dev/null 2>&1; then
    fail "git not found. On macOS: xcode-select --install"
fi
info "git found: $(git --version)"

OS_NAME="$(uname -s)"
if [[ "$OS_NAME" != "Darwin" ]]; then
    warn "This script automates the macOS-specific parts of the guide (LaunchAgent, pmset)."
    warn "On $OS_NAME, pass --skip-launchd --skip-pmset and finish those parts by hand."
fi

# --- Part 3: build it on the server ------------------------------------------

step "Part 3: building HouseGraph on this machine"

if [[ -d "$SOURCE_DIR/.git" ]]; then
    info "Source already cloned at $SOURCE_DIR; pulling latest"
    git -C "$SOURCE_DIR" pull --ff-only
else
    git clone "$SOURCE_REPO_URL" "$SOURCE_DIR"
fi

info "Running ./gradlew :app:shadowJar (this is most of the ~30 minutes)"
(cd "$SOURCE_DIR" && ./gradlew :app:shadowJar)

mkdir -p "$INSTALL_DIR"
JAR_SRC=$(ls "$SOURCE_DIR"/app/build/libs/app-*.jar 2>/dev/null | head -n1) || true
[[ -n "$JAR_SRC" ]] || fail "Couldn't find a built jar under $SOURCE_DIR/app/build/libs/"
cp "$JAR_SRC" "$INSTALL_DIR/housegraph.jar"
info "Installed $(basename "$JAR_SRC") -> $INSTALL_DIR/housegraph.jar"

HOUSEGRAPH_JAR="$INSTALL_DIR/housegraph.jar"
housegraph() { java -jar "$HOUSEGRAPH_JAR" "$@"; }

housegraph --version || fail "housegraph.jar does not run. See docs/remote-server-setup.md's native-library note."

# Shell alias, for the human's own use afterwards — not needed by this script.
SHELL_RC="$HOME/.zshrc"
[[ "${SHELL:-}" == */bash ]] && SHELL_RC="$HOME/.bashrc"
ALIAS_LINE="alias housegraph='java -jar $HOUSEGRAPH_JAR'"
if [[ -f "$SHELL_RC" ]] && grep -qF "$ALIAS_LINE" "$SHELL_RC" 2>/dev/null; then
    info "Alias already present in $SHELL_RC"
else
    echo "$ALIAS_LINE" >> "$SHELL_RC"
    info "Added 'housegraph' alias to $SHELL_RC (source it, or open a new shell)"
fi

# --- Part 4: deploy key ------------------------------------------------------

if [[ "$SKIP_DEPLOY_KEY" == 1 ]]; then
    step "Part 4: deploy key skipped (--skip-deploy-key)"
elif [[ "$GRAPHS_REPO_URL" == https://* ]]; then
    step "Part 4: HTTPS graphs repo URL — skipping SSH deploy key"
    info "Store a read-only 'repo'-scope token in HouseGraph's Secrets... editor,"
    info "and reference its key as repositories[].tokenSecret in remote.json (Part 5)."
else
    step "Part 4: SSH deploy key"
    if [[ -f "$SSH_KEY" ]]; then
        info "Key already exists at $SSH_KEY"
    else
        ssh-keygen -t ed25519 -N "" -C "housegraph-server" -f "$SSH_KEY"
    fi

    SSH_CONFIG="$HOME/.ssh/config"
    mkdir -p "$HOME/.ssh"
    if [[ -f "$SSH_CONFIG" ]] && grep -q "IdentityFile $SSH_KEY" "$SSH_CONFIG" 2>/dev/null; then
        info "~/.ssh/config already points at this key"
    else
        {
            echo ""
            echo "Host github.com"
            echo "  IdentityFile $SSH_KEY"
            echo "  IdentitiesOnly yes"
        } >> "$SSH_CONFIG"
        info "Added a github.com entry to $SSH_CONFIG"
    fi

    echo ""
    bold "    Add this deploy key to your graphs repository:"
    info "GitHub -> $GRAPHS_REPO_URL -> Settings -> Deploy keys -> Add deploy key"
    info "Leave \"Allow write access\" UNCHECKED — the server only ever reads."
    echo ""
    cat "$SSH_KEY.pub"
    echo ""
    pause_for_human "add the key above as a read-only deploy key on GitHub"

    info "Checking access with git ls-remote..."
    if git ls-remote "$GRAPHS_REPO_URL" >/dev/null 2>&1; then
        info "Access confirmed."
    else
        warn "git ls-remote failed. Fix access before continuing (see Part 4 in the guide)."
        confirm "Continue anyway?" || exit 1
    fi
fi

# --- Part 5: configure the server -------------------------------------------

step "Part 5: writing remote.json"

# doctor exits non-zero at this point (no remote.json yet) — that's expected, we only want the path.
CONFIG_DIR=$(housegraph doctor 2>/dev/null | awk -F': *' '/^Data directory:/ {print $2}') || true
if [[ -z "$CONFIG_DIR" ]]; then
    # Fall back to the documented per-OS default rather than failing outright.
    CONFIG_DIR="$HOME/Library/Application Support/HouseGraph"
    warn "Couldn't parse the data directory from 'housegraph doctor'; assuming $CONFIG_DIR"
fi
mkdir -p "$CONFIG_DIR/config"
REMOTE_JSON="$CONFIG_DIR/config/remote.json"

WRITE_CONFIG=1
if [[ -f "$REMOTE_JSON" ]]; then
    warn "$REMOTE_JSON already exists."
    confirm "Overwrite it?" || WRITE_CONFIG=0
fi

if [[ "$WRITE_CONFIG" == 1 ]]; then
    cat > "$REMOTE_JSON" <<EOF
{
  "pollSeconds": $POLL_SECONDS,
  "repositories": [
    { "url": "$GRAPHS_REPO_URL", "branch": "$GRAPHS_BRANCH" }
  ],
  "allowPluginInstall": false,
  "trustedPluginRepositories": []
}
EOF
    info "Wrote $REMOTE_JSON"
else
    info "Left existing $REMOTE_JSON untouched"
fi

# --- Part 6: node libraries ---------------------------------------------------

if [[ "$SKIP_PLUGINS" == 1 ]]; then
    step "Part 6: node libraries skipped (--skip-plugins)"
else
    step "Part 6: node libraries"
    if [[ ${#PLUGIN_URLS[@]} -eq 0 && "$NON_INTERACTIVE" == 0 ]]; then
        info "If your graphs use anything beyond built-in nodes (cameras, Discord, the web"
        info "server), install those node libraries now. Leave blank to skip."
        while true; do
            read -r -p "    Node library repository URL (blank to finish): " url
            [[ -z "$url" ]] && break
            PLUGIN_URLS+=("$url")
        done
    fi
    for url in "${PLUGIN_URLS[@]:-}"; do
        [[ -z "$url" ]] && continue
        info "Installing $url"
        housegraph plugins install "$url" || warn "Failed to install $url — install it manually later."
    done
    housegraph plugins list
fi

# --- Part 7: test it before automating it -------------------------------------

step "Part 7: doctor / sync / daemon --once"

housegraph doctor || warn "doctor reported problems above — resolve them before relying on the daemon."

info "Running housegraph sync..."
housegraph sync

if confirm "Run 'housegraph daemon --once' to start everything now?"; then
    housegraph daemon --once
fi

# --- Part 8: LaunchAgent -------------------------------------------------------

if [[ "$SKIP_LAUNCHD" == 1 || "$OS_NAME" != "Darwin" ]]; then
    step "Part 8: LaunchAgent skipped"
else
    step "Part 8: installing the LaunchAgent"

    JAVA_BIN=$(command -v java)
    PLIST_SRC="$SOURCE_DIR/extras/launchd/com.jaymcole.housegraph.plist"
    PLIST_DEST="$HOME/Library/LaunchAgents/com.jaymcole.housegraph.plist"
    LOG_PATH="$HOME/Library/Logs/housegraph-daemon.log"
    mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"

    if [[ ! -f "$PLIST_SRC" ]]; then
        fail "Template not found at $PLIST_SRC"
    fi

    sed \
        -e "s#/usr/bin/java#$JAVA_BIN#" \
        -e "s#/Users/CHANGEME/HouseGraph/app-0.2.0.jar#$HOUSEGRAPH_JAR#" \
        -e "s#/Users/CHANGEME/Library/Logs/housegraph-daemon.log#$LOG_PATH#g" \
        "$PLIST_SRC" > "$PLIST_DEST"
    info "Wrote $PLIST_DEST"

    if launchctl list | grep -q com.jaymcole.housegraph; then
        info "Already loaded; reloading"
        launchctl unload "$PLIST_DEST" 2>/dev/null || true
    fi
    if ! launchctl bootstrap "gui/$(id -u)" "$PLIST_DEST" 2>/dev/null; then
        launchctl load "$PLIST_DEST"
    fi

    sleep 1
    if launchctl list | grep -q com.jaymcole.housegraph; then
        info "LaunchAgent is loaded and running."
    else
        warn "LaunchAgent didn't show up in 'launchctl list' — check $LOG_PATH"
    fi
fi

# --- Part 9: make the Mac behave like a server ---------------------------------

if [[ "$SKIP_PMSET" == 1 || "$OS_NAME" != "Darwin" ]]; then
    step "Part 9: sleep settings skipped"
else
    step "Part 9: disabling sleep"
    if confirm "Run 'sudo pmset -a sleep 0 disablesleep 1' now? (will prompt for your password)"; then
        sudo pmset -a sleep 0 disablesleep 1
        info "Sleep disabled."
    else
        info "Skipped — run it yourself later: sudo pmset -a sleep 0 disablesleep 1"
    fi
fi

echo ""
bold "Manual steps this script can't do for you (System Settings):"
info "- Users & Groups -> Automatic login -> your user"
info "- General -> Sharing -> Screen Sharing -> on"
info "- Energy -> Start up automatically after a power failure -> on"
echo ""
bold "Done. Day to day, deploying is just: git commit && git push to $REPO_DIR_NAME."
info "Logs: tail -f \"$CONFIG_DIR/logs/housegraph.log\""
