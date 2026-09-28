#!/usr/bin/env sh
set -eu

installer="${1:-app/src/main/assets/termux-helper/install.sh}"
[ -r "$installer" ] || { echo "installer not found: $installer" >&2; exit 2; }

sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
sandbox=''
cleanup_all() { [ -z "$sandbox" ] || rm -rf "$sandbox"; }
trap cleanup_all EXIT INT TERM

fail() {
    printf 'install recovery contract: FAIL: %s\n' "$1" >&2
    exit 1
}
assert_file() { [ -e "$1" ] || fail "missing file: $1"; }
assert_no_file() { [ ! -e "$1" ] || fail "unexpected file: $1"; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail "$(basename "$1") lacks: $2"; }

make_fake_commands() {
    fake="$sandbox/fake-bin"
    mkdir -p "$fake" "$sandbox/prefix/lib/node_modules/npm/node_modules/node-gyp/bin"
    : > "$sandbox/pkg.log"
    : > "$sandbox/apt-get.log"
    : > "$sandbox/dpkg.log"
    : > "$sandbox/git.log"
    : > "$sandbox/build.log"
    : > "$sandbox/node.log"
    : > "$sandbox/npm.log"
    : > "$sandbox/mv.log"
    printf '%s\n' placeholder > "$sandbox/prefix/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js"

    cat > "$fake/uname" <<'EOF'
#!/usr/bin/env sh
[ "${1:-}" = "-m" ] && printf '%s\n' aarch64 || /bin/uname "$@"
EOF
    cat > "$fake/pkg" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$PKG_LOG"
if [ "${PKG_FAIL_MODE:-}" = always ] && [ "${1:-}" = install ]; then
    exit 1
fi
if [ "${PKG_FAIL_ONCE:-0}" = 1 ] && [ "${1:-}" = install ] && [ ! -e "$PKG_MARK" ]; then
    : > "$PKG_MARK"
    exit 1
fi
exit 0
EOF
    cat > "$fake/apt-get" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$APT_GET_LOG"
[ "${APT_GET_FAIL_MODE:-}" != always ]
EOF
    cat > "$fake/dpkg" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$DPKG_LOG"
[ "${DPKG_FAIL_MODE:-}" != always ]
EOF
    cat > "$fake/git" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$GIT_LOG"
if [ "${1:-}" = clone ]; then
    if [ "${GIT_CLONE_FAIL_ONCE:-0}" = 1 ] && [ ! -e "$GIT_MARK" ]; then
        : > "$GIT_MARK"
        exit 1
    fi
    target=''
    for arg in "$@"; do target="$arg"; done
    mkdir -p "$target/scripts/termux"
    printf '%s\n' '#!/usr/bin/env sh' > "$target/scripts/termux/build.sh"
    printf '%s\n' 'partial clone data' > "$target/clone-partial.txt"
    exit 0
fi
if [ "${1:-}" = -C ]; then
    case "${3:-}" in
        rev-parse) printf '%s\n' "${FAKE_COMMIT:-$REVISION}" ;;
        fetch|checkout) exit 0 ;;
    esac
fi
[ "${1:-}" = --version ] && printf '%s\n' 'git version 2.45.0' && exit 0
exit 0
EOF
    cat > "$fake/bash" <<'EOF'
#!/usr/bin/env sh
repo="$PWD"
count=0
if [ -r "$BUILD_COUNT" ]; then count=$(cat "$BUILD_COUNT"); fi
count=$((count + 1))
printf '%s\n' "$count" > "$BUILD_COUNT"
printf '%s\n' "$count:$repo" >> "$BUILD_LOG"
if [ "${BUILD_FAIL_MODE:-}" = always ] || { [ "${BUILD_FAIL_ONCE:-0}" = 1 ] && [ "$count" -eq 1 ]; }; then
    printf '%s\n' 'partial build output' > "$repo/partial-build.txt"
    exit 1
fi
[ ! -e "$repo/partial-build.txt" ] || exit 91
mkdir -p "$repo/server/node" "$repo/dist"
printf '%s\n' '<html>ok</html>' > "$repo/dist/index.html"
printf '%s\n' 'server entry point' > "$repo/server/node/server.cjs"
printf '%s\n' '{"version":"9.9.9"}' > "$repo/package.json"
exit 0
EOF
    cat > "$fake/node" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$NODE_LOG"
