#!/usr/bin/env sh
set -eu

installer="${1:-app/src/main/assets/termux-helper/install.sh}"
[ -r "$installer" ] || { echo "installer not found: $installer" >&2; exit 2; }

source_sha=278251f85a19bfdfd4cf3faae780e62682878f9e
target_sha=b315d898abd543fffaf5346d8eb3246b20da92cd
sandbox="$(mktemp -d)"
helper="$sandbox/.risu-manager/bin/risu-manager"
fake="$sandbox/fake-bin"
prefix="$sandbox/prefix"

cleanup() {
    for pid_file in "$sandbox"/*.fixture-pid; do
        [ -r "$pid_file" ] || continue
        pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
        case "$pid" in ''|*[!0-9]*) ;; *) kill -TERM "$pid" 2>/dev/null || true ;; esac
    done
    if [ "${KEEP_FIXTURE:-0}" = 1 ]; then echo "fixture preserved: $sandbox" >&2; else rm -rf "$sandbox"; fi
}
trap cleanup EXIT INT TERM

fail() { echo "Termux rollback contract: FAIL: $1" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }
assert_absent() { [ ! -e "$1" ] && [ ! -L "$1" ] || fail "unexpected path: $1"; }
assert_contains() {
    if ! grep -F -- "$2" "$1" >/dev/null; then
        printf '%s\n' "--- $(basename "$1") ---" >&2
        sed -n '1,120p' "$1" >&2
        fail "$(basename "$1") lacks: $2"
    fi
}
expect_failure() { output="$1"; shift; if "$@" > "$output" 2>&1; then fail "command unexpectedly succeeded: $*"; fi; }

mkdir -p "$fake" "$prefix/lib/node_modules/npm/node_modules/node-gyp/bin"
: > "$prefix/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js"

cat > "$fake/node" <<'EOF'
#!/usr/bin/env sh
set -eu
tree_hash() {
    root="$1"
    (
        cd "$root"
        find . -print | LC_ALL=C sort | while IFS= read -r item; do
            [ "$item" = . ] && continue
            if [ -d "$item" ] && [ ! -L "$item" ]; then
                printf 'd\t%s\n' "$item"
            elif [ -L "$item" ]; then
                printf 'l\t%s\t%s\n' "$(readlink "$item")" "$item"
            elif [ -f "$item" ]; then
                printf 'f\t%s\t' "$item"
                sha256sum "$item" | awk '{print $1}'
            else
                exit 3
            fi
        done
    ) | sha256sum | awk '{print $1}'
}
if [ "${1:-}" = -p ]; then printf '%s\n' '22.12.0'; exit 0; fi
if [ "${1:-}" = --version ]; then printf '%s\n' 'v22.12.0'; exit 0; fi
if [ "${1:-}" = - ]; then
    [ "${CURL_BLOCK:-0}" = 1 ] && sleep 30
    [ "${CURL_HEALTH_FAIL:-0}" != 1 ]
    exit $?
fi
if [ "${1:-}" = -e ]; then
    script="$2"; shift 2
    case "$script" in
        *'console.log("d\\t"'*)
            root="$1"; (cd "$root"; find . -print | LC_ALL=C sort | while IFS= read -r item; do
                [ "$item" = . ] && continue; rel="${item#./}"
                if [ -d "$item" ] && [ ! -L "$item" ]; then printf 'd\t%s\n' "$rel"
                elif [ -L "$item" ]; then printf 'l\t%s\t%s\n' "$(readlink "$item")" "$rel"
                elif [ -f "$item" ]; then printf 'f\t%s\t%s\t%s\n' "$(wc -c < "$item" | tr -d ' ')" "$(sha256sum "$item" | awk '{print $1}')" "$rel"; else exit 3; fi
            done) ;;
        *'buf=Buffer.allocUnsafe'*|*'createHash("sha256")'*) tree_hash "$1" ;;
        *'shared inode:'*)
            for f in $(find "$1" -type f -print); do rel="${f#"$1"/}"; [ "$(stat -c '%d:%i' "$f")" != "$(stat -c '%d:%i' "$2/$rel")" ] || exit 1; done ;;
        *'broken or escaping symlink:'*) exit 0 ;;
        *'CREATE TABLE IF NOT EXISTS kv'*) mkdir -p "$1/save"; printf '%s\n' sqlite-fixture > "$1/save/risuai.db" ;;
        *'quick_check'*) [ -f "$1/save/risuai.db" ] ;;
        *'config/server-backup-path'*) printf '%s\n' "$2" > "$1/save/.fixture-backup-path" ;;
        *'randomUUID'*) printf '%s' '11111111-1111-4111-8111-111111111111' ;;
        *'JSON.parse(f.readFileSync'*)
            sed -e "s/\"commit\":\"[^\"]*\"/\"commit\":\"$2\"/" -e "s/\"version\":\"[^\"]*\"/\"version\":\"$3\"/" "$1" > "$4" ;;
        *'net.createServer'*) exit 0 ;;
        *) exit 0 ;;
    esac
    exit 0
fi
case "${1:-}" in *risu-server-patch.cjs) exit 0 ;; esac
trap 'exit 0' HUP INT TERM
while :; do sleep 1; done
EOF

cat > "$fake/git" <<EOF
#!/usr/bin/env sh
set -eu
if [ "\${1:-}" = --version ]; then echo 'git version fixture'; exit 0; fi
if [ "\${1:-}" = init ]; then mkdir -p "\$2/.git"; exit 0; fi
if [ "\${1:-}" = -C ]; then
    repo="\$2"; case "\${3:-}" in
        rev-parse) sed -n '1p' "\$repo/.fixture-head" ;;
        remote|fetch) exit 0 ;;
        checkout)
            mkdir -p "\$repo/server/node" "\$repo/dist" "\$repo/save" "\$repo/backups"
            echo '$target_sha' > "\$repo/.fixture-head"; echo '{"version":"1.12.0"}' > "\$repo/package.json"
            echo 'lockfileVersion: fixture' > "\$repo/pnpm-lock.yaml"; echo '<html>B</html>' > "\$repo/dist/index.html"
            cat > "\$repo/server/node/server.cjs" <<'SERVER'
const passwordPath = path.join(process.cwd(), 'save', '__password')
readFileSync(passwordPath, 'utf-8')
const jwtSecretPath = path.join(savePath, '__jwt_secret')
writeFileSync(jwtSecretPath, jwtSecret)
nodeCrypto.createHmac('sha256', jwtSecret)
const instanceIdPath = path.join(savePath, '__instance_id')
nodeCrypto.randomUUID()
writeFileSync(instanceIdPath, instanceId)
const SESSION_FILE = path.join(process.cwd(), 'save', '__sessions')
readFileSync(SESSION_FILE)
writeFileSync(SESSION_FILE, sessions)
const authCodePath = path.join(process.cwd(), 'save', '__authcode')
SERVER
            ;;
        *) exit 0 ;;
    esac
    exit 0
fi
exit 1
EOF

cat > "$fake/pnpm" <<'EOF'
#!/usr/bin/env sh
case " ${*:-} " in *' build '*) mkdir -p dist; echo '<html>B</html>' > dist/index.html ;; esac
EOF
cat > "$fake/curl" <<'EOF'
#!/usr/bin/env sh
[ "${CURL_BLOCK:-0}" = 1 ] && sleep 30
exit 0
EOF
cat > "$fake/termux-wake-lock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
cp "$fake/termux-wake-lock" "$fake/termux-wake-unlock"
cat > "$fake/nohup" <<'EOF'
#!/usr/bin/env sh
# The installed helper has a Termux shebang; execute it with sh in this host fixture.
if [ "${1:-}" = "$HOME/.risu-manager/bin/risu-manager" ]; then
    helper="$1"; shift
    exec sh "$helper" "$@"
fi
exec /usr/bin/nohup "$@"
EOF
cat > "$fake/df" <<'EOF'
#!/usr/bin/env sh
/bin/df "$@"
EOF
chmod +x "$fake"/*

HOME="$sandbox" sh "$installer" > "$sandbox/bootstrap.out"
assert_contains "$sandbox/bootstrap.out" '"helperVersion":26'

instance_id=pocketrisu-rollback
instance="$sandbox/.risu-manager/instances/$instance_id"; repo="$instance/repo"
mkdir -p "$repo/.git" "$repo/server/node/ssl/certificate" "$repo/dist" "$repo/save/assets" "$repo/backups" "$instance/run" "$instance/manager"
echo "$source_sha" > "$repo/.fixture-head"; echo '{"version":"1.11.2"}' > "$repo/package.json"; echo '<html>A</html>' > "$repo/dist/index.html"
cat > "$repo/server/node/server.cjs" <<'EOF'
const passwordPath = path.join(process.cwd(), 'save', '__password')
readFileSync(passwordPath, 'utf-8')
const jwtSecretPath = path.join(savePath, '__jwt_secret')
writeFileSync(jwtSecretPath, jwtSecret)
nodeCrypto.createHmac('sha256', jwtSecret)
const instanceIdPath = path.join(savePath, '__instance_id')
nodeCrypto.randomUUID()
writeFileSync(instanceIdPath, instanceId)
const SESSION_FILE = path.join(process.cwd(), 'save', '__sessions')
readFileSync(SESSION_FILE)
writeFileSync(SESSION_FILE, sessions)
const authCodePath = path.join(process.cwd(), 'save', '__authcode')
EOF
echo state-A > "$repo/save/risuai.db"; echo asset-A > "$repo/save/assets/state.txt"
echo source-secret > "$repo/save/__jwt_secret"; echo aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa > "$repo/save/__instance_id"
echo persisted-password > "$repo/save/__password"
printf '{"instanceId":"%s","variantId":"pocketrisu","commit":"%s","version":"1.11.2","workDir":"repo","entrypoint":"server/node/server.cjs"}\n' "$instance_id" "$source_sha" > "$instance/metadata.json"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" config-set "$instance_id" 6101 LOOPBACK HTTP 100 5 5 20 500 >/dev/null

# Produce a genuine, helper-owned State A point via the reviewed update path.
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" _update update-for-rollback "$instance_id" pocketrisu "$target_sha" recovery-state-a 604800 > "$sandbox/update.out"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation update-for-rollback > "$sandbox/update.out"
assert_contains "$sandbox/update.out" '"state":"SUCCEEDED"'
echo state-B > "$repo/save/risuai.db"; echo asset-B > "$repo/save/assets/state.txt"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" config-set "$instance_id" 6102 LOCAL_NETWORK HTTP 200 10 10 30 1000 >/dev/null

HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-inspect "$instance_id" recovery-state-a > "$sandbox/inspect.out"
assert_contains "$sandbox/inspect.out" '"operation":"recovery-inspect"'
assert_contains "$sandbox/inspect.out" "\"targetCommit\":\"$source_sha\""
assert_contains "$sandbox/inspect.out" '"dataChanged":true'
assert_contains "$sandbox/inspect.out" '"configurationChanged":true'

HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-pin recovery-state-a true > "$sandbox/pin.out"
assert_contains "$sandbox/pin.out" '"pinned":true'
expect_failure "$sandbox/pinned-delete.out" env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-delete recovery-state-a
assert_contains "$sandbox/pinned-delete.out" '"code":"RECOVERY_PINNED"'
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-pin recovery-state-a false >/dev/null

# Manual rollback must first retain B, then restore and health-check A as a bundle.
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" _rollback rollback-success "$instance_id" recovery-state-a recovery-state-b 604800 > "$sandbox/rollback.out"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation rollback-success > "$sandbox/rollback.out"
assert_contains "$sandbox/rollback.out" '"operation":"rollback"'
assert_contains "$sandbox/rollback.out" '"state":"SUCCEEDED"'
assert_contains "$sandbox/rollback.out" '"preRollbackRecoveryPointId":"recovery-state-b"'
assert_contains "$sandbox/rollback.out" '"launchConfirmed":true'
assert_contains "$instance/metadata.json" "$source_sha"
assert_contains "$repo/save/risuai.db" state-A
assert_contains "$sandbox/.risu-manager/recovery-points/recovery-state-b/instance/repo/save/risuai.db" state-B

# Clone B from its retained point without stopping or mutating current A.
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" dispatch recovery-clone recovery-clone-b "$instance_id" recovery-state-b pocketrisu-state-b 6201 > "$sandbox/recovery-clone-dispatch.out"
attempts=0
while [ "$attempts" -lt 60 ]; do
    HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation recovery-clone-b > "$sandbox/recovery-clone-poll.out" || true
    grep -F '"state":"READY_TO_PUBLISH"' "$sandbox/recovery-clone-poll.out" >/dev/null && break
    if grep -F '"state":"FAILED"' "$sandbox/recovery-clone-poll.out" >/dev/null; then
        HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation-log recovery-clone-b 200 >&2 || true
        break
    fi
    attempts=$((attempts + 1)); sleep 1
done
assert_contains "$sandbox/recovery-clone-poll.out" '"state":"READY_TO_PUBLISH"'
assert_contains "$sandbox/recovery-clone-poll.out" '"recoveryPointId":"recovery-state-b"'
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" clone-publish recovery-clone-b > "$sandbox/recovery-clone-publish.out"
assert_contains "$sandbox/recovery-clone-publish.out" '"state":"SUCCEEDED"'
clone="$sandbox/.risu-manager/instances/pocketrisu-state-b"
assert_file "$clone/repo/save/risuai.db"; assert_contains "$clone/repo/save/risuai.db" state-B; assert_contains "$clone/metadata.json" "$target_sha"
assert_contains "$repo/save/risuai.db" state-A

# A crash after the success journal but before the terminal state is published must reconcile.
success_state="$sandbox/.risu-manager/state/rollback-success.json"
sed -e 's/"state":"SUCCEEDED"/"state":"RUNNING"/' -e 's/"stage":"SUCCEEDED"/"stage":"VERIFYING"/' "$success_state" > "$success_state.tmp"
mv "$success_state.tmp" "$success_state"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation rollback-success > "$sandbox/rollback-replayed.out"
assert_contains "$sandbox/rollback-replayed.out" '"state":"SUCCEEDED"'
assert_contains "$sandbox/rollback-replayed.out" '"healthConfirmed":true'

# A killed rollback is reconciled from the verified pre-rollback point.
env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" CURL_BLOCK=1 sh "$helper" _rollback rollback-interrupted "$instance_id" recovery-state-b recovery-before-interrupt 604800 > "$sandbox/interrupted-worker.out" 2>&1 &
worker="$!"; echo "$worker" > "$sandbox/interrupted.fixture-pid"
attempts=0
while ! grep -F "$target_sha" "$instance/metadata.json" >/dev/null 2>&1 && [ "$attempts" -lt 60 ]; do attempts=$((attempts + 1)); sleep 1; done
grep -F "$target_sha" "$instance/metadata.json" >/dev/null || fail 'interrupted rollback never reached activation'
kill -KILL "$worker" 2>/dev/null || true; sleep 1
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation rollback-interrupted > "$sandbox/interrupted.out"
assert_contains "$sandbox/interrupted.out" '"state":"FAILED"'
assert_contains "$sandbox/interrupted.out" '"restored":true'
assert_contains "$instance/metadata.json" "$source_sha"
assert_contains "$repo/save/risuai.db" state-A

# Same server commit can still carry newer conversations, assets, or settings.
echo state-C > "$repo/save/risuai.db"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" _rollback rollback-same-revision "$instance_id" recovery-state-a recovery-state-c 604800 > "$sandbox/same-revision.out"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation rollback-same-revision > "$sandbox/same-revision.out"
assert_contains "$sandbox/same-revision.out" '"state":"SUCCEEDED"'
assert_contains "$instance/metadata.json" "$source_sha"
assert_contains "$repo/save/risuai.db" state-A
assert_contains "$sandbox/.risu-manager/recovery-points/recovery-state-c/instance/repo/save/risuai.db" state-C

HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-delete recovery-state-a > "$sandbox/delete.out"
assert_contains "$sandbox/delete.out" '"deleted":true'
assert_absent "$sandbox/.risu-manager/recovery-points/recovery-state-a"

printf 'Termux rollback contract: PASS\n'
