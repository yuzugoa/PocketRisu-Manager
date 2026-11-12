#!/usr/bin/env sh
set -eu

installer="${1:-app/src/main/assets/termux-helper/install.sh}"
test_root="$(mktemp -d)"
helper="$test_root/.risu-manager/bin/risu-manager"
instance="$test_root/.risu-manager/instances/watched"
run="$instance/run"
lock="$test_root/.risu-manager/locks/instance-watched"

cleanup() {
    HOME="$test_root" sh "$helper" stop watched >/dev/null 2>&1 || true
    if [ -f "$run/server.pid" ]; then kill "$(cat "$run/server.pid")" 2>/dev/null || true; fi
    if [ -f "$run/watcher.pid" ]; then kill "$(cat "$run/watcher.pid")" 2>/dev/null || true; fi
    rm -rf "$test_root"
}
trap cleanup EXIT INT TERM

HOME="$test_root" sh "$installer" >/dev/null
mkdir -p "$instance/repo/server/node" "$run" "$test_root/test-bin"
printf '%s\n' '{"instanceId":"watched","variantId":"pocketrisu","commit":"0000000000000000000000000000000000000000"}' > "$instance/metadata.json"
cat > "$instance/repo/server/node/server.cjs" <<'EOF'
// The fake node executable below keeps this process alive for lifecycle checks.
EOF
cat > "$test_root/test-bin/node" <<'EOF'
#!/usr/bin/env sh
case "${1:-}" in
    -e|-) exit 0 ;;
    server/node/server.cjs) while :; do sleep 1; done ;;
    *) exit 1 ;;
esac
EOF
cat > "$test_root/test-bin/termux-wake-lock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
cat > "$test_root/test-bin/termux-wake-unlock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
chmod +x "$test_root/test-bin/node" "$test_root/test-bin/termux-wake-lock" "$test_root/test-bin/termux-wake-unlock"
PATH="$test_root/test-bin:$PATH"
export PATH
HOME="$test_root" sh "$helper" config-set watched 6124 LOOPBACK HTTP 100 5 5 20 500 >/dev/null
HOME="$test_root" sh "$helper" start watched > "$test_root/start.out"
grep -F '"state":"RUNNING"' "$test_root/start.out" >/dev/null
[ -f "$run/keep-running" ] && [ -f "$run/watcher.pid" ] || { echo 'watcher was not started' >&2; exit 1; }

# An operation lock must prevent recovery while a backup or update owns the instance.
first_pid="$(cat "$run/server.pid")"
mkdir "$lock"
printf '%s\n' maintenance > "$lock/owner"
printf '%s\n' "$$" > "$lock/pid"
kill -KILL "$first_pid"
sleep 3
[ "$(cat "$run/server.pid")" = "$first_pid" ] || { echo 'watcher restarted during maintenance' >&2; exit 1; }
rm -f "$lock/owner" "$lock/pid"
rmdir "$lock"

recovered=0
attempt=0
while [ "$attempt" -lt 15 ]; do
    current_pid="$(cat "$run/server.pid")"
    if [ "$current_pid" != "$first_pid" ] && kill -0 "$current_pid" 2>/dev/null; then
        recovered=1
        break
    fi
    attempt=$((attempt + 1))
    sleep 1
done
[ "$recovered" -eq 1 ] || { echo 'watcher did not recover the killed server' >&2; exit 1; }
HOME="$test_root" sh "$helper" status watched | grep -F '"state":"RUNNING"' >/dev/null

HOME="$test_root" sh "$helper" stop watched | grep -F '"state":"STOPPED"' >/dev/null
[ ! -e "$run/keep-running" ] || { echo 'Stop left the watcher enabled' >&2; exit 1; }
sleep 3
if HOME="$test_root" sh "$helper" status watched | grep -F '"state":"RUNNING"' >/dev/null; then
    echo 'Stop was undone by the watcher' >&2
    exit 1
fi
echo 'Termux server watcher: PASS'
