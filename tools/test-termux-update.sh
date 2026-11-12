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
        fixture_pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
        case "$fixture_pid" in ''|*[!0-9]*) ;; *) kill -TERM "$fixture_pid" 2>/dev/null || true ;; esac
    done
    if [ "${KEEP_FIXTURE:-0}" = 1 ]; then printf 'fixture preserved: %s\n' "$sandbox" >&2; else rm -rf "$sandbox"; fi
}
trap cleanup EXIT INT TERM

fail() { printf 'Termux update contract: FAIL: %s\n' "$1" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }
assert_absent() { [ ! -e "$1" ] && [ ! -L "$1" ] || fail "unexpected path: $1"; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "$(basename "$1") lacks: $2"; }
assert_equal() { [ "$1" = "$2" ] || fail "expected '$1' to equal '$2'"; }
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
            if [ -d "$item" ] && [ ! -L "$item" ]; then printf 'd\t%s\n' "$item"
            elif [ -L "$item" ]; then printf 'l\t%s\t%s\n' "$(readlink "$item")" "$item"
            elif [ -f "$item" ]; then printf 'f\t%s\t' "$item"; sha256sum "$item" | awk '{print $1}'
            else exit 3
            fi
        done
    ) | sha256sum | awk '{print $1}'
}
if [ "${1:-}" = -p ]; then printf '%s\n' '22.12.0'; exit 0; fi
if [ "${1:-}" = - ]; then
    if [ "${CURL_BLOCK:-0}" = 1 ]; then sleep 20; fi
    exit 0
fi
if [ "${1:-}" = -e ]; then
    script="$2"; shift 2
    case "$script" in
        *'console.log("d\\t"'*)
            root="$1"
            (cd "$root"; find . -print | LC_ALL=C sort | while IFS= read -r item; do
                [ "$item" = . ] && continue; rel="${item#./}"
                if [ -d "$item" ] && [ ! -L "$item" ]; then printf 'd\t%s\n' "$rel"
                elif [ -L "$item" ]; then printf 'l\t%s\t%s\n' "$(readlink "$item")" "$rel"
                elif [ -f "$item" ]; then size="$(wc -c < "$item" | tr -d ' ')"; hash="$(sha256sum "$item" | awk '{print $1}')"; printf 'f\t%s\t%s\t%s\n' "$size" "$hash" "$rel"
                else exit 3; fi
            done)
            ;;
        *'buf=Buffer.allocUnsafe'*|*'createHash("sha256")'*) tree_hash "$1" ;;
        *'JSON.parse(f.readFileSync'*)
            metadata="$1"; target="$2"; version="$3"; temporary="$4"
            sed -e "s/\"commit\":\"[^\"]*\"/\"commit\":\"$target\"/" -e "s/\"version\":\"[^\"]*\"/\"version\":\"$version\"/" "$metadata" > "$temporary"
            ;;
        *'net.createServer'*) exit 0 ;;
        *) exit 0 ;;
    esac
    exit 0
fi
case "${1:-}" in
    *risu-server-patch.cjs) exit 0 ;;
esac
if [ "${NODE_SERVER_FAIL:-0}" = 1 ] && [ "$(sed -n '1p' .fixture-head 2>/dev/null || true)" = b315d898abd543fffaf5346d8eb3246b20da92cd ]; then exit 1; fi
trap 'exit 0' HUP INT TERM
while :; do sleep 1; done
EOF

cat > "$fake/git" <<EOF
#!/usr/bin/env sh
set -eu
if [ "\${1:-}" = --version ]; then printf '%s\n' 'git version fixture'; exit 0; fi
if [ "\${1:-}" = init ]; then mkdir -p "\$2/.git"; exit 0; fi
if [ "\${1:-}" = -C ]; then
    repo="\$2"; verb="\${3:-}"
    case "\$verb" in
        rev-parse) sed -n '1p' "\$repo/.fixture-head"; exit 0 ;;
        remote|fetch) exit 0 ;;
        checkout)
            mkdir -p "\$repo/server/node" "\$repo/dist" "\$repo/scripts/termux"
            printf '%s\n' '$target_sha' > "\$repo/.fixture-head"
            printf '{"version":"%s"}\n' "\${FIXTURE_TARGET_VERSION:-1.12.0}" > "\$repo/package.json"
            printf '%s\n' 'lockfileVersion: fixture' > "\$repo/pnpm-lock.yaml"
            printf '%s\n' '// RISU_MANAGER_RUNTIME_CONFIG_V1' > "\$repo/server/node/server.cjs"
            printf '%s\n' '#!/usr/bin/env bash' > "\$repo/scripts/termux/build.sh"
            exit 0
            ;;
    esac
fi
exit 1
EOF