if [ "${1:-}" = -p ]; then printf '%s\n' '22.12.0'; exit 0; fi
exit 0
EOF
    cat > "$fake/curl" <<'EOF'
#!/usr/bin/env sh
output=''; url=''; previous=''
for arg in "$@"; do
    [ "$previous" = output ] && output="$arg"
    [ "$arg" = --output ] && previous=output || previous=''
    case "$arg" in https://*) url="$arg" ;; esac
done
[ -n "$output" ] || exit 2
case "$url" in
    *.sha256)
        if [ "${CURL_CHECKSUM_FAIL_ONCE:-0}" = 1 ] && [ ! -e "$CURL_MARK" ]; then
            : > "$CURL_MARK"
            exit 1
        fi
        printf '%s  archive.tar.gz\n' "$ARCHIVE_HASH" > "$output" ;;
    *) cp "$ARCHIVE_FIXTURE" "$output" ;;
esac
EOF
    cat > "$fake/npm" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$NPM_LOG"
if [ "${NPM_FAIL_ONCE:-0}" = 1 ] && [ "${1:-}" = ci ] && [ ! -e "$NPM_MARK" ]; then
    : > "$NPM_MARK"
    exit 1
fi
exit 0
EOF
    cat > "$fake/initdb" <<'EOF'
#!/usr/bin/env sh
directory=''; previous=''
for arg in "$@"; do
    [ "$previous" = d ] && directory="$arg"
    [ "$arg" = -D ] && previous=d || previous=''
done
mkdir -p "$directory"
printf '%s\n' 15 > "$directory/PG_VERSION"
EOF
    for database_command in pg_ctl createdb psql; do
        cat > "$fake/$database_command" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
    done
    cat > "$fake/openssl" <<'EOF'
#!/usr/bin/env sh
if [ "${1:-}" = rand ] && [ "${2:-}" = -hex ]; then
    printf '%048d\n' 1
    exit 0
fi
exec /usr/bin/openssl "$@"
EOF
    cat > "$fake/mv" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$MV_LOG"
case "${MV_FAIL_RECOVERY:-0}:$*" in
    1:*install-recovery*) exit 1 ;;
