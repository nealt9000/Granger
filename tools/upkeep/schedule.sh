#!/bin/zsh
# Install, remove or show the weekly upkeep check as a launchd agent (Mondays 07:00; a Mac asleep then runs it on wake).
#   tools/upkeep/schedule.sh install|uninstall|status|run-now
# The agent runs weekly.sh from the main checkout, which exports origin/main and checks that.
set -eu
LABEL=org.opengranger.upkeep
PLIST=$HOME/Library/LaunchAgents/$LABEL.plist
REPO=${0:A:h:h:h}
# A worktree's script would vanish with the worktree, so point the agent at the main checkout.
MAIN=$(git -C "$REPO" worktree list --porcelain | awk 'NR==1 {print $2}')
SCRIPT=$MAIN/tools/upkeep/weekly.sh
DOMAIN=gui/$(id -u)

case ${1:-status} in
  install)
    [[ -x $SCRIPT ]] || { echo "$SCRIPT is missing or not executable (merge to main first)"; exit 1; }
    mkdir -p "$HOME/Library/LaunchAgents" "$HOME/granger-upkeep"
    cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/zsh</string><string>$SCRIPT</string></array>
  <key>StartCalendarInterval</key><dict><key>Weekday</key><integer>1</integer><key>Hour</key><integer>7</integer><key>Minute</key><integer>0</integer></dict>
  <key>StandardOutPath</key><string>$HOME/granger-upkeep/launchd.log</string>
  <key>StandardErrorPath</key><string>$HOME/granger-upkeep/launchd.log</string>
  <key>LowPriorityIO</key><true/>
  <key>Nice</key><integer>10</integer>
</dict>
</plist>
EOF
    plutil -lint "$PLIST" >/dev/null
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$PLIST"
    echo "installed $PLIST: Mondays 07:00, runs $SCRIPT"
    ;;
  uninstall)
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "removed $LABEL"
    ;;
  status)
    if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
      launchctl print "$DOMAIN/$LABEL" | grep -E "state|last exit code|program|runs" || true
    else
      echo "$LABEL is not installed"
    fi
    ;;
  run-now)
    launchctl kickstart "$DOMAIN/$LABEL" && echo "started; results in ~/granger-upkeep/$(date +%F)/"
    ;;
  *) echo "usage: $0 install|uninstall|status|run-now"; exit 2 ;;
esac