cat > "$fake/pnpm" <<'EOF'
#!/usr/bin/env sh
set -eu
case " ${*:-} " in *' build '*) mkdir -p dist; printf '%s\n' '<html>updated</html>' > dist/index.html ;; esac
exit 0
EOF
cat > "$fake/curl" <<'EOF'
#!/usr/bin/env sh
if [ "${CURL_BLOCK:-0}" = 1 ]; then sleep 20; fi
[ "${CURL_HEALTH_FAIL:-0}" != 1 ]
EOF
cat > "$fake/termux-wake-lock" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
cp "$fake/termux-wake-lock" "$fake/termux-wake-unlock"
cat > "$fake/df" <<'EOF'
#!/usr/bin/env sh
if [ "${UPDATE_LOW_DISK:-0}" = 1 ]; then
    printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\nfixture 10 9 1 90%% /\n'
else
    if [ -x /system/bin/df ]; then exec /system/bin/df "$@"; else exec /bin/df "$@"; fi
fi
EOF
cat > "$fake/cp" <<'EOF'
#!/usr/bin/env sh
if [ "${UPDATE_COPY_FAIL:-0}" = 1 ]; then
    case " $* " in
        *'/repo/save '*'/staged/repo/save '*) exit 1 ;;
    esac
fi
exec /bin/cp "$@"
EOF
chmod +x "$fake"/*

HOME="$sandbox" sh "$installer" > "$sandbox/bootstrap.out"
assert_contains "$sandbox/bootstrap.out" '"helperVersion":26'

# Manager update storage must never follow a pre-existing symlink outside its
# private root.
rmdir "$sandbox/.risu-manager/recovery-points"
mkdir "$sandbox/outside-recovery"
ln -s "$sandbox/outside-recovery" "$sandbox/.risu-manager/recovery-points"
expect_failure "$sandbox/unsafe-recovery.out" env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-estimate unsafe-test
assert_contains "$sandbox/unsafe-recovery.out" '"code":"UNSAFE_MANAGER_PATH"'
assert_absent "$sandbox/outside-recovery/unsafe-test"
rm "$sandbox/.risu-manager/recovery-points"
mkdir "$sandbox/.risu-manager/recovery-points"
chmod 700 "$sandbox/.risu-manager/recovery-points"

make_instance() {
    instance_id="$1"; version="${2:-1.11.2}"; sha="${3:-$source_sha}"; port="${4:-6101}"
    instance="$sandbox/.risu-manager/instances/$instance_id"; repo="$instance/repo"
    mkdir -p "$repo/.git" "$repo/server/node/ssl/certificate" "$repo/dist" "$repo/save/assets" "$repo/backups" "$instance/run" "$instance/manager"
    printf '%s\n' "$sha" > "$repo/.fixture-head"
    printf '%s\n' '// RISU_MANAGER_RUNTIME_CONFIG_V1' > "$repo/server/node/server.cjs"
    printf '%s\n' '<html>old</html>' > "$repo/dist/index.html"
    printf '{"version":"%s"}\n' "$version" > "$repo/package.json"
    printf '%s\n' 'sample-save' > "$repo/save/risuai.db"
    printf '%s\n' 'sample-asset' > "$repo/save/assets/user.txt"
    printf '%s\n' 'backup-data' > "$repo/backups/keep.bin"
    printf '%s\n' 'private-key' > "$repo/server/node/ssl/certificate/server.key"
    printf '%s\n' 'certificate' > "$repo/server/node/ssl/certificate/server.crt"
    printf '{"instanceId":"%s","variantId":"pocketrisu","commit":"%s","version":"%s","workDir":"repo","entrypoint":"server/node/server.cjs"}\n' "$instance_id" "$sha" "$version" > "$instance/metadata.json"
    HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" config-set "$instance_id" "$port" LOOPBACK HTTP 100 5 5 20 500 >/dev/null
}

run_update() {
    operation="$1"; instance="$2"; recovery="$3"; retention="$4"
    HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" _update "$operation" "$instance" pocketrisu "$target_sha" "$recovery" "$retention"
}

# Estimate exposes the immutable installed identity and disk preflight contract.
make_instance update-success
if ! HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-estimate update-success > "$sandbox/estimate.out"; then
    cat "$sandbox/estimate.out" >&2; fail 'recovery estimate failed'
fi
assert_contains "$sandbox/estimate.out" '"currentCommit":"278251f85a19bfdfd4cf3faae780e62682878f9e"'
assert_contains "$sandbox/estimate.out" '"requiredBytes":'
assert_contains "$sandbox/estimate.out" '"availableBytes":'

# A source/target pair succeeds, preserves private state, and records
# completion-based expiry plus verified recovery digests.
run_update update-success-001 update-success recovery-success-001 1 > "$sandbox/success.out"
state="$sandbox/.risu-manager/state/update-success-001.json"
assert_contains "$state" '"state":"SUCCEEDED"'
assert_contains "$state" '"targetCommit":"b315d898abd543fffaf5346d8eb3246b20da92cd"'
assert_contains "$state" '"activeCommit":"b315d898abd543fffaf5346d8eb3246b20da92cd"'
assert_contains "$state" '"launchConfirmed":true'
assert_contains "$state" '"healthConfirmed":true'
assert_contains "$state" '"restorationState":"NOT_REQUIRED"'
assert_contains "$state" '"completedAt":'
assert_contains "$state" '"expiresAt":'
assert_file "$sandbox/.risu-manager/instances/update-success/repo/save/risuai.db"
assert_file "$sandbox/.risu-manager/instances/update-success/repo/save/assets/user.txt"
assert_file "$sandbox/.risu-manager/recovery-points/recovery-success-001/files.manifest"
assert_file "$sandbox/.risu-manager/recovery-points/recovery-success-001/manifest.sha256"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" status update-success > "$sandbox/stopped-success.out"
assert_contains "$sandbox/stopped-success.out" '"state":"STOPPED"'

# A copy failure after recovery verification must abort before the switch and
# restore the original instance instead of publishing a partial update.
make_instance update-copy-failure 1.11.2 "$source_sha" 6106
expect_failure "$sandbox/copy-failure.out" env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" UPDATE_COPY_FAIL=1 sh "$helper" _update update-copy-failure-001 update-copy-failure pocketrisu "$target_sha" recovery-copy-failure-001 60
copy_failure_state="$sandbox/.risu-manager/state/update-copy-failure-001.json"
assert_contains "$copy_failure_state" '"state":"FAILED"'
assert_contains "$copy_failure_state" '"code":"DATA_TRANSFER_FAILED"'
assert_contains "$copy_failure_state" '"restored":true'
assert_contains "$sandbox/.risu-manager/instances/update-copy-failure/metadata.json" "$source_sha"
assert_equal "$(sed -n '1p' "$sandbox/.risu-manager/instances/update-copy-failure/repo/save/risuai.db")" sample-save
assert_equal "$(sed -n '1p' "$sandbox/.risu-manager/instances/update-copy-failure/repo/save/assets/user.txt")" sample-asset

# Cleanup honors the short fixture retention and removes only the identified,
# completed, unpinned, non-required Manager recovery point.
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" recovery-cleanup recovery-success-001 9999999999999 > "$sandbox/cleanup.out"
assert_contains "$sandbox/cleanup.out" '"deleted":true'
assert_absent "$sandbox/.risu-manager/recovery-points/recovery-success-001"

# A different installed commit and target version are accepted without a pair allowlist.
make_instance alternate-path 1.11.1 b8bbcbe065755379d33f74d6ad16a36d634917c1 6102
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" FIXTURE_TARGET_VERSION=1.13.0 sh "$helper" _update update-alternate-001 alternate-path pocketrisu "$target_sha" recovery-alternate-001 60 > "$sandbox/alternate.out"
assert_contains "$sandbox/.risu-manager/state/update-alternate-001.json" '"state":"SUCCEEDED"'
assert_contains "$sandbox/.risu-manager/state/update-alternate-001.json" '"targetVersion":"1.13.0"'
assert_contains "$sandbox/.risu-manager/instances/alternate-path/metadata.json" '"version":"1.13.0"'

# A launch failure after the switch restores the whole instance and its prior
# running state. A separate instance must remain byte-for-byte unchanged.
make_instance update-failure 1.11.2 "$source_sha" 6103
make_instance other-instance 1.11.2 "$source_sha" 6104
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" start update-failure > "$sandbox/source-start.out"
sed -n '1p' "$sandbox/.risu-manager/instances/update-failure/run/server.pid" > "$sandbox/source.fixture-pid"
other_before="$(HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" node -e 'const f=require("fs"),p=require("path"),c=require("crypto"),root=p.resolve(process.argv[1]),h=c.createHash("sha256"),buf=Buffer.allocUnsafe(1024*1024);function hf(q){const fd=f.openSync(q,"r");try{let n;while((n=f.readSync(fd,buf,0,buf.length,null))>0)h.update(buf.subarray(0,n))}finally{f.closeSync(fd)}}function w(d,r){for(const n of f.readdirSync(d).sort()){const q=p.join(d,n),x=r?`${r}/${n}`:n,s=f.lstatSync(q);let t=s.isDirectory()?"d":s.isFile()?"f":s.isSymbolicLink()?"l":"x";h.update(t+"\\0"+x+"\\0");if(t==="d")w(q,x);else if(t==="f"){h.update(String(s.size)+"\\0");hf(q)}else if(t==="l")h.update(f.readlinkSync(q));else process.exit(3)}}w(root,"");process.stdout.write(h.digest("hex"));' "$sandbox/.risu-manager/instances/other-instance")"
expect_failure "$sandbox/failure.out" env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" NODE_SERVER_FAIL=1 sh "$helper" _update update-failure-001 update-failure pocketrisu "$target_sha" recovery-failure-001 60
failure_state="$sandbox/.risu-manager/state/update-failure-001.json"
assert_contains "$failure_state" '"state":"FAILED"'
assert_contains "$failure_state" '"restored":true'
assert_contains "$failure_state" '"restorationState":"RESTORED"'
assert_contains "$sandbox/.risu-manager/instances/update-failure/metadata.json" "$source_sha"
assert_equal "$(sed -n '1p' "$sandbox/.risu-manager/instances/update-failure/repo/save/risuai.db")" sample-save
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" status update-failure > "$sandbox/restored-status.out"
assert_contains "$sandbox/restored-status.out" '"state":"RUNNING"'
sed -n '1p' "$sandbox/.risu-manager/instances/update-failure/run/server.pid" > "$sandbox/restored.fixture-pid"
other_after="$(HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" node -e 'const f=require("fs"),p=require("path"),c=require("crypto"),root=p.resolve(process.argv[1]),h=c.createHash("sha256"),buf=Buffer.allocUnsafe(1024*1024);function hf(q){const fd=f.openSync(q,"r");try{let n;while((n=f.readSync(fd,buf,0,buf.length,null))>0)h.update(buf.subarray(0,n))}finally{f.closeSync(fd)}}function w(d,r){for(const n of f.readdirSync(d).sort()){const q=p.join(d,n),x=r?`${r}/${n}`:n,s=f.lstatSync(q);let t=s.isDirectory()?"d":s.isFile()?"f":s.isSymbolicLink()?"l":"x";h.update(t+"\\0"+x+"\\0");if(t==="d")w(q,x);else if(t==="f"){h.update(String(s.size)+"\\0");hf(q)}else if(t==="l")h.update(f.readlinkSync(q));else process.exit(3)}}w(root,"");process.stdout.write(h.digest("hex"));' "$sandbox/.risu-manager/instances/other-instance")"
assert_equal "$other_before" "$other_after"

# Simulate SIGKILL after activation. Polling operation must reconcile the
# durable journal, restore the verified recovery point, and never report success.
make_instance update-interrupt 1.11.2 "$source_sha" 6105
env HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" CURL_BLOCK=1 sh "$helper" _update update-interrupt-001 update-interrupt pocketrisu "$target_sha" recovery-interrupt-001 60 > "$sandbox/interruption-worker.out" 2>&1 &
worker_pid="$!"; printf '%s\n' "$worker_pid" > "$sandbox/interruption.fixture-pid"
attempts=0
while ! grep -F "$target_sha" "$sandbox/.risu-manager/instances/update-interrupt/metadata.json" >/dev/null 2>&1 && [ "$attempts" -lt 50 ]; do attempts=$((attempts + 1)); sleep 1; done
grep -F "$target_sha" "$sandbox/.risu-manager/instances/update-interrupt/metadata.json" >/dev/null || fail 'interruption fixture never reached activation'
kill -KILL "$worker_pid" 2>/dev/null || true
sleep 1
interruption_manifest="$sandbox/.risu-manager/recovery-points/recovery-interrupt-001/manifest.conf"
sed -i 's/^instance_id=update-interrupt$/instance_id=other-instance/' "$interruption_manifest"
sha256sum "$interruption_manifest" | awk '{print $1}' > "$sandbox/.risu-manager/recovery-points/recovery-interrupt-001/manifest.sha256"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation update-interrupt-001 > "$sandbox/identity-mismatch.out"
assert_contains "$sandbox/.risu-manager/state/update-interrupt-001.json" '"restorationState":"RESTORE_FAILED"'
assert_contains "$sandbox/.risu-manager/instances/update-interrupt/metadata.json" "$target_sha"
sed -i 's/^instance_id=other-instance$/instance_id=update-interrupt/' "$interruption_manifest"
sha256sum "$interruption_manifest" | awk '{print $1}' > "$sandbox/.risu-manager/recovery-points/recovery-interrupt-001/manifest.sha256"
HOME="$sandbox" PREFIX="$prefix" PATH="$fake:$PATH" sh "$helper" operation update-interrupt-001 > "$sandbox/interruption.out"
assert_contains "$sandbox/interruption.out" '"state":"FAILED"'
assert_contains "$sandbox/interruption.out" '"restored":true'
assert_contains "$sandbox/interruption.out" '"restorationState":"RESTORED"'
assert_contains "$sandbox/.risu-manager/instances/update-interrupt/metadata.json" "$source_sha"

printf 'Termux update contract: PASS\n'