esac
exec /bin/mv "$@"
EOF
    chmod +x "$fake"/*
}

run_helper() {
    HOME="$sandbox" PREFIX="$sandbox/prefix" PATH="$fake:$saved_path" \
        PKG_LOG="$sandbox/pkg.log" PKG_MARK="$sandbox/pkg.failed" \
        APT_GET_LOG="$sandbox/apt-get.log" DPKG_LOG="$sandbox/dpkg.log" \
        GIT_LOG="$sandbox/git.log" GIT_MARK="$sandbox/git.failed" BUILD_LOG="$sandbox/build.log" \
        BUILD_COUNT="$sandbox/build.count" NODE_LOG="$sandbox/node.log" \
        NPM_LOG="$sandbox/npm.log" NPM_MARK="$sandbox/npm.failed" \
        CURL_MARK="$sandbox/curl.failed" \
        MV_LOG="$sandbox/mv.log" REVISION="$sha" \
        sh "$helper" _install "$operation" "$instance" pocketrisu "$sha" >/dev/null 2>&1
}
run_archive_helper() {
    HOME="$sandbox" PREFIX="$sandbox/prefix" PATH="$fake:$saved_path" \
        PKG_LOG="$sandbox/pkg.log" PKG_MARK="$sandbox/pkg.failed" \
        APT_GET_LOG="$sandbox/apt-get.log" DPKG_LOG="$sandbox/dpkg.log" \
        GIT_LOG="$sandbox/git.log" GIT_MARK="$sandbox/git.failed" BUILD_LOG="$sandbox/build.log" \
        BUILD_COUNT="$sandbox/build.count" NODE_LOG="$sandbox/node.log" \
        NPM_LOG="$sandbox/npm.log" NPM_MARK="$sandbox/npm.failed" \
        CURL_MARK="$sandbox/curl.failed" \
        MV_LOG="$sandbox/mv.log" REVISION="$sha" \
        ARCHIVE_FIXTURE="$archive_fixture" ARCHIVE_HASH="$archive_hash" \
        sh "$helper" _install "$operation" "$instance" haejeok-risuai-termux "$sha" \
        "$archive_url" "$archive_sha" "$checksum_url" >/dev/null 2>&1
}
run_expect_failure() { if run_helper; then fail "expected $operation to fail"; fi; }

new_case() {
    sandbox=$(mktemp -d)
    helper="$sandbox/.risu-manager/bin/risu-manager"
    operation="$1"
    instance="$2"
    make_fake_commands
    HOME="$sandbox" sh "$installer" >/dev/null
    saved_path="$PATH"
    fake="$sandbox/fake-bin"
}
cleanup_case() { rm -rf "$sandbox"; }

prepare_archive() {
    archive_root="$sandbox/archive-root"
    mkdir -p "$archive_root/dist" "$archive_root/server/node" "$archive_root/deploy/termux"
    printf '%s\n' '<html>ok</html>' > "$archive_root/dist/index.html"
    printf '%s\n' 'server entry point' > "$archive_root/server/node/server.cjs"
    printf '%s\n' '{"version":"8.8.8"}' > "$archive_root/package.json"
    printf '%s\n' '{}' > "$archive_root/package-lock.json"
    printf '%s\n' 'b1' > "$archive_root/RELEASE"
    printf '%s\n' '#!/usr/bin/env sh' > "$archive_root/deploy/termux/haejeok.sh"
    archive_fixture="$sandbox/release-fixture.tar.gz"
    (cd "$archive_root" && tar -czf "$archive_fixture" .)
    archive_hash=$(sha256sum "$archive_fixture" | awk '{print $1}')
    archive_url='https://github.com/nevaeh5379/HaejeokRisuai/releases/download/b1/RisuAI-Termux-b1.tar.gz'
    checksum_url="$archive_url.sha256"
}

new_case recovery-once install-recovery-one
BUILD_FAIL_ONCE=1 run_helper || fail 'one-time project failure did not recover'
state="$sandbox/.risu-manager/state/$operation.json"
backup="$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1"
assert_contains "$state" '"state":"SUCCEEDED"'
assert_contains "$state" '"recoveryBackup":"'
assert_contains "$sandbox/.risu-manager/logs/$operation.log" 'First failed install attempt preserved at'
assert_file "$backup/repo/partial-build.txt"
assert_file "$backup/repo/clone-partial.txt"
assert_no_file "$sandbox/.risu-manager/instances/$instance/repo/partial-build.txt"
assert_file "$sandbox/.risu-manager/instances/$instance/metadata.json"
cleanup_case

new_case recovery-exhausted install-recovery-two
BUILD_FAIL_MODE=always run_expect_failure
state="$sandbox/.risu-manager/state/$operation.json"
backup="$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1"
assert_contains "$state" '"state":"FAILED"'
assert_contains "$state" '"code":"BUILD_FAILED"'
assert_contains "$state" 'Recovery exhausted after one clean retry'
assert_file "$backup/repo/partial-build.txt"
[ "$(wc -l < "$sandbox/build.log" | tr -d ' ')" = 2 ] || fail 'project retry was not bounded at two attempts'
cleanup_case

# A clone failure that creates no destination still gets a retained recovery
# record, then the same immutable source is cloned once more.
new_case clone-recovery install-clone-retry
GIT_CLONE_FAIL_ONCE=1 run_helper || fail 'empty clone failure did not recover'
state="$sandbox/.risu-manager/state/$operation.json"
backup="$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1"
assert_contains "$state" '"state":"SUCCEEDED"'
assert_file "$backup/failure.txt"
assert_file "$sandbox/.risu-manager/instances/$instance/metadata.json"
cleanup_case

# Archive npm dependency failure is recoverable. The extracted repository is
# moved aside, then a fresh archive extraction is installed successfully.
new_case archive-npm-recovery install-archive-npm
prepare_archive
archive_sha="$archive_hash"
NPM_FAIL_ONCE=1 run_archive_helper || fail 'archive npm failure did not recover'
state="$sandbox/.risu-manager/state/$operation.json"
backup="$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1"
assert_contains "$state" '"state":"SUCCEEDED"'
assert_file "$backup/repo/server/node/server.cjs"
assert_file "$sandbox/.risu-manager/instances/$instance/repo/server/node/server.cjs"
[ "$(grep -c '^ci ' "$sandbox/npm.log")" = 2 ] || fail 'archive npm recovery was not bounded at two ci attempts'
cleanup_case

# A checksum download interruption is a bounded project failure and can be
# retried with the same verified archive URL and digest.
new_case archive-checksum-download-recovery install-archive-checksum-download
prepare_archive
archive_sha="$archive_hash"
CURL_CHECKSUM_FAIL_ONCE=1 run_archive_helper || fail 'checksum download failure did not recover'
state="$sandbox/.risu-manager/state/$operation.json"
backup="$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1"
assert_contains "$state" '"state":"SUCCEEDED"'
assert_file "$backup/release.tar.gz"
cleanup_case

# Archive checksum mismatch is a trust failure: retain downloaded evidence but
# never move it into a recovery backup or run a second archive attempt.
new_case archive-checksum-failure install-archive-checksum
prepare_archive
archive_sha=0000000000000000000000000000000000000000000000000000000000000000
if run_archive_helper; then fail 'archive checksum mismatch unexpectedly succeeded'; fi
state="$sandbox/.risu-manager/state/$operation.json"
assert_contains "$state" '"code":"CHECKSUM_MISMATCH"'
assert_no_file "$sandbox/.risu-manager/backups/install-recovery/$operation"
if grep -Ev '^--version$' "$sandbox/npm.log" | grep -q .; then
    fail 'checksum failure unexpectedly ran an npm command other than version diagnostics'
fi
cleanup_case

new_case package-recovery install-package-retry
PKG_FAIL_MODE=always run_helper || fail 'direct apt package recovery did not succeed'
pkg_installs=$(grep -c '^install ' "$sandbox/pkg.log")
[ "$pkg_installs" = 1 ] || fail "expected one initial pkg install, got $pkg_installs"
assert_contains "$sandbox/dpkg.log" '--force-confold --configure -a'
assert_contains "$sandbox/apt-get.log" 'update'
assert_contains "$sandbox/apt-get.log" '-o Dpkg::Options::=--force-confold --fix-broken install -y'
assert_contains "$sandbox/apt-get.log" '-o Dpkg::Options::=--force-confold full-upgrade -y'
assert_contains "$sandbox/apt-get.log" '-o Dpkg::Options::=--force-confold install -y git nodejs-lts'
cleanup_case

new_case package-exhausted install-package-exhausted
PKG_FAIL_MODE=always APT_GET_FAIL_MODE=always run_expect_failure
state="$sandbox/.risu-manager/state/$operation.json"
assert_contains "$state" '"code":"DEPENDENCY_INSTALL_FAILED"'
[ "$(grep -c '^install ' "$sandbox/pkg.log")" = 1 ] || fail 'pkg wrapper was retried during direct apt recovery'
[ "$(wc -l < "$sandbox/apt-get.log" | tr -d ' ')" = 1 ] || fail 'apt recovery continued after update failed'
[ ! -e "$sandbox/build.count" ] || fail 'project started after package recovery exhaustion'
cleanup_case

new_case revision-mismatch install-trust-failure
FAKE_COMMIT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb run_expect_failure
state="$sandbox/.risu-manager/state/$operation.json"
assert_contains "$state" '"code":"REVISION_MISMATCH"'
assert_no_file "$sandbox/.risu-manager/backups/install-recovery/$operation"
[ ! -e "$sandbox/build.count" ] || fail 'trust failure unexpectedly ran the build'
cleanup_case

new_case preexisting-data install-preexisting
protected="$sandbox/.risu-manager/instances/$instance"
mkdir -p "$protected/repo" "$protected/save"
printf '%s\n' 'keep me' > "$protected/repo/user-file.txt"
printf '%s\n' 'user data' > "$protected/save/user.bin"
run_expect_failure
state="$sandbox/.risu-manager/state/$operation.json"
assert_contains "$state" '"code":"INSTANCE_ALREADY_EXISTS"'
assert_contains "$protected/repo/user-file.txt" 'keep me'
assert_contains "$protected/save/user.bin" 'user data'
[ ! -s "$sandbox/pkg.log" ] || fail 'preexisting data was checked after package mutation'
cleanup_case

new_case backup-failure install-backup-failure
BUILD_FAIL_ONCE=1 MV_FAIL_RECOVERY=1 run_expect_failure
state="$sandbox/.risu-manager/state/$operation.json"
assert_contains "$state" '"code":"BUILD_FAILED"'
assert_contains "$state" 'could not safely preserve the failed attempt'
assert_no_file "$sandbox/.risu-manager/backups/install-recovery/$operation/attempt-1/repo"
assert_file "$sandbox/.risu-manager/instances/$instance/repo/partial-build.txt"
[ "$(wc -l < "$sandbox/build.log" | tr -d ' ')" = 1 ] || fail 'retry started after backup preservation failure'
cleanup_case

printf '%s\n' 'Termux install recovery contract: PASS'
