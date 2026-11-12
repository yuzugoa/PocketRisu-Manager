#!/usr/bin/env sh
set -eu

installer="${1:-app/src/main/assets/termux-helper/install.sh}"
[ -r "$installer" ] || { echo "installer not found: $installer" >&2; exit 2; }

sha=278251f85a19bfdfd4cf3faae780e62682878f9e
sandbox="$(mktemp -d)"
helper="$sandbox/.risu-manager/bin/risu-manager"
fake="$sandbox/fake-bin"

cleanup() {
    for pid_file in "$sandbox"/*.fixture-pid; do
        [ -r "$pid_file" ] || continue
        fixture_pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
        case "$fixture_pid" in ''|*[!0-9]*) ;; *) kill -TERM "$fixture_pid" 2>/dev/null || true ;; esac
    done
    rm -rf "$sandbox"
}
trap cleanup EXIT INT TERM

fail() { printf 'Termux clone contract: FAIL: %s\n' "$1" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }
assert_dir() { [ -d "$1" ] || fail "missing directory: $1"; }
assert_absent() { [ ! -e "$1" ] && [ ! -L "$1" ] || fail "unexpected path: $1"; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "$(basename "$1") lacks: $2"; }
assert_not_contains() { ! grep -F -- "$2" "$1" >/dev/null || fail "$(basename "$1") unexpectedly contains: $2"; }

mkdir -p "$fake"

cat > "$fake/node" <<'EOF'
#!/usr/bin/env sh
set -eu
if [ "${1:-}" = - ]; then
    [ "${CURL_HEALTH_FAIL:-0}" != 1 ]
    exit $?
fi
if [ "${1:-}" = -e ]; then
    script="$2"; shift 2
    case "$script" in
        *'createHash("sha256")'*)
            root="$1"
            (
                cd "$root"
                find . -printf '%P|%y|%s|%l\n' | LC_ALL=C sort
                for file in $(find . -type f -print | LC_ALL=C sort); do sha256sum "$file"; done
            ) | sha256sum | awk '{print $1}'
            ;;
        *'shared inode:'*)
            source_root="$1"; target_root="$2"
            for source_file in $(find "$source_root" -type f -print); do
                relative="${source_file#"$source_root"/}"
                target_file="$target_root/$relative"
                [ -f "$target_file" ] || exit 1
                source_inode="$(stat -c '%d:%i' "$source_file")"
                target_inode="$(stat -c '%d:%i' "$target_file")"
                [ "$source_inode" != "$target_inode" ] || exit 1
            done
            ;;
        *'broken or escaping symlink:'*)
            source_root="$(realpath "$1")"; target_root="$(realpath "$2")"; final_root="$3"
            for link in $(find "$target_root" -type l -print); do
                resolved="$(realpath "$link" 2>/dev/null || true)"
                case "$resolved" in
                    "$target_root"|"$target_root"/*) continue ;;
                    "$source_root"|"$source_root"/*)
                        relative="$(realpath --relative-to="$source_root" "$resolved")"
                        mapped="$target_root/$relative"
                        [ -e "$mapped" ] || exit 1
                        replacement="$(realpath --relative-to="$(dirname "$link")" "$mapped")"
                        rm -f "$link"
                        ln -s "$replacement" "$link"
                        ;;
                    "$final_root"|"$final_root"/*) exit 1 ;;
                    *) exit 1 ;;
                esac
            done
            ;;
        *'CREATE TABLE IF NOT EXISTS kv'*)
            mkdir -p "$1/save"
            printf '%s\n' 'initialized-sqlite-fixture' > "$1/save/risuai.db"
            ;;
        *'quick_check'*)
            case "$1" in *clone-valid-fail*) exit 1 ;; esac
            [ "${NODE_VALIDATE_FAIL:-0}" != 1 ] && [ -f "$1/save/risuai.db" ]
            ;;
        *'config/server-backup-path'*)
            [ "${NODE_RESET_FAIL:-0}" != 1 ] || exit 1
            printf '%s\n' "$2" > "$1/save/.fixture-backup-path"
            ;;
        *'net.createServer'*)
            [ "${FAKE_BUSY_PORT:-}" != "$1" ]
            ;;
        *'randomUUID'*) printf '%s' '11111111-1111-4111-8111-111111111111' ;;
        *) exit 0 ;;
    esac
    exit 0
fi

trap 'exit 0' HUP INT TERM
while :; do sleep 1; done
EOF

cat > "$fake/git" <<EOF
#!/usr/bin/env sh
if [ "\${1:-}" = -C ] && [ "\${3:-}" = rev-parse ]; then printf '%s\n' '$sha'; exit 0; fi
if [ "\${1:-}" = --version ]; then printf '%s\n' 'git version fixture'; exit 0; fi
exit 1
EOF

cat > "$fake/curl" <<'EOF'
#!/usr/bin/env sh
[ "${CURL_HEALTH_FAIL:-0}" != 1 ]
EOF

cat > "$fake/termux-wake-lock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
cat > "$fake/termux-wake-unlock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF

cat > "$fake/cp" <<'EOF'
#!/usr/bin/env sh
if [ "${CLONE_CP_FAIL:-0}" = 1 ] && [ "${1:-}" = -a ]; then exit 1; fi
exec /bin/cp "$@"
EOF

cat > "$fake/df" <<'EOF'
#!/usr/bin/env sh
if [ "${CLONE_LOW_DISK:-0}" = 1 ]; then
    printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\nfixture 10 9 1 90%% /\n'
else
    /bin/df "$@"
fi
EOF

cat > "$fake/nohup" <<'EOF'
#!/usr/bin/env sh
if [ "${NOHUP_SUPPRESS:-0}" = 1 ]; then exit 0; fi
exec /usr/bin/nohup "$@"
EOF
chmod +x "$fake"/*

HOME="$sandbox" sh "$installer" >/dev/null

make_source() {
    source_id="$1"
    source="$sandbox/.risu-manager/instances/$source_id"
    repo="$source/repo"
    mkdir -p "$repo/server/node/ssl/certificate" "$repo/dist" "$repo/save/assets" \
        "$repo/save/model-jobs" "$repo/backups" "$repo/node_modules/better-sqlite3" \
        "$source/run" "$source/manager/acme" "$source/manager/tls"
    cat > "$repo/server/node/server.cjs" <<'EOF'
const passwordPath = path.join(process.cwd(), 'save', '__password')
readFileSync(passwordPath, 'utf-8')
const jwtSecretPath = path.join(savePath, '__jwt_secret')
writeFileSync(jwtSecretPath, jwtSecret, 'utf-8')
nodeCrypto.createHmac('sha256', jwtSecret)
const instanceIdPath = path.join(savePath, '__instance_id')
nodeCrypto.randomUUID()
writeFileSync(instanceIdPath, instanceId, 'utf-8')
const SESSION_FILE = path.join(process.cwd(), 'save', '__sessions')
readFileSync(SESSION_FILE, 'utf-8')
writeFileSync(SESSION_FILE, sessions)
const authCodePath = path.join(process.cwd(), 'save', '__authcode')
EOF
    printf '%s\n' '<html>fixture</html>' > "$repo/dist/index.html"
    printf '%s\n' '{"version":"1.11.2"}' > "$repo/package.json"
    printf '%s\n' 'sqlite-fixture-content' > "$repo/save/risuai.db"
    printf '%s\n' 'source-jwt-secret' > "$repo/save/__jwt_secret"
    printf '%s\n' 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' > "$repo/save/__instance_id"
    printf '%s\n' 'persisted-login-password' > "$repo/save/__password"
    printf '%s\n' 'session-token' > "$repo/save/__sessions"
    printf '%s\n' 'one-time-auth-code' > "$repo/save/__authcode"
    printf '%s\n' 'content-asset' > "$repo/save/assets/user.txt"
    printf '%s\n' 'pending-model-job' > "$repo/save/model-jobs/job.json"
    printf '%s\n' 'pending-model-job-db' > "$repo/save/model-jobs.db"
    printf '%s\n' 'temporary-backup' > "$repo/backups/in-progress.bin"
    printf '%s\n' 'private-key' > "$repo/server/node/ssl/certificate/server.key"
    printf '%s\n' 'certificate' > "$repo/server/node/ssl/certificate/server.crt"
    printf '%s\n' 'runtime-log' > "$source/run/server.log"
    printf '%s\n' 'renewal-state' > "$source/manager/acme/account.conf"
    printf '%s\n' 'tls-state' > "$source/manager/tls.conf"
    ln -s assets/user.txt "$repo/save/internal-link"
    printf '{"instanceId":"%s","variantId":"pocketrisu","commit":"%s","version":"1.11.2","workDir":"repo","entrypoint":"server/node/server.cjs"}\n' "$source_id" "$sha" > "$source/metadata.json"
    HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" config-set "$source_id" 6101 LOCAL_NETWORK HTTP 256 10 15 30 1024 >/dev/null
}

run_clone() {
    operation_id="$1"; source_id="$2"; target_id="$3"; port="$4"
    HOME="$sandbox" PATH="$fake:$PATH" \
        CLONE_CP_FAIL="${CLONE_CP_FAIL:-0}" CLONE_LOW_DISK="${CLONE_LOW_DISK:-0}" \
        NODE_VALIDATE_FAIL="${NODE_VALIDATE_FAIL:-0}" NODE_RESET_FAIL="${NODE_RESET_FAIL:-0}" \
        sh "$helper" _clone "$operation_id" "$source_id" "$target_id" "$port"
}

run_publish() {
    HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" clone-publish "$1"
}

run_abort() {
    HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" clone-abort "$1"
}

expect_failure() {
    output="$1"; shift
    if "$@" > "$output" 2>&1; then fail "command unexpectedly succeeded: $*"; fi
}

write_record() {
    operation_id="$1"; source_id="$2"; target_id="$3"; port="$4"; stage="$5"; was_running="$6"; restored="$7"; intent="$8"; published="$9"
    cat > "$sandbox/.risu-manager/state/$operation_id.clone" <<EOF
schema=1
operation_id=$operation_id
source_id=$source_id
target_id=$target_id
target_port=$port
stage=$stage
source_was_running=$was_running
source_restored=$restored
publication_intent=$intent
published=$published
EOF
}

write_state() {
    operation_id="$1"; source_id="$2"; target_id="$3"; state="$4"; stage="$5"; was_running="$6"; restored="$7"; port="$8"
    printf '{"ok":true,"operation":"clone","operationId":"%s","sourceInstanceId":"%s","targetInstanceId":"%s","state":"%s","stage":"%s","sourceWasRunning":%s,"sourceRestored":%s,"port":%s}\n' \
        "$operation_id" "$source_id" "$target_id" "$state" "$stage" "$was_running" "$restored" "$port" > "$sandbox/.risu-manager/state/$operation_id.json"
}

start_fixture_source() {
    source_id="$1"; source="$sandbox/.risu-manager/instances/$source_id"
    (cd "$source/repo" && PATH="$fake:$PATH" node server/node/server.cjs) &
    fixture_pid="$!"
    printf '%s\n' "$fixture_pid" > "$source/run/server.pid"
    printf '%s\n' "$fixture_pid" > "$sandbox/$source_id.fixture-pid"
    sleep 1
    kill -0 "$fixture_pid" 2>/dev/null || fail "fixture source failed to start"
}

make_source source-one
source_one="$sandbox/.risu-manager/instances/source-one"

# A newly installed source has no save directory or database until its first
# server start. Cloning initializes only the staged target and leaves that
# pristine source untouched.
make_source source-pristine
pristine_source="$sandbox/.risu-manager/instances/source-pristine"
rm -rf "$pristine_source/repo/save"
run_clone clone-pristine-001 source-pristine clone-pristine 6216
assert_absent "$pristine_source/repo/save/risuai.db"
assert_file "$sandbox/.risu-manager/clone-staging/clone-pristine-001/instance/repo/save/risuai.db"
run_publish clone-pristine-001 > "$sandbox/pristine-published.out"
assert_contains "$sandbox/pristine-published.out" '"state":"SUCCEEDED"'
assert_file "$sandbox/.risu-manager/instances/clone-pristine/repo/save/risuai.db"

# Missing storage is a supported pristine state, but a link or non-regular DB
# path is still rejected before the source can be stopped or copied.
make_source source-db-link
mv "$sandbox/.risu-manager/instances/source-db-link/repo/save/risuai.db" "$sandbox/source-db-link-outside.db"
ln -s "$sandbox/source-db-link-outside.db" "$sandbox/.risu-manager/instances/source-db-link/repo/save/risuai.db"
expect_failure "$sandbox/db-link-fail.out" run_clone clone-db-link-fail source-db-link clone-db-link-fail 6217
assert_contains "$sandbox/.risu-manager/state/clone-db-link-fail.json" '"code":"SOURCE_DATABASE_UNSAFE"'
assert_file "$sandbox/source-db-link-outside.db"
assert_absent "$sandbox/.risu-manager/instances/clone-db-link-fail"

# A stopped source reaches a validated unpublished boundary.
run_clone clone-stop-001 source-one clone-one 6201
ready="$sandbox/.risu-manager/state/clone-stop-001.json"
assert_contains "$ready" '"state":"READY_TO_PUBLISH"'
assert_contains "$ready" '"sourceWasRunning":false'
assert_contains "$ready" '"sourceRestored":true'
assert_absent "$sandbox/.risu-manager/instances/clone-one"
assert_dir "$sandbox/.risu-manager/clone-staging/clone-stop-001/instance"

published_out="$sandbox/published.out"
run_publish clone-stop-001 > "$published_out"
assert_contains "$published_out" '"state":"SUCCEEDED"'
assert_contains "$published_out" '"stage":"PUBLISHED"'
assert_contains "$published_out" '"sourceWasRunning":false'
assert_contains "$published_out" '"sourceRestored":true'
assert_contains "$published_out" '"commit":"278251f85a19bfdfd4cf3faae780e62682878f9e"'
target="$sandbox/.risu-manager/instances/clone-one"
assert_dir "$target"
assert_absent "$target/run/server.pid"
assert_file "$target/repo/save/__password"
[ "$(cat "$target/repo/save/__password")" = 'persisted-login-password' ] || fail 'login password was not preserved'
[ "$(cat "$target/repo/save/__jwt_secret")" != "$(cat "$source_one/repo/save/__jwt_secret")" ] || fail 'JWT secret was reused'
[ "$(cat "$target/repo/save/__instance_id")" != "$(cat "$source_one/repo/save/__instance_id")" ] || fail 'runtime instance ID was reused'
assert_absent "$target/repo/save/__sessions"
assert_absent "$target/repo/save/__authcode"
assert_absent "$target/repo/save/model-jobs"
assert_absent "$target/repo/save/model-jobs.db"
assert_absent "$target/repo/backups/in-progress.bin"
assert_absent "$target/repo/server/node/ssl/certificate/server.key"
assert_absent "$target/repo/server/node/ssl/certificate/server.crt"
assert_absent "$target/manager/tls.conf"
assert_absent "$target/manager/acme"
assert_file "$target/repo/save/assets/user.txt"
assert_contains "$target/metadata.json" '"instanceId":"clone-one"'
assert_contains "$target/metadata.json" '"version":"1.11.2"'
assert_contains "$target/repo/save/.fixture-backup-path" "$target/repo/backups"
clone_config="$sandbox/clone-config.out"
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" config-get clone-one > "$clone_config"
assert_contains "$clone_config" '"port":6201'
assert_contains "$clone_config" '"bindScope":"LOOPBACK"'
assert_contains "$clone_config" '"protocol":"HTTP"'
[ "$(stat -c '%d:%i' "$source_one/repo/save/risuai.db")" != "$(stat -c '%d:%i' "$target/repo/save/risuai.db")" ] || fail 'source and target database share an inode'
[ "$(realpath "$target/repo/save/internal-link")" = "$target/repo/save/assets/user.txt" ] || fail 'internal symlink did not resolve within clone'

# A published retry trusts its ownership marker without requiring the original DB hash.
printf '%s\n' 'legitimate post-publication change' >> "$target/repo/save/risuai.db"
run_publish clone-stop-001 > "$sandbox/published-retry.out"
assert_contains "$sandbox/published-retry.out" '"stage":"PUBLISHED"'
expect_failure "$sandbox/published-abort.out" run_abort clone-stop-001
assert_contains "$sandbox/published-abort.out" '"code":"CLONE_ALREADY_PUBLISHED"'
assert_file "$target/repo/save/risuai.db"

# Running sources are stopped at the snapshot boundary and restored before publication.
make_source source-running
start_fixture_source source-running
old_pid="$(cat "$sandbox/.risu-manager/instances/source-running/run/server.pid")"
run_clone clone-run-001 source-running clone-running 6202
running_ready="$sandbox/.risu-manager/state/clone-run-001.json"
assert_contains "$running_ready" '"sourceWasRunning":true'
assert_contains "$running_ready" '"sourceRestored":true'
new_pid="$(cat "$sandbox/.risu-manager/instances/source-running/run/server.pid")"
[ "$new_pid" != "$old_pid" ] || fail 'running source was not restarted with a new process'
kill -0 "$new_pid" 2>/dev/null || fail 'running source was not restored'
run_publish clone-run-001 >/dev/null
assert_absent "$sandbox/.risu-manager/instances/clone-running/run/server.pid"
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" stop source-running >/dev/null

# Copy, validation, storage, auth-layout, and symlink failures never publish partial targets.
CLONE_CP_FAIL=1 expect_failure "$sandbox/copy-fail.out" run_clone clone-copy-fail source-one clone-copy-fail 6203
assert_contains "$sandbox/.risu-manager/state/clone-copy-fail.json" '"code":"COPY_FAILED"'
assert_absent "$sandbox/.risu-manager/instances/clone-copy-fail"
assert_absent "$sandbox/.risu-manager/clone-staging/clone-copy-fail"

NODE_VALIDATE_FAIL=1 expect_failure "$sandbox/validate-fail.out" run_clone clone-valid-fail source-one clone-valid-fail 6204
assert_contains "$sandbox/.risu-manager/state/clone-valid-fail.json" '"code":"VALIDATION_FAILED"'
assert_absent "$sandbox/.risu-manager/instances/clone-valid-fail"

CLONE_LOW_DISK=1 expect_failure "$sandbox/disk-fail.out" run_clone clone-disk-fail source-one clone-disk-fail 6205
assert_contains "$sandbox/.risu-manager/state/clone-disk-fail.json" '"code":"INSUFFICIENT_DISK_SPACE"'
assert_absent "$sandbox/.risu-manager/clone-staging/clone-disk-fail"

make_source source-auth-unknown
printf '%s\n' 'unrecognized server auth implementation' > "$sandbox/.risu-manager/instances/source-auth-unknown/repo/server/node/server.cjs"
expect_failure "$sandbox/auth-fail.out" run_clone clone-auth-fail source-auth-unknown clone-auth-fail 6206
assert_contains "$sandbox/.risu-manager/state/clone-auth-fail.json" '"code":"UNSUPPORTED_AUTH_LAYOUT"'

make_source source-external-link
ln -s "$sandbox/outside-user-data" "$sandbox/.risu-manager/instances/source-external-link/repo/save/external-link"
printf '%s\n' 'outside' > "$sandbox/outside-user-data"
expect_failure "$sandbox/link-fail.out" run_clone clone-link-fail source-external-link clone-link-fail 6207
assert_contains "$sandbox/.risu-manager/state/clone-link-fail.json" '"code":"UNSAFE_CLONE_SYMLINK"'
assert_file "$sandbox/outside-user-data"
assert_absent "$sandbox/.risu-manager/instances/clone-link-fail"

# A copy failure after stopping a running source restores it.
make_source source-failure-running
start_fixture_source source-failure-running
CLONE_CP_FAIL=1 expect_failure "$sandbox/running-copy-fail.out" run_clone clone-run-fail source-failure-running clone-run-fail 6208
assert_contains "$sandbox/.risu-manager/state/clone-run-fail.json" '"sourceWasRunning":true'
assert_contains "$sandbox/.risu-manager/state/clone-run-fail.json" '"sourceRestored":true'
restored_pid="$(cat "$sandbox/.risu-manager/instances/source-failure-running/run/server.pid")"
kill -0 "$restored_pid" 2>/dev/null || fail 'source was not restored after copy failure'
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" stop source-failure-running >/dev/null

# Publication conflicts retain both unrelated target data and owned staging.
run_clone clone-conflict-001 source-one clone-conflict 6209
mkdir -p "$sandbox/.risu-manager/instances/clone-conflict"
printf '%s\n' 'unrelated target' > "$sandbox/.risu-manager/instances/clone-conflict/keep.txt"
expect_failure "$sandbox/conflict.out" run_publish clone-conflict-001
assert_contains "$sandbox/conflict.out" '"code":"PUBLICATION_CONFLICT"'
assert_file "$sandbox/.risu-manager/instances/clone-conflict/keep.txt"
assert_dir "$sandbox/.risu-manager/clone-staging/clone-conflict-001/instance"
expect_failure "$sandbox/conflict-abort.out" run_abort clone-conflict-001
assert_file "$sandbox/.risu-manager/instances/clone-conflict/keep.txt"
rm -rf "$sandbox/.risu-manager/instances/clone-conflict"
run_abort clone-conflict-001 >/dev/null

# Explicit abort removes only provisional operation-owned staging and is retryable.
run_clone clone-abort-001 source-one clone-aborted 6210
run_abort clone-abort-001 > "$sandbox/abort.out"
assert_contains "$sandbox/abort.out" '"operation":"clone-abort"'
assert_contains "$sandbox/abort.out" '"sourceRestored":true'
assert_absent "$sandbox/.risu-manager/clone-staging/clone-abort-001"
assert_absent "$sandbox/.risu-manager/instances/clone-aborted"
run_abort clone-abort-001 >/dev/null

# Queued process loss (before source stop) reconciles without touching the source.
write_record clone-queued-lost source-one clone-queued-lost 6211 PREPARED unknown 0 0 0
write_state clone-queued-lost source-one clone-queued-lost QUEUED QUEUED false false 6211
printf '%s\n' 999999 > "$sandbox/.risu-manager/state/clone-queued-lost.pid"
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-queued-lost > "$sandbox/queued-lost.out"
assert_contains "$sandbox/queued-lost.out" '"code":"CLONE_PROCESS_LOST"'
assert_file "$source_one/repo/save/risuai.db"
assert_absent "$sandbox/.risu-manager/instances/clone-queued-lost"

# Process loss after a recorded stop restores the source and cleans owned staging.
mkdir -p "$sandbox/.risu-manager/clone-staging/clone-stopped-lost/instance"
printf '%s\n' 'partial' > "$sandbox/.risu-manager/clone-staging/clone-stopped-lost/instance/partial"
write_record clone-stopped-lost source-one clone-stopped-lost 6212 SOURCE_STOPPED 1 0 0 0
write_state clone-stopped-lost source-one clone-stopped-lost RUNNING COPYING true false 6212
printf '%s\n' 999999 > "$sandbox/.risu-manager/state/clone-stopped-lost.pid"
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-stopped-lost > "$sandbox/stopped-lost.out"
assert_contains "$sandbox/stopped-lost.out" '"sourceRestored":true'
assert_absent "$sandbox/.risu-manager/clone-staging/clone-stopped-lost"
restored_pid="$(cat "$source_one/run/server.pid")"
kill -0 "$restored_pid" 2>/dev/null || fail 'process-loss recovery did not restart source'
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" stop source-one >/dev/null

# A ready result survives polling; publication followed by observer loss reconciles idempotently.
run_clone clone-recover-ready source-one clone-recovered 6213
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-recover-ready > "$sandbox/recover-ready.out"
assert_contains "$sandbox/recover-ready.out" '"state":"READY_TO_PUBLISH"'
run_publish clone-recover-ready >/dev/null
printf '%s\n' 'post-start-like-db-change' >> "$sandbox/.risu-manager/instances/clone-recovered/repo/save/risuai.db"
write_record clone-recover-ready source-one clone-recovered 6213 PUBLISHING 0 1 1 0
write_state clone-recover-ready source-one clone-recovered RUNNING PUBLISHING false true 6213
printf '%s\n' 999999 > "$sandbox/.risu-manager/state/clone-recover-ready.pid"
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-recover-ready > "$sandbox/recovered-published.out"
assert_contains "$sandbox/recovered-published.out" '"state":"SUCCEEDED"'
assert_contains "$sandbox/recovered-published.out" '"stage":"PUBLISHED"'
assert_file "$sandbox/.risu-manager/instances/clone-recovered/repo/save/risuai.db"

# Same-operation publish, abort, and recovery are serialized by a live action lock.
run_clone clone-busy-001 source-one clone-busy 6214
sleep 60 &
busy_pid="$!"
printf '%s\n' "$busy_pid" > "$sandbox/busy.fixture-pid"
busy_lock="$sandbox/.risu-manager/locks/clone-operation-clone-busy-001"
mkdir "$busy_lock"
printf '%s\n' "$busy_pid" > "$busy_lock/pid"
expect_failure "$sandbox/busy-publish.out" run_publish clone-busy-001
assert_contains "$sandbox/busy-publish.out" '"code":"CLONE_BUSY"'
expect_failure "$sandbox/busy-abort.out" run_abort clone-busy-001
assert_contains "$sandbox/busy-abort.out" '"code":"CLONE_BUSY"'
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-busy-001 > "$sandbox/busy-operation.out"
assert_contains "$sandbox/busy-operation.out" '"state":"READY_TO_PUBLISH"'
kill -TERM "$busy_pid" 2>/dev/null || true
rm -rf "$busy_lock"
run_abort clone-busy-001 >/dev/null

# Dispatch validates immutable arguments, detects managed ports, and safely recovers a dead queued child.
NOHUP_SUPPRESS=1 HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" dispatch clone clone-dispatch-001 source-one clone-dispatch 6215 > "$sandbox/dispatch.out"
assert_contains "$sandbox/dispatch.out" '"state":"QUEUED"'
assert_contains "$sandbox/dispatch.out" '"sourceWasRunning":false'
assert_not_contains "$sandbox/dispatch.out" '"sourceWasRunning":0'
sleep 1
HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" operation clone-dispatch-001 > "$sandbox/dispatch-lost.out"
assert_contains "$sandbox/dispatch-lost.out" '"code":"CLONE_PROCESS_LOST"'

expect_failure "$sandbox/duplicate-port.out" env HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" dispatch clone clone-port-busy source-one clone-port-busy 6201
assert_contains "$sandbox/duplicate-port.out" '"code":"PORT_UNAVAILABLE"'
expect_failure "$sandbox/invalid-target.out" env HOME="$sandbox" PATH="$fake:$PATH" sh "$helper" dispatch clone clone-invalid source-one ../escape 6300
assert_contains "$sandbox/invalid-target.out" '"code":"INVALID_TARGET_INSTANCE_ID"'

printf 'Termux clone contract: PASS\n'
