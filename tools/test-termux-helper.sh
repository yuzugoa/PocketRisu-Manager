#!/usr/bin/env sh
set -eu

installer="${1:-app/src/main/assets/termux-helper/install.sh}"
[ -r "$installer" ] || { echo "installer not found: $installer" >&2; exit 2; }

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT INT TERM
helper="$sandbox/.risu-manager/bin/risu-manager"

HOME="$sandbox" sh "$installer" >/dev/null
HOME="$sandbox" sh "$helper" version | grep -F '"helperVersion":19' >/dev/null

HOME="$sandbox" sh "$helper" config-get test-instance | grep -F '"port":6001' >/dev/null
HOME="$sandbox" sh "$helper" config-set test-instance 6123 LOCAL_NETWORK HTTP 512 30 60 100 51200 | grep -F '"port":6123' >/dev/null
round_trip="$(HOME="$sandbox" sh "$helper" config-get test-instance)"
printf '%s' "$round_trip" | grep -F '"bindScope":"LOCAL_NETWORK"' >/dev/null
printf '%s' "$round_trip" | grep -F '"bodyLimitMb":512' >/dev/null
printf '%s' "$round_trip" | grep -F '"saveIntervalSeconds":30' >/dev/null
printf '%s' "$round_trip" | grep -F '"snapshotRetentionCount":100' >/dev/null
HOME="$sandbox" sh "$helper" config-validate test-instance | grep -F '"operation":"config-validate"' >/dev/null

if HOME="$sandbox" sh "$helper" config-get ../../escape >"$sandbox/path.out" 2>&1; then
    echo 'path traversal was accepted' >&2
    exit 1
fi
grep -F '"code":"INVALID_INSTANCE_ID"' "$sandbox/path.out" >/dev/null

if HOME="$sandbox" sh "$helper" config-set test-instance 6123 LOOPBACK HTTP 0 5 5 20 500 >"$sandbox/number.out" 2>&1; then
    echo 'invalid numeric setting was accepted' >&2
    exit 1
fi
grep -F '"code":"INVALID_CONFIG"' "$sandbox/number.out" >/dev/null

mode="$(stat -c '%a' "$sandbox/.risu-manager/instances/test-instance/manager/server.conf")"
[ "$mode" = "600" ] || { echo "unexpected config mode: $mode" >&2; exit 1; }

test_bin="$sandbox/test-bin"
wake_events="$sandbox/wake-events"
mkdir -p "$test_bin"
cat > "$test_bin/termux-wake-lock" <<'EOF'
#!/usr/bin/env sh
printf 'lock\n' >> "$WAKE_EVENTS"
EOF
cat > "$test_bin/termux-wake-unlock" <<'EOF'
#!/usr/bin/env sh
printf 'unlock\n' >> "$WAKE_EVENTS"
EOF
cat > "$test_bin/node" <<'EOF'
#!/usr/bin/env sh
[ "${1:-}" = "-e" ] && exit 0
exit 1
EOF
chmod +x "$test_bin/termux-wake-lock" "$test_bin/termux-wake-unlock" "$test_bin/node"

failed_instance="$sandbox/.risu-manager/instances/start-fails"
mkdir -p "$failed_instance/repo/server/node" "$failed_instance/run"
printf '%s\n' 'process.exit(1)' > "$failed_instance/repo/server/node/server.cjs"
printf '%s\n' '{"instanceId":"start-fails","variantId":"pocketrisu","commit":"0000000000000000000000000000000000000000"}' > "$failed_instance/metadata.json"
HOME="$sandbox" sh "$helper" config-set start-fails 6124 LOOPBACK HTTP 100 5 5 20 500 >/dev/null
if HOME="$sandbox" PATH="$test_bin:$PATH" WAKE_EVENTS="$wake_events" sh "$helper" start start-fails >"$sandbox/start.out" 2>&1; then
    echo 'early server exit was accepted' >&2
    exit 1
fi
grep -F '"code":"SERVER_START_FAILED"' "$sandbox/start.out" >/dev/null
[ "$(grep -c '^lock$' "$wake_events")" = "1" ] || { echo 'start failure did not acquire the wake lock once' >&2; exit 1; }
[ "$(grep -c '^unlock$' "$wake_events")" = "1" ] || { echo 'start failure did not release the wake lock once' >&2; exit 1; }

: > "$wake_events"
HOME="$sandbox" PATH="$test_bin:$PATH" WAKE_EVENTS="$wake_events" sh "$helper" stop start-fails | grep -F '"alreadyStopped":true' >/dev/null
[ "$(grep -c '^unlock$' "$wake_events")" = "1" ] || { echo 'already-stopped cleanup did not release an idle wake lock' >&2; exit 1; }

node_test="$(dirname "$0")/test-termux-helper-exporter-sql.cjs"
if command -v node >/dev/null 2>&1; then node "$node_test" "$installer"; fi

recovery_test="$(dirname "$0")/test-termux-install-recovery.sh"
[ -r "$recovery_test" ] && sh "$recovery_test" "$installer"

echo 'Termux helper config contract: PASS'
