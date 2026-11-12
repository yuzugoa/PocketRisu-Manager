#!/data/data/com.termux/files/usr/bin/sh
# Risu Manager-owned bootstrap. This file is shipped in the APK and is never
# populated from a remote manifest.
set -eu

ROOT="${HOME:?}/.risu-manager"
TARGET="$ROOT/bin/risu-manager"
TMP="$TARGET.tmp.$$"
EXPORTER="$ROOT/bin/risu-backup-export.cjs"
SERVER="$ROOT/bin/risu-backup-server.cjs"
PATCHER="$ROOT/bin/risu-server-patch.cjs"

bootstrap_secure_dir() {
    bootstrap_path="$1"
    if [ -L "$bootstrap_path" ] || { [ -e "$bootstrap_path" ] && [ ! -d "$bootstrap_path" ]; }; then
        return 1
    fi
    [ -d "$bootstrap_path" ] || mkdir "$bootstrap_path" || return 1
    [ -d "$bootstrap_path" ] && [ ! -L "$bootstrap_path" ] || return 1
    chmod 700 "$bootstrap_path" || return 1
}

[ -d "$HOME" ] && [ ! -L "$HOME" ] || {
    printf '%s\n' '{"ok":false,"code":"UNSAFE_MANAGER_PATH","message":"Termux HOME must be a real private directory."}'
    exit 1
}
bootstrap_secure_dir "$ROOT" &&
    bootstrap_secure_dir "$ROOT/bin" &&
    bootstrap_secure_dir "$ROOT/instances" &&
    bootstrap_secure_dir "$ROOT/logs" &&
    bootstrap_secure_dir "$ROOT/backups" &&
    bootstrap_secure_dir "$ROOT/state" &&
    bootstrap_secure_dir "$ROOT/acme" &&
    bootstrap_secure_dir "$ROOT/locks" &&
    bootstrap_secure_dir "$ROOT/clone-staging" &&
    bootstrap_secure_dir "$ROOT/update-staging" &&
    bootstrap_secure_dir "$ROOT/recovery-points" || {
        printf '%s\n' '{"ok":false,"code":"UNSAFE_MANAGER_PATH","message":"Manager storage contains a symlink, non-directory, or non-private path."}'
        exit 1
    }

cat > "$EXPORTER.tmp.$$" <<'RISU_BACKUP_EXPORTER'
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const [repo, output] = process.argv.slice(2);
if (!repo || !output) throw new Error('usage: exporter <repo> <output>');
const Database = require(path.join(repo, 'node_modules', 'better-sqlite3'));
const dbPath = path.join(repo, 'save', 'risuai.db');
const db = new Database(dbPath, { readonly: true, fileMustExist: true });
const marker = Buffer.from('\x00RISUCHUNKED\x00', 'binary');
const rowFor = db.prepare('SELECT value FROM kv WHERE key = ?');
const keysFor = db.prepare('SELECT key FROM kv WHERE key LIKE ? ORDER BY key');
let chunkRows = null;

function getValue(key) {
  const row = rowFor.get(key);
  if (!row) return null;
  const value = Buffer.from(row.value);
  if (!value.equals(marker)) return value;
  if (!chunkRows) {
    const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('chunks','manifest_chunks')").all();
    if (tables.length !== 2) throw new Error(`chunk marker without chunk tables: ${key}`);
    chunkRows = db.prepare('SELECT m.seq, c.data FROM manifest_chunks m JOIN chunks c ON c.hash=m.hash WHERE m.manifest_key=? ORDER BY m.seq');
  }
  const chunks = chunkRows.all(key);
  if (chunks.length === 0) throw new Error(`chunk marker without manifest: ${key}`);
  for (let i = 0; i < chunks.length; i++) {
    if (chunks[i].seq !== i || !chunks[i].data) throw new Error(`incomplete chunk manifest: ${key}`);
  }
  return Buffer.concat(chunks.map(row => Buffer.from(row.data)));
}

function decodeSave(raw) {
  const magic = Buffer.from([0,82,73,83,85,83,65,86,69,0]);
  if (raw.length < 11 || !raw.subarray(0, 10).equals(magic)) throw new Error('database.risudat has an unsupported header');
  const { unpack } = require(path.join(repo, 'node_modules', 'msgpackr'));
  if (raw[10] === 8) {
    const { decompressSync } = require(path.join(repo, 'node_modules', 'fflate'));
    return unpack(decompressSync(raw.subarray(11)));
  }
  if (raw[10] === 7) return unpack(raw.subarray(11));
  if (raw[10] === 9) return unpack(zlib.gunzipSync(raw.subarray(11)));
  throw new Error(`database.risudat encoding ${raw[10]} cannot be merged safely`);
}

function encodeSave(value) {
  const { Packr } = require(path.join(repo, 'node_modules', 'msgpackr'));
  const { compressSync } = require(path.join(repo, 'node_modules', 'fflate'));
  const packed = new Packr({ useRecords: false }).pack(value);
  return Buffer.concat([Buffer.from([0,82,73,83,85,83,65,86,69,0,8]), Buffer.from(compressSync(packed))]);
}

function pluginKey(encoded) {
  const base64 = encoded.replace(/-/g, '+').replace(/_/g, '/');
  return Buffer.from(base64, 'base64').toString('utf8');
}

let database = getValue('database/database.bin');
if (!database) throw new Error('database/database.bin is missing');
const pluginRows = keysFor.all('plugin-storage/%').map(row => row.key)
  .filter(key => key !== 'plugin-storage/__migrated__');
if (pluginRows.length) {
  const decoded = decodeSave(database);
  const merged = {};
  for (const key of pluginRows) {
    const value = getValue(key);
    if (!value) throw new Error(`plugin storage value is missing: ${key}`);
    Object.defineProperty(merged, pluginKey(key.slice('plugin-storage/'.length)), {
      value: JSON.parse(value.toString('utf8')), enumerable: true, writable: true, configurable: true,
    });
  }
  const fromDatabase = decoded.pluginCustomStorage;
  if (fromDatabase && typeof fromDatabase === 'object') {
    for (const key of Object.keys(fromDatabase)) {
      Object.defineProperty(merged, key, {
        value: Object.getOwnPropertyDescriptor(fromDatabase, key).value,
        enumerable: true, writable: true, configurable: true,
      });
    }
  }
  decoded.pluginCustomStorage = merged;
  database = encodeSave(decoded);
}

const fd = fs.openSync(output, 'wx', 0o600);
function writeBuffer(buffer) { fs.writeSync(fd, buffer); }
function header(name, size) {
  const encoded = Buffer.from(name, 'utf8');
  if (!encoded.length || encoded.length > 65536 || size < 0 || size > 0xffffffff) throw new Error(`invalid backup entry: ${name}`);
  const h = Buffer.allocUnsafe(8 + encoded.length);
  h.writeUInt32LE(encoded.length, 0); encoded.copy(h, 4); h.writeUInt32LE(size, 4 + encoded.length);
  writeBuffer(h);
}
function bufferEntry(name, value) { header(name, value.length); writeBuffer(value); }
function fileEntry(name, file) {
  const stat = fs.statSync(file);
  if (!stat.isFile()) throw new Error(`not a regular file: ${file}`);
  header(name, stat.size);
  const source = fs.openSync(file, 'r');
  try {
    const buf = Buffer.allocUnsafe(256 * 1024);
    let count;
    while ((count = fs.readSync(source, buf, 0, buf.length, null)) > 0) fs.writeSync(fd, buf, 0, count);
  } finally { fs.closeSync(source); }
}
function safeInlayId(id) {
  return typeof id === 'string' && id.length > 0 && !id.includes('\0') &&
    !id.includes('/') && !id.includes('\\') && id !== '.' && id !== '..';
}

try {
  const entries = [];
  for (const { key } of keysFor.all('assets/%')) {
    const name = path.basename(key);
    if (!name || name.includes('/') || name.includes('\\')) throw new Error(`invalid asset key: ${key}`);
    entries.push({ sort: key, name, value: getValue(key) });
  }
  const seenCold = new Set();
  for (const { key } of keysFor.all('coldstorage/%')) {
    let id = key.slice('coldstorage/'.length).replace(/\.json$/, '');
    if (!id || id.includes('/') || seenCold.has(id)) continue;
    seenCold.add(id);
    const value = getValue(key);
    let object;
    try { object = JSON.parse(zlib.gunzipSync(value).toString('utf8')); }
    catch (_) { object = JSON.parse(value.toString('utf8')); }
    const plain = Buffer.from(JSON.stringify(object), 'utf8');
    entries.push({ sort: `coldstorage/${id}.json`, name: `coldstorage/${id}.json`, value: plain });
  }
  for (const { key } of keysFor.all('inlay_meta/%')) entries.push({ sort: key, name: key, value: getValue(key) });
  const inlayDir = path.join(repo, 'save', 'inlays');
  if (fs.existsSync(inlayDir)) {
    const rawInlays = [];
    for (const name of fs.readdirSync(inlayDir)) {
      const file = path.join(inlayDir, name);
      const stat = fs.statSync(file);
      if (!stat.isFile() || name === '.migrated_to_fs' || name.endsWith('.meta.json')) continue;
      const dot = name.lastIndexOf('.');
      const id = dot > 0 ? name.slice(0, dot) : name;
      if (safeInlayId(id)) rawInlays.push({ id, name, file });
    }
    for (const inlay of rawInlays) {
      entries.push({ sort: `inlay/${inlay.id}`, name: `inlay/${inlay.name}`, file: inlay.file });
      const sidecar = path.join(inlayDir, `${inlay.id}.meta.json`);
      if (fs.existsSync(sidecar)) entries.push({ sort: `inlay_sidecar/${inlay.id}`, name: `inlay_sidecar/${inlay.id}`, file: sidecar });
    }
  }
  entries.sort((a, b) => a.sort.localeCompare(b.sort));
  const entryNames = new Set();
  for (const entry of entries) {
    if (entryNames.has(entry.name) || entry.name === 'database.risudat') throw new Error(`duplicate backup entry: ${entry.name}`);
    entryNames.add(entry.name);
    if (entry.value === null) throw new Error(`backup value disappeared: ${entry.name}`);
    if (entry.file) fileEntry(entry.name, entry.file); else bufferEntry(entry.name, entry.value);
  }
  bufferEntry('database.risudat', database);
} catch (error) {
  fs.closeSync(fd); fs.rmSync(output, { force: true }); db.close(); throw error;
}
fs.fsyncSync(fd); fs.closeSync(fd); db.close();
RISU_BACKUP_EXPORTER

cat > "$SERVER.tmp.$$" <<'RISU_BACKUP_SERVER'
'use strict';
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const [file, expectedToken, portFile, pidFile, tokenFile] = process.argv.slice(2);
if (!file || !/^[0-9a-f]{64}$/.test(expectedToken)) process.exit(2);
function clean() {
  for (const p of [file, portFile, pidFile, tokenFile]) { try { fs.unlinkSync(p); } catch (_) {} }
}
const server = http.createServer((req, res) => {
  const supplied = String(req.headers.authorization || '').replace(/^Bearer /, '');
  const valid = supplied.length === expectedToken.length && crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(expectedToken));
  if (req.method !== 'GET' || req.url !== '/backup' || !valid) { res.writeHead(404); res.end(); return; }
  const stat = fs.statSync(file);
  res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': stat.size, 'X-Risu-SHA256': process.env.RISU_BACKUP_SHA256 });
  const stream = fs.createReadStream(file);
  stream.on('error', () => res.destroy());
  stream.pipe(res);
});
server.listen(0, '127.0.0.1', () => fs.writeFileSync(portFile, String(server.address().port), { mode: 0o600 }));
setTimeout(() => { server.close(); clean(); }, 15 * 60 * 1000).unref();
process.on('SIGTERM', () => server.close(() => { clean(); process.exit(0); }));
RISU_BACKUP_SERVER

cat > "$PATCHER.tmp.$$" <<'RISU_SERVER_PATCHER'
'use strict';
const fs = require('fs');
const crypto = require('crypto');
const file = process.argv[2];
if (!file) throw new Error('server entry point is required');
const marker = '// RISU_MANAGER_RUNTIME_CONFIG_V1';
let source = fs.readFileSync(file, 'utf8');
if (source.includes(marker)) {
  const expected = [
    "const SAVE_INTERVAL = risuManagerInt('RISU_SAVE_INTERVAL_MS'",
    'express.json({ limit: RISU_BODY_LIMIT })',
    'express.text({ limit: RISU_BODY_LIMIT })',
    'server.listen(port, RISU_BIND_HOST, () => {',
  ];
  if (!expected.every(part => source.includes(part)) || source.split('server.listen(port, RISU_BIND_HOST, () => {').length - 1 !== 2) {
    throw new Error('managed patch marker exists but the patched structure is incomplete');
  }
  process.exit(0);
}
function replaceExactly(search, replacement, expectedCount = 1) {
  const count = source.split(search).length - 1;
  if (count !== expectedCount) {
    throw new Error(`managed patch context mismatch (${JSON.stringify(search)}: expected ${expectedCount}, found ${count})`);
  }
  source = source.split(search).join(replacement);
}
replaceExactly(
  'const SAVE_INTERVAL = 5000;',
  `${marker}\nfunction risuManagerInt(name, fallback, min, max) {\n    const raw = process.env[name];\n    if (raw === undefined || raw === '') return fallback;\n    const value = Number(raw);\n    if (!Number.isSafeInteger(value) || value < min || value > max) {\n        throw new Error(\`Invalid \${name}: expected integer \${min}..\${max}\`);\n    }\n    return value;\n}\nconst SAVE_INTERVAL = risuManagerInt('RISU_SAVE_INTERVAL_MS', 5000, 1000, 30000);\nconst RISU_BODY_LIMIT_MB = risuManagerInt('RISU_BODY_LIMIT_MB', 100, 1, 1024);\nconst RISU_BODY_LIMIT = String(RISU_BODY_LIMIT_MB) + 'mb';\nconst RISU_BIND_HOST = process.env.RISU_BIND_HOST === '0.0.0.0' ? '0.0.0.0' : '127.0.0.1';`,
);
replaceExactly("app.use(express.json({ limit: '100mb' }));", 'app.use(express.json({ limit: RISU_BODY_LIMIT }));');
replaceExactly("app.use(express.text({ limit: '100mb' }));", 'app.use(express.text({ limit: RISU_BODY_LIMIT }));');
replaceExactly('server.listen(port, () => {', 'server.listen(port, RISU_BIND_HOST, () => {', 2);
const temp = `${file}.risu-manager.tmp-${process.pid}`;
const sidecar = `${file}.risu-manager-patch`;
const sidecarTemp = `${sidecar}.tmp-${process.pid}`;
const originalHash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
fs.writeFileSync(temp, source, { mode: 0o600, flag: 'wx' });
fs.writeFileSync(sidecarTemp, `v1 ${originalHash}\n`, { mode: 0o600, flag: 'wx' });
fs.renameSync(temp, file);
fs.renameSync(sidecarTemp, sidecar);
RISU_SERVER_PATCHER

chmod 600 "$EXPORTER.tmp.$$" "$SERVER.tmp.$$" "$PATCHER.tmp.$$"
mv -f "$EXPORTER.tmp.$$" "$EXPORTER"
mv -f "$SERVER.tmp.$$" "$SERVER"
mv -f "$PATCHER.tmp.$$" "$PATCHER"

cat > "$TMP" <<'RISU_MANAGER_HELPER'
#!/data/data/com.termux/files/usr/bin/sh
set -u

HELPER_VERSION="26"
ROOT="${HOME:?}/.risu-manager"
BIN="$ROOT/bin/risu-manager"
INSTANCES="$ROOT/instances"
STATE="$ROOT/state"
LOGS="$ROOT/logs"
LOCKS="$ROOT/locks"
CLONE_ROOT="$ROOT/clone-staging"
UPDATE_ROOT="$ROOT/update-staging"
RECOVERY_ROOT="$ROOT/recovery-points"
EXPORTER="$ROOT/bin/risu-backup-export.cjs"
SERVER="$ROOT/bin/risu-backup-server.cjs"
PATCHER="$ROOT/bin/risu-server-patch.cjs"
ACME_ROOT="$ROOT/acme"
ACME_VERSION="3.1.4"
ACME_ARCHIVE_SHA256="e5f8e187bbf5251e0cd8891f2622daab9850366bd17bea9f92c2fe2ee091fd32"
ACME_ARCHIVE_URL="https://github.com/acmesh-official/acme.sh/archive/refs/tags/3.1.4.tar.gz"

secure_manager_dir() {
    secure_path="$1"
    if [ -L "$secure_path" ] || { [ -e "$secure_path" ] && [ ! -d "$secure_path" ]; }; then
        return 1
    fi
    [ -d "$secure_path" ] || mkdir "$secure_path" || return 1
    [ -d "$secure_path" ] && [ ! -L "$secure_path" ] || return 1
    chmod 700 "$secure_path" || return 1
}

[ -d "$HOME" ] && [ ! -L "$HOME" ] && secure_manager_dir "$ROOT" &&
    secure_manager_dir "$ROOT/bin" && secure_manager_dir "$INSTANCES" &&
    secure_manager_dir "$STATE" && secure_manager_dir "$LOGS" &&
    secure_manager_dir "$ROOT/backups" && secure_manager_dir "$ACME_ROOT" &&
    secure_manager_dir "$LOCKS" && secure_manager_dir "$CLONE_ROOT" &&
    secure_manager_dir "$UPDATE_ROOT" && secure_manager_dir "$RECOVERY_ROOT" || {
        printf '%s\n' '{"ok":false,"code":"UNSAFE_MANAGER_PATH","message":"Manager storage contains a symlink, non-directory, or non-private path."}'
        exit 1
    }

ensure_update_storage() {
    secure_manager_dir "$ROOT" && secure_manager_dir "$UPDATE_ROOT" && secure_manager_dir "$RECOVERY_ROOT"
}

json_escape() {
    printf '%s' "$1" | LC_ALL=C tr -d '\000-\010\013\014\016-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n\r\t' '   '
}

emit_error() {
    code="$(json_escape "$1")"
    message="$(json_escape "$2")"
    printf '{"ok":false,"code":"%s","message":"%s"}\n' "$code" "$message"
}

valid_identifier() {
    value="$1"
    case "$value" in
        ''|*[!a-z0-9-]*|-*|*-) return 1 ;;
    esac
    [ "${#value}" -ge 3 ] && [ "${#value}" -le 64 ]
}

valid_operation_id() {
    value="$1"
    case "$value" in
        ''|*[!A-Za-z0-9_-]*) return 1 ;;
    esac
    [ "${#value}" -ge 8 ] && [ "${#value}" -le 80 ]
}

valid_revision() {
    value="$1"
    case "$value" in
        ''|-*|*'..'*|*'@{'*|*'\\'*|*[!A-Za-z0-9._/-]*) return 1 ;;
    esac
    [ "${#value}" -le 128 ]
}

valid_commit() {
    value="$1"
    [ "${#value}" -eq 40 ] || return 1
    case "$value" in *[!0-9a-f]*) return 1 ;; esac
}

valid_uint() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
}

valid_config_values() {
    port="$1"; bind_scope="$2"; protocol="$3"; body_mb="$4"; save_seconds="$5"
    snapshot_minutes="$6"; snapshot_count="$7"; snapshot_mb="$8"
    valid_uint "$port" && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || return 1
    case "$bind_scope" in LOOPBACK|LOCAL_NETWORK) ;; *) return 1 ;; esac
    case "$protocol" in HTTP|HTTPS) ;; *) return 1 ;; esac
    valid_uint "$body_mb" && [ "$body_mb" -ge 1 ] && [ "$body_mb" -le 1024 ] || return 1
    valid_uint "$save_seconds" && [ "$save_seconds" -ge 1 ] && [ "$save_seconds" -le 30 ] || return 1
    valid_uint "$snapshot_minutes" && [ "$snapshot_minutes" -ge 1 ] && [ "$snapshot_minutes" -le 1440 ] || return 1
    valid_uint "$snapshot_count" && [ "$snapshot_count" -ge 1 ] && [ "$snapshot_count" -le 100 ] || return 1
    valid_uint "$snapshot_mb" && [ "$snapshot_mb" -ge 10 ] && [ "$snapshot_mb" -le 51200 ] || return 1
}

config_file() { printf '%s/manager/server.conf' "$(instance_dir "$1")"; }
applied_config_file() { printf '%s/manager/applied.conf' "$(instance_dir "$1")"; }

write_config_file() (
    destination="$1"; shift
    port="$1"; bind_scope="$2"; protocol="$3"; body_mb="$4"; save_seconds="$5"
    snapshot_minutes="$6"; snapshot_count="$7"; snapshot_mb="$8"
    directory="$(dirname "$destination")"; temporary="$destination.tmp.$$"
    umask 077
    mkdir -p "$directory"
    {
        printf 'schema=1\n'
        printf 'port=%s\n' "$port"
        printf 'bind_scope=%s\n' "$bind_scope"
        printf 'protocol=%s\n' "$protocol"
        printf 'body_limit_mb=%s\n' "$body_mb"
        printf 'save_interval_seconds=%s\n' "$save_seconds"
        printf 'snapshot_interval_minutes=%s\n' "$snapshot_minutes"
        printf 'snapshot_retention_count=%s\n' "$snapshot_count"
        printf 'snapshot_max_mb=%s\n' "$snapshot_mb"
    } > "$temporary"
    chmod 600 "$temporary"
    mv -f "$temporary" "$destination"
)

ensure_config() {
    file="$(config_file "$1")"
    [ -r "$file" ] || write_config_file "$file" 6001 LOOPBACK HTTP 100 5 5 20 500
}

config_value() {
    key="$1"; file="$2"
    sed -n "s/^${key}=//p" "$file" | sed -n '1p'
}

load_config() {
    ensure_config "$1"
    loaded_config_file="$(config_file "$1")"
    [ "$(wc -l < "$loaded_config_file" | tr -d ' ')" = "9" ] || return 1
    [ "$(config_value schema "$loaded_config_file")" = "1" ] || return 1
    [ "$(grep -Ec '^(schema|port|bind_scope|protocol|body_limit_mb|save_interval_seconds|snapshot_interval_minutes|snapshot_retention_count|snapshot_max_mb)=' "$loaded_config_file")" = "9" ] || return 1
    cfg_port="$(config_value port "$loaded_config_file")"
    cfg_bind_scope="$(config_value bind_scope "$loaded_config_file")"
    cfg_protocol="$(config_value protocol "$loaded_config_file")"
    cfg_body_mb="$(config_value body_limit_mb "$loaded_config_file")"
    cfg_save_seconds="$(config_value save_interval_seconds "$loaded_config_file")"
    cfg_snapshot_minutes="$(config_value snapshot_interval_minutes "$loaded_config_file")"
    cfg_snapshot_count="$(config_value snapshot_retention_count "$loaded_config_file")"
    cfg_snapshot_mb="$(config_value snapshot_max_mb "$loaded_config_file")"
    valid_config_values "$cfg_port" "$cfg_bind_scope" "$cfg_protocol" "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"
}

config_json_fields() {
    printf '"port":%s,"bindScope":"%s","protocol":"%s","bodyLimitMb":%s,"saveIntervalSeconds":%s,"snapshotIntervalMinutes":%s,"snapshotRetentionCount":%s,"snapshotMaxMb":%s' \
        "$cfg_port" "$cfg_bind_scope" "$cfg_protocol" "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"
}

instance_dir() {
    printf '%s/%s' "$INSTANCES" "$1"
}

state_file() {
    printf '%s/%s.json' "$STATE" "$1"
}

tls_state_file() {
    printf '%s/manager/tls.conf' "$(instance_dir "$1")"
}

operation_pid_file() {
    printf '%s/%s.pid' "$STATE" "$1"
}

write_state() {
    operation_id="$1"
    payload="$2"
    file="$(state_file "$operation_id")"
    tmp="$file.tmp.$$"
    umask 077
    printf '%s\n' "$payload" > "$tmp"
    chmod 600 "$tmp"
    sync -f "$tmp" 2>/dev/null || sync "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$file"
    sync -f "$file" 2>/dev/null || sync "$file" 2>/dev/null || true
}

append_operation_log() {
    operation_id="$1"
    level="$2"
    message="$3"
    mkdir -p "$LOGS"
    umask 077
    printf '%s [%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date)" "$level" "$message" >> "$LOGS/$operation_id.log"
    chmod 600 "$LOGS/$operation_id.log" 2>/dev/null || true
}

valid_recovery_id() {
    value="$1"
    case "$value" in ''|*[!A-Za-z0-9_-]*) return 1 ;; esac
    [ "${#value}" -ge 8 ] && [ "${#value}" -le 80 ]
}

update_record_file() { printf '%s/%s.update' "$STATE" "$1"; }
update_operation_dir() { printf '%s/%s' "$UPDATE_ROOT" "$1"; }
recovery_dir() { printf '%s/%s' "$RECOVERY_ROOT" "$1"; }
rollback_record_file() { printf '%s/%s.rollback' "$STATE" "$1"; }
rollback_operation_dir() { printf '%s/%s' "$UPDATE_ROOT" "$1"; }
recovery_clone_binding_file() { printf '%s/%s.recovery-clone' "$STATE" "$1"; }

write_recovery_clone_binding() (
    operation_id="$1"; source_id="$2"; recovery_id="$3"; target_id="$4"; target_port="$5"
    file="$(recovery_clone_binding_file "$operation_id")"; tmp="$file.tmp.$$"
    {
        printf 'schema=1\noperation_id=%s\nsource_id=%s\nrecovery_id=%s\ntarget_id=%s\ntarget_port=%s\n' \
            "$operation_id" "$source_id" "$recovery_id" "$target_id" "$target_port"
    } > "$tmp" && chmod 600 "$tmp" && mv -f "$tmp" "$file"
)

recovery_clone_binding_valid() (
    file="$1"; [ -f "$file" ] && [ ! -L "$file" ] || return 1
    [ "$(record_value schema "$file")" = 1 ] || return 1
    valid_operation_id "$(record_value operation_id "$file")" && valid_identifier "$(record_value source_id "$file")" &&
        valid_recovery_id "$(record_value recovery_id "$file")" && valid_identifier "$(record_value target_id "$file")" &&
        valid_uint "$(record_value target_port "$file")"
)

record_value() {
    key="$1"; file="$2"
    sed -n "s/^${key}=//p" "$file" | sed -n '1p'
}

write_update_record() (
    operation_id="$1"; instance_id="$2"; target_sha="$3"; recovery_id="$4"; retention="$5"
    update_stage="$6"; source_sha="$7"; was_running="$8"; recovery_verified="$9"
    shift 9
    recovery_required="$1"; completion_ms="$2"; expiration_ms="$3"
    file="$(update_record_file "$operation_id")"; tmp="$file.tmp.$$"
    umask 077
    {
        printf 'schema=1\noperation_id=%s\ninstance_id=%s\nvariant_id=pocketrisu\ntarget_sha=%s\nrecovery_id=%s\nretention_seconds=%s\n' \
            "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention"
        printf 'stage=%s\nsource_sha=%s\nwas_running=%s\nrecovery_verified=%s\nrecovery_required=%s\ncompletion_ms=%s\nexpiration_ms=%s\n' \
            "$update_stage" "$source_sha" "$was_running" "$recovery_verified" "$recovery_required" "$completion_ms" "$expiration_ms"
    } > "$tmp"
    chmod 600 "$tmp" || { rm -f "$tmp"; return 1; }
    sync -f "$tmp" 2>/dev/null || sync "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$file" || { rm -f "$tmp"; return 1; }
    sync -f "$file" 2>/dev/null || sync "$file" 2>/dev/null || true
)

update_record_valid() (
    file="$1"
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    [ "$(record_value schema "$file")" = 1 ] || return 1
    valid_operation_id "$(record_value operation_id "$file")" || return 1
    valid_identifier "$(record_value instance_id "$file")" || return 1
    [ "$(record_value variant_id "$file")" = pocketrisu ] || return 1
    valid_commit "$(record_value target_sha "$file")" || return 1
    valid_recovery_id "$(record_value recovery_id "$file")" || return 1
    valid_uint "$(record_value retention_seconds "$file")" || return 1
    valid_commit "$(record_value source_sha "$file")" || return 1
    case "$(record_value stage "$file")" in PREPARED|STAGING|RECOVERY_CREATING|RECOVERY_VERIFIED|STOPPING|SWITCHING|STARTING|VERIFYING|SUCCEEDED|ROLLING_BACK|ROLLED_BACK|FAILED) ;; *) return 1 ;; esac
    case "$(record_value was_running "$file")" in unknown|0|1) ;; *) return 1 ;; esac
    case "$(record_value recovery_verified "$file"):$(record_value recovery_required "$file")" in 0:0|1:0|1:1) ;; *) return 1 ;; esac
    valid_uint "$(record_value completion_ms "$file")" && valid_uint "$(record_value expiration_ms "$file")"
)

update_state() {
    operation_id="$1"; instance_id="$2"; state="$3"; stage="$4"; recovery_id="$5"; extra="${6:-}"
    write_state "$operation_id" "{\"ok\":true,\"operation\":\"update\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"$state\",\"stage\":\"$stage\",\"recoveryPointId\":\"$recovery_id\"$extra}"
    append_operation_log "$operation_id" "$stage" "Update stage: $stage"
}

update_failed() {
    operation_id="$1"; instance_id="$2"; recovery_id="$3"; code="$4"; message="$5"; restored="$6"
    restoration_state="${7:-NOT_REQUIRED}"; extra="${8:-}"
    write_state "$operation_id" "{\"ok\":false,\"operation\":\"update\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"FAILED\",\"stage\":\"FAILED\",\"recoveryPointId\":\"$recovery_id\",\"restored\":$restored,\"restorationState\":\"$restoration_state\",\"launchConfirmed\":false,\"healthConfirmed\":false,\"code\":\"$code\",\"message\":\"$(json_escape "$message")\"$extra}"
    append_operation_log "$operation_id" "FAILED:$code" "$message"
}

write_rollback_record() (
    operation_id="$1"; instance_id="$2"; selected_id="$3"; pre_id="$4"; retention="$5"
    rollback_stage="$6"; current_sha="$7"; target_sha="$8"; was_running="$9"
    shift 9
    pre_verified="$1"; recovery_required="$2"; completion_ms="$3"; expiration_ms="$4"
    file="$(rollback_record_file "$operation_id")"; tmp="$file.tmp.$$"
    umask 077
    {
        printf 'schema=1\noperation_id=%s\ninstance_id=%s\nvariant_id=pocketrisu\nselected_recovery_id=%s\npre_recovery_id=%s\nretention_seconds=%s\n' \
            "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention"
        printf 'stage=%s\ncurrent_sha=%s\ntarget_sha=%s\nwas_running=%s\npre_verified=%s\nrecovery_required=%s\ncompletion_ms=%s\nexpiration_ms=%s\n' \
            "$rollback_stage" "$current_sha" "$target_sha" "$was_running" "$pre_verified" "$recovery_required" "$completion_ms" "$expiration_ms"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 600 "$tmp" || { rm -f "$tmp"; return 1; }
    sync -f "$tmp" 2>/dev/null || sync "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$file" || { rm -f "$tmp"; return 1; }
    sync -f "$file" 2>/dev/null || sync "$file" 2>/dev/null || true
)

rollback_record_valid() (
    file="$1"
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    [ "$(record_value schema "$file")" = 1 ] || return 1
    valid_operation_id "$(record_value operation_id "$file")" || return 1
    valid_identifier "$(record_value instance_id "$file")" || return 1
    [ "$(record_value variant_id "$file")" = pocketrisu ] || return 1
    valid_recovery_id "$(record_value selected_recovery_id "$file")" || return 1
    valid_recovery_id "$(record_value pre_recovery_id "$file")" || return 1
    [ "$(record_value selected_recovery_id "$file")" != "$(record_value pre_recovery_id "$file")" ] || return 1
    valid_uint "$(record_value retention_seconds "$file")" || return 1
    case "$(record_value stage "$file")" in PREPARED|STOPPING|PRE_RECOVERY_CREATING|PRE_RECOVERY_VERIFIED|RESTORING|VERIFYING|RECOVERING_CURRENT|SUCCEEDED|FAILED) ;; *) return 1 ;; esac
    valid_commit "$(record_value current_sha "$file")" || return 1
    valid_commit "$(record_value target_sha "$file")" || return 1
    case "$(record_value was_running "$file")" in unknown|0|1) ;; *) return 1 ;; esac
    case "$(record_value pre_verified "$file"):$(record_value recovery_required "$file")" in 0:0|1:0|1:1) ;; *) return 1 ;; esac
    valid_uint "$(record_value completion_ms "$file")" && valid_uint "$(record_value expiration_ms "$file")"
)

rollback_state() {
    operation_id="$1"; instance_id="$2"; state="$3"; stage="$4"; selected_id="$5"; pre_id="$6"; extra="${7:-}"
    write_state "$operation_id" "{\"ok\":true,\"operation\":\"rollback\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"$state\",\"stage\":\"$stage\",\"recoveryPointId\":\"$selected_id\",\"preRollbackRecoveryPointId\":\"$pre_id\"$extra}"
    append_operation_log "$operation_id" "$stage" "Rollback stage: $stage"
}

rollback_failed() {
    operation_id="$1"; instance_id="$2"; selected_id="$3"; pre_id="$4"; code="$5"; message="$6"; restored="$7"
    restoration_state="${8:-NOT_REQUIRED}"; extra="${9:-}"
    write_state "$operation_id" "{\"ok\":false,\"operation\":\"rollback\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"FAILED\",\"stage\":\"FAILED\",\"recoveryPointId\":\"$selected_id\",\"preRollbackRecoveryPointId\":\"$pre_id\",\"restored\":$restored,\"restorationState\":\"$restoration_state\",\"launchConfirmed\":false,\"healthConfirmed\":false,\"code\":\"$code\",\"message\":\"$(json_escape "$message")\"$extra}"
    append_operation_log "$operation_id" "FAILED:$code" "$message"
}

clone_record_file() { printf '%s/%s.clone' "$STATE" "$1"; }
clone_operation_dir() { printf '%s/%s' "$CLONE_ROOT" "$1"; }
clone_staged_instance() { printf '%s/instance' "$(clone_operation_dir "$1")"; }

write_clone_record() (
    operation_id="$1"; source_id="$2"; target_id="$3"; target_port="$4"; clone_stage="$5"
    source_was_running="$6"; source_restored="$7"; publication_intent="$8"; published="$9"
    file="$(clone_record_file "$operation_id")"; tmp="$file.tmp.$$"
    umask 077
    {
        printf 'schema=1\n'
        printf 'operation_id=%s\n' "$operation_id"
        printf 'source_id=%s\n' "$source_id"
        printf 'target_id=%s\n' "$target_id"
        printf 'target_port=%s\n' "$target_port"
        printf 'stage=%s\n' "$clone_stage"
        printf 'source_was_running=%s\n' "$source_was_running"
        printf 'source_restored=%s\n' "$source_restored"
        printf 'publication_intent=%s\n' "$publication_intent"
        printf 'published=%s\n' "$published"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 600 "$tmp" || { rm -f "$tmp"; return 1; }
    sync -f "$tmp" 2>/dev/null || sync "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$file" || { rm -f "$tmp"; return 1; }
    sync -f "$file" 2>/dev/null || sync "$file" 2>/dev/null || true
)

clone_record_value() {
    key="$1"; file="$2"
    sed -n "s/^${key}=//p" "$file" | sed -n '1p'
}

clone_record_valid() (
    file="$1"
    [ -r "$file" ] && [ ! -L "$file" ] || return 1
    [ "$(wc -l < "$file" | tr -d ' ')" = "10" ] || return 1
    [ "$(clone_record_value schema "$file")" = "1" ] || return 1
    valid_operation_id "$(clone_record_value operation_id "$file")" || return 1
    valid_identifier "$(clone_record_value source_id "$file")" || return 1
    valid_identifier "$(clone_record_value target_id "$file")" || return 1
    valid_uint "$(clone_record_value target_port "$file")" || return 1
    case "$(clone_record_value stage "$file")" in PREPARED|STOPPING|SOURCE_STOPPED|COPYING|VALIDATING|TRANSFORMING|RESTORING_SOURCE|READY_TO_PUBLISH|PUBLISHING|PUBLISHED|FAILED|ABORTED) ;; *) return 1 ;; esac
    case "$(clone_record_value source_was_running "$file")" in unknown|0|1) ;; *) return 1 ;; esac
    case "$(clone_record_value source_restored "$file")" in 0|1) ;; *) return 1 ;; esac
    case "$(clone_record_value publication_intent "$file")" in 0|1) ;; *) return 1 ;; esac
    case "$(clone_record_value published "$file")" in 0|1) ;; *) return 1 ;; esac
)

clone_metadata_fields() {
    metadata="$1"
    clone_instance_id="$(sed -n 's/.*"instanceId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    clone_variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    clone_commit="$(sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    clone_version="$(sed -n 's/.*"version":"\([^"]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    clone_workdir="$(sed -n 's/.*"workDir":"\([^"]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    clone_entrypoint="$(sed -n 's/.*"entrypoint":"\([^"]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    valid_identifier "$clone_instance_id" && [ "$clone_variant_id" = pocketrisu ] && valid_commit "$clone_commit" &&
        [ -n "$clone_version" ] && [ "$clone_workdir" = repo ] && [ "$clone_entrypoint" = server/node/server.cjs ]
}

json_boolean() {
    case "$1" in 1|true) printf true ;; *) printf false ;; esac
}

clone_write_state() (
    operation_id="$1"; source_id="$2"; target_id="$3"; state="$4"; clone_stage="$5"
    source_was_running="$6"; source_restored="$7"; target_port="$8"; extra="${9:-}"
    json_source_was_running="$(json_boolean "$source_was_running")"; json_source_restored="$(json_boolean "$source_restored")"
    write_state "$operation_id" "{\"ok\":true,\"operation\":\"clone\",\"operationId\":\"$operation_id\",\"sourceInstanceId\":\"$source_id\",\"targetInstanceId\":\"$target_id\",\"state\":\"$state\",\"stage\":\"$clone_stage\",\"sourceWasRunning\":$json_source_was_running,\"sourceRestored\":$json_source_restored,\"port\":$target_port$extra}"
)

clone_failed() (
    operation_id="$1"; source_id="$2"; target_id="$3"; code="$4"; message="$5"
    source_was_running="$6"; source_restored="$7"; target_port="$8"
    json_source_was_running="$(json_boolean "$source_was_running")"; json_source_restored="$(json_boolean "$source_restored")"
    write_state "$operation_id" "{\"ok\":false,\"operation\":\"clone\",\"operationId\":\"$operation_id\",\"sourceInstanceId\":\"$source_id\",\"targetInstanceId\":\"$target_id\",\"state\":\"FAILED\",\"stage\":\"FAILED\",\"sourceWasRunning\":$json_source_was_running,\"sourceRestored\":$json_source_restored,\"port\":$target_port,\"code\":\"$code\",\"message\":\"$(json_escape "$message")\"}"
    append_operation_log "$operation_id" "FAILED:$code" "$message"
)

instance_lock_dir() { printf '%s/instance-%s' "$LOCKS" "$1"; }
clone_action_lock_dir() { printf '%s/clone-operation-%s' "$LOCKS" "$1"; }

acquire_instance_lock() (
    instance_id="$1"; lock_owner="$2"; reserve="${3:-0}"; lock="$(instance_lock_dir "$instance_id")"
    if [ -L "$LOCKS" ] || { [ -e "$LOCKS" ] && [ ! -d "$LOCKS" ]; }; then return 2; fi
    mkdir -p "$LOCKS" || return 2
    chmod 700 "$LOCKS" 2>/dev/null || true
    if ! mkdir "$lock" 2>/dev/null; then
        [ -d "$lock" ] && [ ! -L "$lock" ] || return 2
        existing_owner="$(sed -n '1p' "$lock/owner" 2>/dev/null || true)"
        if [ "$existing_owner" = "$lock_owner" ]; then
            existing_pid="$(sed -n '1p' "$lock/pid" 2>/dev/null || true)"
            case "$existing_pid" in
                ''|*[!0-9]*) ;;
                *) [ "$existing_pid" = "$$" ] || ! kill -0 "$existing_pid" 2>/dev/null || return 1 ;;
            esac
            printf '%s\n' "$$" > "$lock/pid" || return 2
            if [ "$reserve" = 1 ]; then : > "$lock/reserved" || return 2; fi
            return 0
        fi
        [ -e "$lock/reserved" ] && return 1
        existing_pid="$(sed -n '1p' "$lock/pid" 2>/dev/null || true)"
        case "$existing_pid" in ''|*[!0-9]*) alive=0 ;; *) if kill -0 "$existing_pid" 2>/dev/null; then alive=1; else alive=0; fi ;; esac
        [ "$alive" -eq 0 ] || return 1
        for lock_file in "$lock/owner" "$lock/pid"; do
            [ -L "$lock_file" ] && return 2
            [ -e "$lock_file" ] && [ ! -f "$lock_file" ] && return 2
        done
        rm -f "$lock/owner" "$lock/pid" || return 2
        rmdir "$lock" 2>/dev/null || return 1
        mkdir "$lock" 2>/dev/null || return 1
    fi
    umask 077
    printf '%s\n' "$lock_owner" > "$lock/owner" || return 2
    printf '%s\n' "$$" > "$lock/pid" || return 2
    chmod 600 "$lock/owner" "$lock/pid" || return 2
    if [ "$reserve" = 1 ]; then : > "$lock/reserved" || return 2; fi
    return 0
)

release_instance_lock() (
    instance_id="$1"; lock_owner="$2"; lock="$(instance_lock_dir "$instance_id")"
    [ -d "$lock" ] && [ ! -L "$lock" ] || return 0
    [ "$(sed -n '1p' "$lock/owner" 2>/dev/null || true)" = "$lock_owner" ] || return 1
    rm -f "$lock/owner" "$lock/pid" "$lock/reserved" || return 1
    rmdir "$lock" 2>/dev/null
)

acquire_clone_action_lock() (
    clone_action_operation_id="$1"; clone_action_wait="${2:-0}"
    clone_action_lock="$(clone_action_lock_dir "$clone_action_operation_id")"
    clone_action_attempt=0
    umask 077
    mkdir -p "$LOCKS" || return 2
    chmod 700 "$LOCKS" 2>/dev/null || true
    while :; do
        if mkdir "$clone_action_lock" 2>/dev/null; then
            if ! printf '%s\n' "$$" > "$clone_action_lock/pid" || ! chmod 600 "$clone_action_lock/pid"; then
                rm -f "$clone_action_lock/pid" 2>/dev/null || true
                rmdir "$clone_action_lock" 2>/dev/null || true
                return 2
            fi
            return 0
        fi
        [ -d "$clone_action_lock" ] && [ ! -L "$clone_action_lock" ] || return 2
        clone_action_pid="$(sed -n '1p' "$clone_action_lock/pid" 2>/dev/null || true)"
        case "$clone_action_pid" in
            ''|*[!0-9]*) clone_action_alive=0 ;;
            *) if kill -0 "$clone_action_pid" 2>/dev/null; then clone_action_alive=1; else clone_action_alive=0; fi ;;
        esac
        if [ "$clone_action_alive" -eq 0 ]; then
            [ -L "$clone_action_lock/pid" ] && return 2
            rm -f "$clone_action_lock/pid" 2>/dev/null || return 2
            rmdir "$clone_action_lock" 2>/dev/null || return 2
            continue
        fi
        [ "$clone_action_pid" = "$$" ] && return 0
        [ "$clone_action_attempt" -lt "$clone_action_wait" ] || return 1
        clone_action_attempt=$((clone_action_attempt + 1))
        sleep 1
    done
)

release_clone_action_lock() (
    clone_action_operation_id="$1"; clone_action_lock="$(clone_action_lock_dir "$clone_action_operation_id")"
    [ -d "$clone_action_lock" ] && [ ! -L "$clone_action_lock" ] || return 0
    [ "$(sed -n '1p' "$clone_action_lock/pid" 2>/dev/null || true)" = "$$" ] || return 1
    rm -f "$clone_action_lock/pid" || return 1
    rmdir "$clone_action_lock"
)

with_instance_lock() (
    locked_instance="$1"; lock_verb="$2"; shift 2
    valid_identifier "$locked_instance" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; exit 2; }
    lock_owner="direct-$$-$lock_verb"
    if ! acquire_instance_lock "$locked_instance" "$lock_owner" 0; then
        emit_error "INSTANCE_BUSY" "Another mutating helper operation owns this instance."
        exit 4
    fi
    trap 'release_instance_lock "$locked_instance" "$lock_owner" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    "$@"
)

operation_running() {
    operation_id="$1"
    instance_id="$2"
    stage="$3"
    detail="$4"
    write_state "$operation_id" "{\"ok\":true,\"operation\":\"install\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"RUNNING\",\"stage\":\"$stage\",\"detail\":\"$(json_escape "$detail")\"}"
    append_operation_log "$operation_id" "$stage" "$detail"
}

operation_failed() {
    operation_id="$1"
    instance_id="$2"
    code="$3"
    message="$4"
    write_state "$operation_id" "{\"ok\":false,\"operation\":\"install\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"FAILED\",\"code\":\"$code\",\"message\":\"$(json_escape "$message")\"}"
    append_operation_log "$operation_id" "FAILED:$code" "$message"
}

backup_running() {
    write_state "$1" "{\"ok\":true,\"operation\":\"backup\",\"operationId\":\"$1\",\"instanceId\":\"$2\",\"state\":\"RUNNING\",\"stage\":\"$3\",\"detail\":\"$(json_escape "$4")\"}"
}

backup_failed() {
    write_state "$1" "{\"ok\":false,\"operation\":\"backup\",\"operationId\":\"$1\",\"instanceId\":\"$2\",\"state\":\"FAILED\",\"code\":\"$3\",\"message\":\"$(json_escape "$4")\"}"
}

read_pid() (
    file="$(instance_dir "$1")/run/server.pid"
    [ -r "$file" ] || return 1
    pid="$(sed -n '1p' "$file")"
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s' "$pid"
)

valid_process() (
    instance_id="$1"
    pid="$(read_pid "$instance_id")" || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    metadata="$(instance_dir "$instance_id")/metadata.json"
    variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" 2>/dev/null | sed -n '1p')"
    load_profile "$variant_id" || return 1
    cmdline="$( { tr '\000' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
    case "$cmdline" in *"$profile_entrypoint"*) ;; *) return 1 ;; esac
    expected="$(instance_dir "$instance_id")/repo"
    actual="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    [ "$actual" = "$expected" ]
)

http_healthy() {
    port="$1"; protocol="${2:-HTTP}"; repo="${3:-}"; health_path="${4:-/}"
    tls_mode="MANUAL"; tls_domain=""
    if [ "$protocol" = "HTTPS" ]; then
        [ -f "$repo/server/node/ssl/certificate/server.crt" ] || return 1
        tls_state="${5:-}"
        if [ -n "$tls_state" ] && [ -r "$tls_state" ]; then
            tls_mode="$(config_value mode "$tls_state" 2>/dev/null || printf MANUAL)"
            tls_domain="$(config_value domain "$tls_state" 2>/dev/null || true)"
        fi
    fi
    node - "$protocol" "$port" "$health_path" "$repo/server/node/ssl/certificate/server.crt" "$tls_mode" "$tls_domain" <<'NODE_HEALTH' >/dev/null 2>&1
const fs = require('fs');
const [protocol, port, path, certificate, mode, domain] = process.argv.slice(2);
const secure = protocol === 'HTTPS';
const trustedDomain = secure && mode === 'LETS_ENCRYPT' && domain;
const options = {
  hostname: trustedDomain ? domain : (secure ? 'localhost' : '127.0.0.1'),
  port: Number(port), path, timeout: 3000, agent: false,
};
if (secure) {
  options.lookup = (_host, _options, callback) => callback(null, '127.0.0.1', 4);
  if (!trustedDomain) options.ca = fs.readFileSync(certificate);
}
const request = require(secure ? 'https' : 'http').get(options, (response) => {
  response.resume();
  response.on('end', () => { process.exitCode = response.statusCode >= 200 && response.statusCode < 400 ? 0 : 1; });
});
request.on('timeout', () => request.destroy(new Error('health timeout')));
request.on('error', () => { process.exitCode = 1; });
NODE_HEALTH
}

running_count() {
    count=0
    for pid_file in "$INSTANCES"/*/run/server.pid; do
        [ -e "$pid_file" ] || continue
        candidate="$(basename "$(dirname "$(dirname "$pid_file")")")"
        if valid_process "$candidate"; then count=$((count + 1)); fi
    done
    printf '%s' "$count"
}

release_wake_lock_if_idle() {
    if [ "$(running_count)" -eq 0 ]; then
        termux-wake-unlock >/dev/null 2>&1 || true
    fi
}

watch_requested_file() { printf '%s/run/keep-running' "$(instance_dir "$1")"; }
watch_pid_file() { printf '%s/run/watcher.pid' "$(instance_dir "$1")"; }

watcher_running() (
    instance_id="$1"
    pid_file="$(watch_pid_file "$instance_id")"
    [ -f "$pid_file" ] && [ ! -L "$pid_file" ] || return 1
    pid="$(sed -n '1p' "$pid_file")"
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$pid" 2>/dev/null || return 1
    cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    case "$cmdline" in *"$BIN _watch $instance_id"*) return 0 ;; *) return 1 ;; esac
)

ensure_watcher() (
    instance_id="$1"
    watcher_running "$instance_id" && return 0
    run="$(instance_dir "$instance_id")/run"
    pid_file="$(watch_pid_file "$instance_id")"
    [ ! -L "$pid_file" ] || return 1
    nohup sh "$BIN" _watch "$instance_id" >> "$run/watch.log" 2>&1 < /dev/null &
    printf '%s\n' "$!" > "$pid_file"
)

request_watch() (
    instance_id="$1"
    marker="$(watch_requested_file "$instance_id")"
    [ ! -L "$marker" ] || return 1
    : > "$marker" || return 1
    ensure_watcher "$instance_id"
)

command_watch() {
    instance_id="$1"
    marker="$(watch_requested_file "$instance_id")"
    pid_file="$(watch_pid_file "$instance_id")"
    trap 'if [ "$(sed -n "1p" "$pid_file" 2>/dev/null || true)" = "$$" ]; then rm -f "$pid_file"; fi' EXIT
    trap 'exit 0' HUP INT TERM
    while [ -f "$marker" ] && [ ! -L "$marker" ]; do
        pid="$(read_pid "$instance_id" 2>/dev/null || true)"
        if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
            printf '%s\n' 'Server process disappeared; checking whether recovery is allowed.'
            if ! sh "$BIN" _watch-recover "$instance_id" >> "$(instance_dir "$instance_id")/run/watch.log" 2>&1; then
                sleep 5
            fi
        fi
        sleep 2
    done
}

command_watch_recover() {
    instance_id="$1"
    marker="$(watch_requested_file "$instance_id")"
    [ -f "$marker" ] && [ ! -L "$marker" ] || return 0
    valid_process "$instance_id" && return 0
    command_start "$instance_id"
}

load_profile() {
    profile_id="$1"
    case "$profile_id" in
        pocketrisu)
            profile_repository="https://github.com/PocketRisu/PocketRisu.git"
            profile_strategy="SOURCE_BUILD"
            profile_build_script="POCKETRISU_TERMUX_BUILD"
            profile_workdir="repo"
            profile_entrypoint="server/node/server.cjs"
            profile_min_node_major="22"
            profile_health_path="/"
            profile_storage="sqlite"
            profile_architectures="aarch64"
            ;;
        haejeok-risuai-termux)
            profile_repository="https://github.com/nevaeh5379/HaejeokRisuai.git"
            profile_strategy="RELEASE_ARCHIVE"
            profile_build_script="NONE"
            profile_workdir="repo"
            profile_entrypoint="server/node/server.cjs"
            profile_min_node_major="20"
            profile_health_path="/api/health"
            profile_storage="postgres"
            profile_architectures="aarch64 arm x86_64 x86"
            ;;
        *) return 1 ;;
    esac
}

run_trusted_build() {
    build_id="$1"; repo="$2"; log="$3"
    case "$build_id" in
        POCKETRISU_TERMUX_BUILD)
            [ -f "$repo/scripts/termux/build.sh" ] || return 1
            node_gyp_script="${PREFIX:?}/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js"
            node_gyp_shim="$ROOT/bin/node-gyp"
            [ -f "$node_gyp_script" ] || return 1
            cat > "$node_gyp_shim.tmp.$$" <<'NODE_GYP_SHIM'
#!/data/data/com.termux/files/usr/bin/sh
exec node "${PREFIX:?}/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js" "$@"
NODE_GYP_SHIM
            chmod 700 "$node_gyp_shim.tmp.$$"
            mv -f "$node_gyp_shim.tmp.$$" "$node_gyp_shim"
            (cd "$repo" && PATH="$ROOT/bin:$PATH" bash scripts/termux/build.sh) >> "$log" 2>&1
            ;;
        *) return 1 ;;
    esac
}

run_trusted_update_build() {
    repo="$1"; log="$2"; private_bin="$3"; private_store="$4"
    [ -f "$repo/pnpm-lock.yaml" ] && [ -f "$repo/package.json" ] || return 1
    node_gyp_script="${PREFIX:?}/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js"
    [ -f "$node_gyp_script" ] && command -v pnpm >/dev/null 2>&1 || return 1
    mkdir -p "$private_bin" "$private_store" "$private_store/cache" "$private_store/corepack" || return 1
    cat > "$private_bin/node-gyp" <<'NODE_GYP_SHIM'
#!/data/data/com.termux/files/usr/bin/sh
exec node "${PREFIX:?}/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js" "$@"
NODE_GYP_SHIM
    chmod 700 "$private_bin/node-gyp" || return 1
    # Deliberately do not call scripts/termux/build.sh here: that script runs
    # pkg and corepack global installation. Updates may create only local
    # node_modules/build output inside this immutable staging checkout.
    (cd "$repo" && export GYP_DEFINES="android_ndk_path=''" XDG_CACHE_HOME="$private_store/cache" \
        npm_config_cache="$private_store/cache/npm" COREPACK_HOME="$private_store/corepack" &&
        PATH="$private_bin:$PATH" pnpm install --frozen-lockfile --store-dir "$private_store/pnpm" &&
        NODE_OPTIONS="--max-old-space-size=2048" PATH="$private_bin:$PATH" pnpm build) >> "$log" 2>&1
}

apply_managed_server_patch() {
    entrypoint="$1"; log="$2"
    [ -f "$PATCHER" ] || return 1
    node "$PATCHER" "$entrypoint" >> "$log" 2>&1
}

node_version_supported() {
    minimum_major="$1"
    version="$(node -p 'process.versions.node' 2>/dev/null || true)"
    major="${version%%.*}"; rest="${version#*.}"; minor="${rest%%.*}"
    valid_uint "$major" && valid_uint "$minor" || return 1
    if [ "$minimum_major" -eq 22 ]; then
        [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 12 ]; }
    else
        [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 12 ]; } || { [ "$major" -eq 20 ] && [ "$minor" -ge 19 ]; }
    fi
}

setup_haejeok_database() {
    instance="$1"; log="$2"; data="$instance/postgres/data"; instance_id="${instance##*/}"; socket="$ROOT/sockets/$instance_id"
    mkdir -p "$socket" "$instance/save" "$instance/backups" "$instance/manager"
    if [ ! -f "$data/PG_VERSION" ]; then
        initdb -D "$data" --auth-local=trust --auth-host=scram-sha-256 >> "$log" 2>&1 || return 1
    fi
    seed="$(printf '%s' "$instance" | cksum | awk '{print $1}')"
    db_port=$((54000 + seed % 8000))
    attempts=0
    while ! port_available 127.0.0.1 "$db_port" && [ "$attempts" -lt 100 ]; do
        db_port=$((db_port + 1)); [ "$db_port" -le 62000 ] || db_port=54000; attempts=$((attempts + 1))
    done
    [ "$attempts" -lt 100 ] || return 1
    password="$(openssl rand -hex 24 2>/dev/null)"
    case "$password" in *[!0-9a-f]*|'') return 1 ;; esac
    user="$(id -un)"; case "$user" in *[!A-Za-z0-9_]*) return 1 ;; esac
    pg_ctl -D "$data" -l "$instance/postgres/postgres.log" -o "-h 127.0.0.1 -p $db_port -k $socket" start >> "$log" 2>&1 || return 1
    createdb -h "$socket" -p "$db_port" risu_manager >> "$log" 2>&1 || true
    psql -h "$socket" -p "$db_port" -d postgres -v ON_ERROR_STOP=1 \
        -c "ALTER USER \"$user\" WITH PASSWORD '$password'" >> "$log" 2>&1 || { pg_ctl -D "$data" stop -m fast >> "$log" 2>&1 || true; return 1; }
    pg_ctl -D "$data" stop -m fast >> "$log" 2>&1 || return 1
    db_config="$instance/manager/db.conf"; db_temp="$db_config.tmp.$$"
    umask 077
    {
        printf 'db_port=%s\n' "$db_port"
        printf 'db_user=%s\n' "$user"
        printf 'db_password=%s\n' "$password"
        printf 'db_name=risu_manager\n'
        printf 'db_socket=%s\n' "$socket"
    } > "$db_temp"
    chmod 600 "$db_temp"; mv -f "$db_temp" "$db_config"
}

do_install() (
    operation_id="$1"
    instance_id="$2"
    variant_id="$3"
    revision="$4"
    artifact_url="${5:-}"
    artifact_sha256="${6:-}"
    checksum_url="${7:-}"
    instance="$(instance_dir "$instance_id")"
    repo="$instance/repo"
    run="$instance/run"
    log="$LOGS/$operation_id.log"
    operation_pid="$(operation_pid_file "$operation_id")"
    install_lock_root="$ROOT/locks"
    install_lock="$install_lock_root/install-$instance_id"
    recovery_root="$ROOT/backups/install-recovery/$operation_id"
    recovery_backup="$recovery_root/attempt-1"
    recovery_used=0
    install_lock_acquired=0
    operation_pid_owned=0
    cleanup_install() {
        if [ "$operation_pid_owned" -eq 1 ]; then rm -f "$operation_pid"; fi
        if [ "$install_lock_acquired" -eq 1 ]; then
            rm -f "$install_lock/operation-id"
            rmdir "$install_lock" 2>/dev/null || true
        fi
    }
    trap cleanup_install 0
    trap 'exit 143' HUP INT TERM

    umask 077
    for managed_parent in "$ROOT" "$INSTANCES" "$ROOT/backups"; do
        if [ -L "$managed_parent" ] || { [ -e "$managed_parent" ] && [ ! -d "$managed_parent" ]; }; then
            operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "A managed install parent is not a real directory and was left untouched: $managed_parent"
            return 1
        fi
    done
    if [ -L "$install_lock_root" ] || { [ -e "$install_lock_root" ] && [ ! -d "$install_lock_root" ]; }; then
        operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "The install lock parent is not a real directory and was left untouched: $install_lock_root"
        return 1
    fi
    mkdir -p "$install_lock_root"
    chmod 700 "$install_lock_root"
    if ! mkdir "$install_lock" 2>/dev/null; then
        existing_lock_operation="$(sed -n '1p' "$install_lock/operation-id" 2>/dev/null || true)"
        existing_pid_file=""
        existing_pid=""
        if valid_operation_id "$existing_lock_operation"; then
            existing_pid_file="$(operation_pid_file "$existing_lock_operation")"
            existing_pid="$(sed -n '1p' "$existing_pid_file" 2>/dev/null || true)"
        fi
        existing_alive=false
        case "$existing_pid" in
            ''|*[!0-9]*) ;;
            *)
                existing_cmdline="$( { tr '\000' ' ' < "/proc/$existing_pid/cmdline"; } 2>/dev/null || true)"
                case "$existing_cmdline" in *'risu-manager _install '*"$existing_lock_operation"*) existing_alive=true ;; esac
                ;;
        esac
        if [ "$existing_alive" = true ]; then
            operation_failed "$operation_id" "$instance_id" "INSTALL_ALREADY_RUNNING" "Another installation is already active for this instance."
            return 1
        fi
        rm -f "$install_lock/operation-id"
        if [ -n "$existing_pid_file" ]; then rm -f "$existing_pid_file"; fi
        rmdir "$install_lock" 2>/dev/null || {
            operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "A stale install lock could not be reset safely."
            return 1
        }
        mkdir "$install_lock" || return 1
        append_operation_log "$operation_id" "RECOVERY" "Removed a stale installation lock before resuming."
    fi
    install_lock_acquired=1
    printf '%s\n' "$operation_id" > "$install_lock/operation-id" || return 1
    chmod 600 "$install_lock/operation-id" || return 1
    if [ -e "$operation_pid" ] || [ -L "$operation_pid" ]; then return 1; fi
    printf '%s\n' "$$" > "$operation_pid" || return 1
    operation_pid_owned=1

    if ! load_profile "$variant_id"; then
        operation_failed "$operation_id" "$instance_id" "UNSUPPORTED_VARIANT" "No trusted Termux Node profile matches this variant."
        return 1
    fi
    device_arch="$(uname -m 2>/dev/null || true)"
    case "$device_arch" in armv7l|armv8l) device_arch="arm" ;; i?86) device_arch="x86" ;; esac
    case " $profile_architectures " in
        *" $device_arch "*) ;;
        *) operation_failed "$operation_id" "$instance_id" "UNSUPPORTED_ARCHITECTURE" "This trusted profile does not support the device architecture: $device_arch"; return 1 ;;
    esac
    if [ -L "$instance" ] || { [ -e "$instance" ] && [ ! -d "$instance" ]; }; then
        operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "The instance path is not a real directory and was left untouched."
        return 1
    fi
    for protected_path in "$instance/metadata.json" "$instance/save" "$instance/postgres" "$repo/save"; do
        if [ -e "$protected_path" ] || [ -L "$protected_path" ]; then
            operation_failed "$operation_id" "$instance_id" "INSTANCE_ALREADY_EXISTS" "The instance already contains a repository, metadata, or user data; it was left untouched."
            return 1
        fi
    done
    for managed_instance_path in "$run" "$instance/manager"; do
        if [ -L "$managed_instance_path" ] || { [ -e "$managed_instance_path" ] && [ ! -d "$managed_instance_path" ]; }; then
            operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "An instance management path is not a real directory and was left untouched: $managed_instance_path"
            return 1
        fi
    done
    archive="$instance/release.tar.gz"
    checksum="$instance/release.sha256"
    archive_listing="$instance/release.contents"
    staging="$instance/staging"
    reset_count=0
    for owned_path in "$repo" "$archive" "$checksum" "$archive_listing" "$staging" "$run" "$instance/manager"; do
        if [ -L "$owned_path" ]; then
            operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "A managed installation artifact is a symbolic link and was left untouched: $owned_path"
            return 1
        fi
        if [ -e "$owned_path" ]; then
            rm -rf "$owned_path" || {
                operation_failed "$operation_id" "$instance_id" "INSTALL_RESET_FAILED" "A stale managed installation artifact could not be reset: $owned_path"
                return 1
            }
            reset_count=$((reset_count + 1))
        fi
    done
    if [ "$reset_count" -gt 0 ]; then
        append_operation_log "$operation_id" "RECOVERY" "Reset $reset_count stale manager-owned installation artifact(s) before resuming."
    fi
    if [ -L "$recovery_root" ] || { [ -e "$recovery_root" ] && [ ! -d "$recovery_root" ]; }; then
        operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "The recovery backup path is not a real directory and was left untouched: $recovery_root"
        return 1
    fi
    if [ -L "$log" ] || { [ -e "$log" ] && [ ! -f "$log" ]; }; then
        operation_failed "$operation_id" "$instance_id" "UNSAFE_INSTALL_PATH" "The operation log path is not a regular file and was left untouched: $log"
        return 1
    fi

    if ! mkdir -p "$instance" "$run"; then
        operation_failed "$operation_id" "$instance_id" "INSTALL_PREPARATION_FAILED" "The isolated instance directories could not be created."
        return 1
    fi
    if ! touch "$log" || ! chmod 600 "$log"; then
        operation_failed "$operation_id" "$instance_id" "INSTALL_PREPARATION_FAILED" "The private installation log could not be created: $log"
        return 1
    fi
    append_operation_log "$operation_id" "START" "Starting or resuming installation. helper=$HELPER_VERSION instance=$instance_id variant=$variant_id revision=$revision"
    append_operation_log "$operation_id" "ENV" "uname=$(uname -a 2>/dev/null || true)"
    append_operation_log "$operation_id" "ENV" "node=$(node --version 2>/dev/null || printf missing) npm=$(npm --version 2>/dev/null || printf missing) pnpm=$(pnpm --version 2>/dev/null || printf missing) git=$(git --version 2>/dev/null || printf missing)"
    df -h "$ROOT" >> "$log" 2>&1 || true

    install_termux_packages() {
        package_description="$1"
        shift
        if pkg install -y "$@" >> "$log" 2>&1; then return 0; fi
        operation_running "$operation_id" "$instance_id" "RECOVERING_DEPENDENCIES" "Repairing Termux package metadata and retrying $package_description once"
        printf '%s\n' "Termux package installation failed; running one bounded direct apt recovery without the pkg wrapper." >> "$log"
        if ! DEBIAN_FRONTEND=noninteractive dpkg --force-confold --configure -a >> "$log" 2>&1; then
            printf '%s\n' "dpkg could not configure every unpacked package before dependency repair; continuing with apt-get." >> "$log"
        fi
        if ! DEBIAN_FRONTEND=noninteractive apt-get update >> "$log" 2>&1 ||
           ! DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=--force-confold --fix-broken install -y >> "$log" 2>&1 ||
           ! DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=--force-confold full-upgrade -y >> "$log" 2>&1 ||
           ! DEBIAN_FRONTEND=noninteractive apt-get -o Dpkg::Options::=--force-confold install -y "$@" >> "$log" 2>&1; then
            operation_failed "$operation_id" "$instance_id" "DEPENDENCY_INSTALL_FAILED" "Termux could not install $package_description after one recovery attempt. Recovery exhausted. Log: $log"
            return 1
        fi
        printf '%s\n' "Termux package recovery succeeded." >> "$log"
    }

    preserve_failed_attempt() {
        for recovery_parent in "$ROOT/backups" "$ROOT/backups/install-recovery"; do
            if [ -L "$recovery_parent" ] || { [ -e "$recovery_parent" ] && [ ! -d "$recovery_parent" ]; }; then return 1; fi
        done
        mkdir -p "$ROOT/backups/install-recovery" "$recovery_root" || return 1
        chmod 700 "$ROOT/backups/install-recovery" "$recovery_root" || return 1
        recovery_index=1
        recovery_backup="$recovery_root/attempt-$recovery_index"
        while [ -e "$recovery_backup" ] || [ -L "$recovery_backup" ]; do
            recovery_index=$((recovery_index + 1))
            [ "$recovery_index" -le 20 ] || return 1
            recovery_backup="$recovery_root/attempt-$recovery_index"
        done
        mkdir "$recovery_backup" || return 1
        chmod 700 "$recovery_backup" || return 1
        preserved_count=0
        for failed_path in "$repo" "$staging" "$archive" "$checksum" "$archive_listing"; do
            if [ -e "$failed_path" ] || [ -L "$failed_path" ]; then
                failed_name="${failed_path##*/}"
                failed_destination="$recovery_backup/$failed_name"
                if [ -e "$failed_destination" ] || [ -L "$failed_destination" ]; then return 1; fi
                mv "$failed_path" "$failed_destination" || return 1
                if [ -e "$failed_path" ] || [ -L "$failed_path" ]; then return 1; fi
                if [ ! -e "$failed_destination" ] && [ ! -L "$failed_destination" ]; then return 1; fi
                preserved_count=$((preserved_count + 1))
            fi
        done
        {
            printf 'code=%s\n' "$first_error_code"
            printf 'message=%s\n' "$first_error_message"
            printf 'preserved_artifacts=%s\n' "$preserved_count"
        } > "$recovery_backup/failure.txt" || return 1
        chmod 600 "$recovery_backup/failure.txt" || return 1
        printf '%s\n' "First failed install attempt preserved at $recovery_backup ($preserved_count artifact(s))." >> "$log"
    }

    source_install_attempt() {
        install_error_recoverable=0
        operation_running "$operation_id" "$instance_id" "CLONING" "Fetching the selected trusted source revision"
        if ! git clone --filter=blob:none --no-checkout "$profile_repository" "$repo" >> "$log" 2>&1; then
            install_error_code="GIT_CLONE_FAILED"
            install_error_message="The trusted source repository could not be cloned."
            install_error_recoverable=1
            return 1
        fi
        if ! git -C "$repo" fetch --depth=1 origin "$revision" >> "$log" 2>&1 || ! git -C "$repo" checkout --detach FETCH_HEAD >> "$log" 2>&1; then
            install_error_code="REVISION_UNAVAILABLE"
            install_error_message="The selected source revision is unavailable."
            install_error_recoverable=1
            return 1
        fi
        commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"
        if [ "$commit" != "$revision" ]; then
            install_error_code="REVISION_MISMATCH"
            install_error_message="The checked out commit does not match the verified requested revision."
            return 1
        fi
        operation_running "$operation_id" "$instance_id" "BUILDING" "Running the allowlisted Termux build strategy"
        if ! run_trusted_build "$profile_build_script" "$repo" "$log"; then
            install_error_code="BUILD_FAILED"
            install_error_message="The trusted Termux build strategy failed."
            install_error_recoverable=1
            return 1
        fi
        commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"
        if [ "$commit" != "$revision" ]; then
            install_error_code="REVISION_MISMATCH"
            install_error_message="The built Git checkout no longer matches the verified requested revision."
            return 1
        fi
        if [ ! -f "$repo/$profile_entrypoint" ] || [ ! -d "$repo/dist" ]; then
            install_error_code="INSTALL_VERIFICATION_FAILED"
            install_error_message="The expected server or frontend output is missing."
            install_error_recoverable=1
            return 1
        fi
    }

    archive_install_attempt() {
        install_error_recoverable=0
        operation_running "$operation_id" "$instance_id" "DOWNLOADING" "Downloading and verifying the official Termux archive"
        if ! curl --proto '=https' --tlsv1.2 --fail --location --output "$archive" "$artifact_url" >> "$log" 2>&1; then
            install_error_code="DOWNLOAD_FAILED"
            install_error_message="The official Termux archive could not be downloaded."
            install_error_recoverable=1
            return 1
        fi
        if ! curl --proto '=https' --tlsv1.2 --fail --location --output "$checksum" "$checksum_url" >> "$log" 2>&1; then
            install_error_code="CHECKSUM_DOWNLOAD_FAILED"
            install_error_message="The official checksum could not be downloaded."
            install_error_recoverable=1
            return 1
        fi
        actual="$(sha256sum "$archive" | awk '{print $1}')"
        official="$(awk 'NR==1 {print $1}' "$checksum")"
        if [ "$actual" != "$artifact_sha256" ] || [ "$official" != "$artifact_sha256" ]; then
            install_error_code="CHECKSUM_MISMATCH"
            install_error_message="The release archive failed SHA-256 verification."
            return 1
        fi
        if ! tar -tzf "$archive" > "$archive_listing" 2>> "$log"; then
            install_error_code="EXTRACT_FAILED"
            install_error_message="The verified release archive could not be read."
            install_error_recoverable=1
            return 1
        fi
        if grep -Eq '(^/|(^|/)\.\.(/|$))' "$archive_listing"; then
            install_error_code="UNSAFE_ARCHIVE"
            install_error_message="The release archive contains an unsafe path."
            return 1
        fi
        rm -f "$archive_listing"
        mkdir "$staging" || {
            install_error_code="EXTRACT_FAILED"
            install_error_message="A private extraction directory could not be created."
            install_error_recoverable=1
            return 1
        }
        if ! tar -xzf "$archive" --no-same-owner --no-same-permissions -C "$staging" >> "$log" 2>&1; then
            install_error_code="EXTRACT_FAILED"
            install_error_message="The release archive could not be extracted."
            install_error_recoverable=1
            return 1
        fi
        for required in dist/index.html server/node/server.cjs package.json package-lock.json RELEASE deploy/termux/haejeok.sh; do
            if [ ! -f "$staging/$required" ]; then
                install_error_code="INCOMPATIBLE_ARCHIVE"
                install_error_message="The release archive is missing a required file."
                return 1
            fi
        done
        if ! mv "$staging" "$repo"; then
            install_error_code="INSTALL_VERIFICATION_FAILED"
            install_error_message="The extracted release could not be placed in its isolated repository."
            install_error_recoverable=1
            return 1
        fi
        rm -f "$archive" "$checksum"
        operation_running "$operation_id" "$instance_id" "BUILDING" "Installing locked runtime dependencies"
        if ! (cd "$repo" && npm ci --omit=dev --ignore-scripts && npm install --omit=dev --ignore-scripts web-push) >> "$log" 2>&1; then
            install_error_code="DEPENDENCY_INSTALL_FAILED"
            install_error_message="The archive runtime dependencies could not be installed."
            install_error_recoverable=1
            return 1
        fi
        if [ ! -f "$repo/$profile_entrypoint" ] || [ ! -d "$repo/dist" ]; then
            install_error_code="INSTALL_VERIFICATION_FAILED"
            install_error_message="The expected server or frontend output is missing."
            install_error_recoverable=1
            return 1
        fi
        commit="$revision"
    }

    if [ "$profile_strategy" = "SOURCE_BUILD" ]; then
        operation_running "$operation_id" "$instance_id" "PREPARING_TERMUX" "Installing Git and Node.js in Termux"
        install_termux_packages "source build dependencies" git nodejs-lts || return 1
        node_version_supported "$profile_min_node_major" || { operation_failed "$operation_id" "$instance_id" "NODE_VERSION_UNSUPPORTED" "The installed Node.js version does not satisfy the trusted profile."; return 1; }
    elif [ "$profile_strategy" = "RELEASE_ARCHIVE" ]; then
        operation_running "$operation_id" "$instance_id" "PREPARING_TERMUX" "Installing the verified archive runtime dependencies"
        install_termux_packages "archive runtime dependencies" nodejs-lts postgresql curl tar openssl-tool coreutils || return 1
        node_version_supported "$profile_min_node_major" || { operation_failed "$operation_id" "$instance_id" "NODE_VERSION_UNSUPPORTED" "The installed Node.js version does not satisfy the trusted profile."; return 1; }
        prefix="https://github.com/nevaeh5379/HaejeokRisuai/releases/download/"
        case "$artifact_url" in "$prefix"*) ;; *) operation_failed "$operation_id" "$instance_id" "INVALID_ARTIFACT_URL" "The release archive URL is outside the trusted profile."; return 1 ;; esac
        remainder="${artifact_url#"$prefix"}"; tag="${remainder%%/*}"
        printf '%s' "$tag" | grep -Eq '^b[0-9]+$' || { operation_failed "$operation_id" "$instance_id" "INVALID_ARTIFACT_TAG" "The release tag does not match the trusted profile."; return 1; }
        [ "$artifact_url" = "$prefix$tag/RisuAI-Termux-$tag.tar.gz" ] || { operation_failed "$operation_id" "$instance_id" "INVALID_ARTIFACT_NAME" "The release archive name does not match the trusted profile."; return 1; }
        case "$checksum_url" in "$artifact_url.sha256") ;; *) operation_failed "$operation_id" "$instance_id" "INVALID_CHECKSUM_URL" "The checksum URL does not match the trusted release asset."; return 1 ;; esac
        case "$artifact_sha256" in *[!0-9a-f]*|'') operation_failed "$operation_id" "$instance_id" "INVALID_CHECKSUM" "The GitHub asset digest is invalid."; return 1 ;; esac
        [ "${#artifact_sha256}" -eq 64 ] || { operation_failed "$operation_id" "$instance_id" "INVALID_CHECKSUM" "The GitHub asset digest is invalid."; return 1; }
    else
        operation_failed "$operation_id" "$instance_id" "UNSUPPORTED_INSTALL_STRATEGY" "The profile selected an unsupported install strategy."; return 1
    fi

    install_attempt=1
    while [ "$install_attempt" -le 2 ]; do
        if [ "$profile_strategy" = "SOURCE_BUILD" ]; then
            project_attempt_succeeded=0
            source_install_attempt && project_attempt_succeeded=1
        else
            project_attempt_succeeded=0
            archive_install_attempt && project_attempt_succeeded=1
        fi
        [ "$project_attempt_succeeded" -eq 0 ] || break

        if [ "$install_attempt" -eq 1 ] && [ "$install_error_recoverable" -eq 1 ]; then
            first_error_code="$install_error_code"
            first_error_message="$install_error_message"
            operation_running "$operation_id" "$instance_id" "RECOVERING_PROJECT" "Preserving the failed attempt before one clean retry at $recovery_backup"
            if ! preserve_failed_attempt; then
                operation_failed "$operation_id" "$instance_id" "$first_error_code" "$first_error_message Recovery could not safely preserve the failed attempt, so no retry was started. Intended backup: $recovery_backup. Log: $log"
                return 1
            fi
            recovery_used=1
            install_attempt=2
            operation_running "$operation_id" "$instance_id" "RECOVERING_PROJECT" "Retrying the same verified revision once; first attempt retained at $recovery_backup"
            continue
        fi

        if [ "$recovery_used" -eq 1 ]; then
            operation_failed "$operation_id" "$instance_id" "$first_error_code" "$first_error_message Recovery exhausted after one clean retry. Retry failure: $install_error_code: $install_error_message Backup: $recovery_backup. Log: $log"
        else
            operation_failed "$operation_id" "$instance_id" "$install_error_code" "$install_error_message Log: $log"
        fi
        return 1
    done

    if [ "$profile_strategy" = "RELEASE_ARCHIVE" ]; then
        post_recovery_detail="Log: $log"
        if [ "$recovery_used" -eq 1 ]; then post_recovery_detail="Backup: $recovery_backup. Log: $log"; fi
        setup_haejeok_database "$instance" "$log" || { operation_failed "$operation_id" "$instance_id" "DATABASE_SETUP_FAILED" "The isolated PostgreSQL database could not be initialized. Database setup is not retried automatically. $post_recovery_detail"; return 1; }
    fi

    post_recovery_detail="Log: $log"
    if [ "$recovery_used" -eq 1 ]; then post_recovery_detail="Backup: $recovery_backup. Log: $log"; fi
    node_version_supported "$profile_min_node_major" || { operation_failed "$operation_id" "$instance_id" "NODE_VERSION_UNSUPPORTED" "The installed Node.js version does not satisfy the trusted profile. $post_recovery_detail"; return 1; }

    operation_running "$operation_id" "$instance_id" "VERIFYING" "Verifying the server entry point"
    entrypoint="$repo/$profile_entrypoint"
    [ -f "$entrypoint" ] && [ -d "$repo/dist" ] || { operation_failed "$operation_id" "$instance_id" "INSTALL_VERIFICATION_FAILED" "The expected server or frontend output disappeared after installation. $post_recovery_detail"; return 1; }
    if [ "$variant_id" = "pocketrisu" ] && ! apply_managed_server_patch "$entrypoint" "$log"; then
        operation_failed "$operation_id" "$instance_id" "INCOMPATIBLE_SERVER_LAYOUT" "The server did not match the validated Manager patch context; the original file was left untouched. $post_recovery_detail"
        return 1
    fi

    if [ "$profile_strategy" = "SOURCE_BUILD" ]; then
        commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"
        [ "$commit" = "$revision" ] || { operation_failed "$operation_id" "$instance_id" "REVISION_MISMATCH" "The installed Git checkout no longer matches the verified requested revision. $post_recovery_detail"; return 1; }
    fi
    version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$repo/package.json" | sed -n '1p')"
    case "$commit" in *[!0-9a-f]*|'')
        operation_failed "$operation_id" "$instance_id" "INSTALL_VERIFICATION_FAILED" "The installed Git commit could not be verified. $post_recovery_detail"
        return 1
    esac

    ensure_config "$instance_id"
    load_config "$instance_id" || { operation_failed "$operation_id" "$instance_id" "INVALID_CONFIG" "The default server configuration could not be created. $post_recovery_detail"; return 1; }
    metadata="$instance/metadata.json"
    metadata_temp="$metadata.tmp.$$"
    if ! printf '{"instanceId":"%s","variantId":"%s","commit":"%s","version":"%s","workDir":"%s","entrypoint":"%s"}\n' "$instance_id" "$variant_id" "$commit" "$(json_escape "$version")" "$profile_workdir" "$profile_entrypoint" > "$metadata_temp" || ! chmod 600 "$metadata_temp" || ! mv "$metadata_temp" "$metadata"; then
        rm -f "$metadata_temp"
        operation_failed "$operation_id" "$instance_id" "INSTALL_METADATA_FAILED" "The verified installation metadata could not be committed. $post_recovery_detail"
        return 1
    fi
    if [ "$recovery_used" -eq 1 ]; then
        write_state "$operation_id" "{\"ok\":true,\"operation\":\"install\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"SUCCEEDED\",\"stage\":\"READY\",\"commit\":\"$commit\",\"version\":\"$(json_escape "$version")\",\"port\":$cfg_port,\"message\":\"$(json_escape "Installation recovered after one clean retry. First attempt retained at $recovery_backup")\",\"recoveryBackup\":\"$(json_escape "$recovery_backup")\"}"
    else
        write_state "$operation_id" "{\"ok\":true,\"operation\":\"install\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"SUCCEEDED\",\"stage\":\"READY\",\"commit\":\"$commit\",\"version\":\"$(json_escape "$version")\",\"port\":$cfg_port}"
    fi
)

do_backup() (
    operation_id="$1"
    instance_id="$2"
    instance="$(instance_dir "$instance_id")"
    repo="$instance/repo"
    run="$instance/run"
    backup="$ROOT/backups/$operation_id.bin"
    port_file="$ROOT/backups/$operation_id.port"
    token_file="$ROOT/backups/$operation_id.token"
    serve_pid_file="$ROOT/backups/$operation_id.pid"
    log="$LOGS/$operation_id.log"
    was_running=0

    restart_after_backup() {
        restart_attempts=0
        while [ "$restart_attempts" -lt 10 ]; do
            if command_start "$instance_id" >> "$log" 2>&1; then return 0; fi
            restart_attempts=$((restart_attempts + 1))
            sleep 1
        done
        return 1
    }

    metadata="$instance/metadata.json"; variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" 2>/dev/null | sed -n '1p')"
    load_profile "$variant_id" || { backup_failed "$operation_id" "$instance_id" "UNSUPPORTED_VARIANT" "The instance has no trusted Termux Node profile."; exit 3; }
    [ -f "$repo/$profile_entrypoint" ] || { backup_failed "$operation_id" "$instance_id" "INSTANCE_NOT_INSTALLED" "The configured server entry point is missing."; exit 3; }
    [ -f "$repo/save/risuai.db" ] || { backup_failed "$operation_id" "$instance_id" "BACKUP_DATABASE_MISSING" "PocketRisu has no SQLite database to back up."; exit 3; }
    [ -f "$EXPORTER" ] && [ -f "$SERVER" ] || { backup_failed "$operation_id" "$instance_id" "HELPER_INCOMPLETE" "The installed backup helper is incomplete."; exit 3; }
    umask 077
    : > "$log"

    if valid_process "$instance_id"; then
        was_running=1
        backup_running "$operation_id" "$instance_id" "STOPPING" "Waiting for PocketRisu to flush pending writes"
        pid="$(read_pid "$instance_id")"
        kill -TERM "$pid" 2>/dev/null || true
        attempts=0
        while valid_process "$instance_id" && [ "$attempts" -lt 30 ]; do attempts=$((attempts + 1)); sleep 1; done
        if valid_process "$instance_id"; then
            backup_failed "$operation_id" "$instance_id" "GRACEFUL_STOP_FAILED" "PocketRisu did not stop cleanly; backup was cancelled without forcing it to exit."
            exit 4
        fi
        rm -f "$run/server.pid"
    fi

    backup_running "$operation_id" "$instance_id" "EXPORTING" "Building a Risu-compatible .bin backup"
    rm -f "$backup" "$port_file" "$token_file" "$serve_pid_file"
    if ! (cd "$repo" && node "$EXPORTER" "$repo" "$backup") >> "$log" 2>&1; then
        rm -f "$backup"
        if [ "$was_running" -eq 1 ] && ! restart_after_backup; then
            backup_failed "$operation_id" "$instance_id" "SERVER_RESTART_FAILED" "The backup failed and PocketRisu could not be restarted automatically. Start it again from the Home screen."
            exit 6
        fi
        if grep -q 'database/database.bin is missing' "$log"; then
            backup_failed "$operation_id" "$instance_id" "BACKUP_DATABASE_EMPTY" "PocketRisu has not created its first save yet. Open PocketRisu and finish its initial setup, then try the backup again."
        else
            backup_failed "$operation_id" "$instance_id" "BACKUP_EXPORT_FAILED" "PocketRisu data could not be encoded. Check the operation log."
        fi
        exit 5
    fi

    if [ "$was_running" -eq 1 ]; then
        backup_running "$operation_id" "$instance_id" "STARTING" "Restarting PocketRisu"
        if ! restart_after_backup; then
            rm -f "$backup"
            backup_failed "$operation_id" "$instance_id" "SERVER_RESTART_FAILED" "The backup was discarded because PocketRisu could not be restarted."
            exit 6
        fi
    fi

    size="$(wc -c < "$backup" | tr -d ' ')"
    hash="$(sha256sum "$backup" | sed -n 's/ .*//p')"
    token="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
    case "$size" in ''|*[!0-9]*) rm -f "$backup"; backup_failed "$operation_id" "$instance_id" "BACKUP_VERIFY_FAILED" "The backup size could not be verified."; exit 7 ;; esac
    case "$hash:$token" in *[!0-9a-f:]*) rm -f "$backup"; backup_failed "$operation_id" "$instance_id" "BACKUP_VERIFY_FAILED" "The backup hash or transfer token is invalid."; exit 7 ;; esac
    [ "${#hash}" -eq 64 ] && [ "${#token}" -eq 64 ] || { rm -f "$backup"; backup_failed "$operation_id" "$instance_id" "BACKUP_VERIFY_FAILED" "The backup verification metadata is invalid."; exit 7; }
    printf '%s\n' "$token" > "$token_file"
    backup_running "$operation_id" "$instance_id" "SERVING" "Opening a private loopback transfer"
    RISU_BACKUP_SHA256="$hash" nohup node "$SERVER" "$backup" "$token" "$port_file" "$serve_pid_file" "$token_file" >> "$log" 2>&1 < /dev/null &
    printf '%s\n' "$!" > "$serve_pid_file"
    attempts=0
    while [ ! -s "$port_file" ] && kill -0 "$(sed -n '1p' "$serve_pid_file")" 2>/dev/null && [ "$attempts" -lt 50 ]; do attempts=$((attempts + 1)); sleep 1; done
    port="$(sed -n '1p' "$port_file" 2>/dev/null || true)"
    case "$port" in ''|*[!0-9]*) rm -f "$backup" "$port_file" "$token_file" "$serve_pid_file"; backup_failed "$operation_id" "$instance_id" "BACKUP_TRANSFER_START_FAILED" "The private backup transfer could not be started."; exit 8 ;; esac
    write_state "$operation_id" "{\"ok\":true,\"operation\":\"backup\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"SUCCEEDED\",\"stage\":\"READY\",\"port\":$port,\"token\":\"$token\",\"size\":$size,\"sha256\":\"$hash\",\"formatVersion\":1}"
)

managed_port_available() (
    candidate_port="$1"; ignored_instance="${2:-}"
    valid_uint "$candidate_port" && [ "$candidate_port" -ge 1024 ] && [ "$candidate_port" -le 65535 ] || return 1
    for candidate_config in "$INSTANCES"/*/manager/server.conf; do
        [ -f "$candidate_config" ] && [ ! -L "$candidate_config" ] || continue
        candidate_id="$(basename "$(dirname "$(dirname "$candidate_config")")")"
        [ "$candidate_id" = "$ignored_instance" ] && continue
        [ "$(config_value port "$candidate_config" 2>/dev/null || true)" = "$candidate_port" ] && return 1
    done
    port_available 127.0.0.1 "$candidate_port"
)

tree_manifest_hash() {
    node -e 'const f=require("fs"),p=require("path"),c=require("crypto"),root=p.resolve(process.argv[1]),h=c.createHash("sha256"),buf=Buffer.allocUnsafe(1024*1024);function hf(q){const fd=f.openSync(q,"r");try{let n;while((n=f.readSync(fd,buf,0,buf.length,null))>0)h.update(buf.subarray(0,n))}finally{f.closeSync(fd)}}function w(d,r){for(const n of f.readdirSync(d).sort()){const q=p.join(d,n),x=r?`${r}/${n}`:n,s=f.lstatSync(q);let t=s.isDirectory()?"d":s.isFile()?"f":s.isSymbolicLink()?"l":"x";h.update(t+"\\0"+x+"\\0");if(t==="d")w(q,x);else if(t==="f"){h.update(String(s.size)+"\\0");hf(q)}else if(t==="l")h.update(f.readlinkSync(q));else process.exit(3)}}w(root,"");process.stdout.write(h.digest("hex"));' "$1"
}

verify_independent_regular_files() {
    node -e 'const f=require("fs"),p=require("path"),a=p.resolve(process.argv[1]),b=p.resolve(process.argv[2]);function w(d,r){for(const n of f.readdirSync(d)){const q=p.join(d,n),x=r?p.join(r,n):n,s=f.lstatSync(q);if(s.isDirectory())w(q,x);else if(s.isFile()){const t=f.lstatSync(p.join(b,x));if(s.dev===t.dev&&s.ino===t.ino)throw Error("shared inode: "+x)}}}w(a,"");' "$1" "$2"
}

sanitize_clone_symlinks() (
    source_root="$1"; staged_root="$2"; final_root="$3"
    node -e 'const f=require("fs"),p=require("path"),src=f.realpathSync(process.argv[1]),dst=f.realpathSync(process.argv[2]),final=p.resolve(process.argv[3]);const inside=(q,r)=>q===r||q.startsWith(r+p.sep);function links(d){let out=[];for(const n of f.readdirSync(d)){const q=p.join(d,n),s=f.lstatSync(q);if(s.isDirectory())out=out.concat(links(q));else if(s.isSymbolicLink())out.push(q)}return out}for(const q of links(dst)){const raw=f.readlinkSync(q);let resolved=null;try{resolved=f.realpathSync(q)}catch{}let mapped=null;if(resolved&&inside(resolved,dst))continue;if(resolved&&inside(resolved,src))mapped=p.join(dst,p.relative(src,resolved));else if(p.isAbsolute(raw)&&inside(p.resolve(raw),final))mapped=p.join(dst,p.relative(final,p.resolve(raw)));else throw Error("broken or escaping symlink: "+p.relative(dst,q));if(!inside(p.resolve(mapped),dst)||!f.existsSync(mapped))throw Error("unmappable symlink: "+p.relative(dst,q));f.unlinkSync(q);f.symlinkSync(p.relative(p.dirname(q),mapped)||".",q);const checked=f.realpathSync(q);if(!inside(checked,dst))throw Error("rewritten symlink escapes: "+p.relative(dst,q))}' "$source_root" "$staged_root" "$final_root"
)

pocketrisu_clone_auth_contract_supported() (
    entrypoint="$1"
    [ -f "$entrypoint" ] && [ ! -L "$entrypoint" ] || return 1
    grep -E "jwtSecretPath.*['\"]__jwt_secret['\"]" "$entrypoint" >/dev/null || return 1
    grep -E "writeFileSync\(jwtSecretPath[),]" "$entrypoint" >/dev/null || return 1
    grep -E "createHmac\(['\"]sha256['\"],[[:space:]]*jwtSecret\)" "$entrypoint" >/dev/null || return 1
    grep -E "instanceIdPath.*['\"]__instance_id['\"]" "$entrypoint" >/dev/null || return 1
    grep -E "writeFileSync\(instanceIdPath[),]" "$entrypoint" >/dev/null || return 1
    grep -F 'randomUUID()' "$entrypoint" >/dev/null || return 1
    grep -E "SESSION_FILE.*['\"]__sessions['\"]" "$entrypoint" >/dev/null || return 1
    grep -E "readFileSync\(SESSION_FILE[),]" "$entrypoint" >/dev/null || return 1
    grep -E "writeFileSync\(SESSION_FILE[),]" "$entrypoint" >/dev/null || return 1
    grep -E "passwordPath.*['\"]__password['\"]" "$entrypoint" >/dev/null || return 1
    grep -E "readFileSync\(passwordPath[),]" "$entrypoint" >/dev/null || return 1
    grep -E "authCodePath.*['\"]__authcode['\"]" "$entrypoint" >/dev/null || return 1
)

validate_clone_sqlite() (
    repo="$1"
    node -e 'const p=require("path"),D=require(p.join(process.argv[1],"node_modules","better-sqlite3")),db=new D(p.join(process.argv[1],"save","risuai.db"),{readonly:true,fileMustExist:true});const rows=db.pragma("quick_check");db.close();if(rows.length!==1||rows[0].quick_check!=="ok")process.exit(1);' "$repo"
)

initialize_clone_sqlite() (
    repo="$1"; db_path="$repo/save/risuai.db"
    [ ! -e "$db_path" ] && [ ! -L "$db_path" ] || return 1
    node -e 'const p=require("path"),D=require(p.join(process.argv[1],"node_modules","better-sqlite3")),db=new D(p.join(process.argv[1],"save","risuai.db"),{fileMustExist:false});try{db.pragma("journal_mode = WAL");db.exec("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value BLOB NOT NULL, updated_at INTEGER NOT NULL DEFAULT (CAST(strftime(\u0027%s\u0027,\u0027now\u0027) AS INTEGER) * 1000))");db.pragma("wal_checkpoint(TRUNCATE)")}finally{db.close()}' "$repo"
)

reset_clone_database_paths() (
    repo="$1"; final_backup_path="$2"
    node -e 'const p=require("path"),D=require(p.join(process.argv[1],"node_modules","better-sqlite3")),db=new D(p.join(process.argv[1],"save","risuai.db"),{fileMustExist:true});db.prepare("INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").run("config/server-backup-path",Buffer.from(process.argv[2]));db.pragma("wal_checkpoint(TRUNCATE)");db.close();' "$repo" "$final_backup_path"
)

clone_marker_valid() (
    marker="$1"; operation_id="$2"; source_id="$3"; target_id="$4"
    [ -f "$marker" ] && [ ! -L "$marker" ] || return 1
    [ "$(config_value schema "$marker")" = "1" ] || return 1
    [ "$(config_value operation_id "$marker")" = "$operation_id" ] || return 1
    [ "$(config_value source_id "$marker")" = "$source_id" ] || return 1
    [ "$(config_value target_id "$marker")" = "$target_id" ] || return 1
    valid_commit "$(config_value commit "$marker")" || return 1
    marker_hash="$(config_value database_sha256 "$marker")"
    case "$marker_hash" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#marker_hash}" -eq 64 ]
)

restore_clone_source() (
    source_id="$1"; source_was_running="$2"; log="$3"
    if [ "$source_was_running" = 0 ]; then
        valid_process "$source_id" && return 1
        return 0
    fi
    valid_process "$source_id" && return 0
    restore_attempts=0
    while [ "$restore_attempts" -lt 10 ]; do
        if (command_start "$source_id") >> "$log" 2>&1; then return 0; fi
        restore_attempts=$((restore_attempts + 1))
        sleep 1
    done
    return 1
)

safe_remove_clone_staging() (
    operation_id="$1"; operation_dir="$(clone_operation_dir "$operation_id")"
    [ "$operation_dir" = "$CLONE_ROOT/$operation_id" ] || return 1
    [ -L "$CLONE_ROOT" ] && return 1
    if [ -L "$operation_dir" ] || { [ -e "$operation_dir" ] && [ ! -d "$operation_dir" ]; }; then return 1; fi
    [ -e "$operation_dir" ] || return 0
    rm -rf "$operation_dir"
)

do_clone() (
    operation_id="$1"; source_id="$2"; target_id="$3"; target_port="$4"
    source="$(instance_dir "$source_id")"; target="$(instance_dir "$target_id")"; source_is_recovery=0; recovery_id=""
    recovery_binding="$(recovery_clone_binding_file "$operation_id")"
    if recovery_clone_binding_valid "$recovery_binding"; then
        [ "$(record_value source_id "$recovery_binding")" = "$source_id" ] && [ "$(record_value target_id "$recovery_binding")" = "$target_id" ] && [ "$(record_value target_port "$recovery_binding")" = "$target_port" ] || exit 2
        recovery_id="$(record_value recovery_id "$recovery_binding")"; source="$(recovery_dir "$recovery_id")/instance"; source_is_recovery=1
    fi
    operation_dir="$(clone_operation_dir "$operation_id")"; staged="$(clone_staged_instance "$operation_id")"
    log="$LOGS/$operation_id.log"; pid_file="$(operation_pid_file "$operation_id")"
    source_was_running=0; source_restored=0; source_stopped=0; source_lock=0; target_lock=0; keep_reservation=0; action_lock=0

    release_clone_locks() {
        [ "$target_lock" -eq 1 ] && release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
        [ "$source_lock" -eq 1 ] && release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
        target_lock=0; source_lock=0
    }
    fail_clone() {
        failure_code="$1"; failure_message="$2"
        if [ "$source_stopped" -eq 1 ]; then
            if [ "$source_is_recovery" -eq 1 ]; then
                source_restored=1
            elif restore_clone_source "$source_id" "$source_was_running" "$log"; then
                source_restored=1
            else
                failure_code="SOURCE_RESTART_FAILED"
                failure_message="The clone did not complete and the previously running source could not be restarted automatically."
            fi
        else
            source_restored=1
        fi
        cleanup_ok=1
        safe_remove_clone_staging "$operation_id" || cleanup_ok=0
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" FAILED "$source_was_running" "$source_restored" 0 0 || true
        if [ "$source_is_recovery" -eq 1 ] && recovery_manifest_valid "$recovery_id"; then
            recovery_manifest="$(recovery_dir "$recovery_id")/manifest.conf"
            rewrite_recovery_manifest_flags "$recovery_id" "$(record_value pinned "$recovery_manifest")" 0 0 "$(record_value restored "$recovery_manifest")" "$(record_value restoration_state "$recovery_manifest")" || true
        fi
        release_clone_locks
        if [ "$cleanup_ok" -ne 1 ]; then
            failure_code="CLONE_CLEANUP_FAILED"
            failure_message="The clone failed and its operation-owned staging directory could not be cleaned safely."
        fi
        clone_failed "$operation_id" "$source_id" "$target_id" "$failure_code" "$failure_message" "$source_was_running" "$source_restored" "$target_port" || true
        exit 1
    }
    interrupted_clone() {
        trap - HUP INT TERM
        fail_clone "CLONE_INTERRUPTED" "The clone helper was interrupted before publication."
    }
    cleanup_clone_process() {
        rm -f "$pid_file"
        [ "$keep_reservation" -eq 1 ] || release_clone_locks
        [ "$action_lock" -eq 1 ] && release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true
    }
    trap cleanup_clone_process EXIT
    trap interrupted_clone HUP INT TERM

    umask 077
    acquire_clone_action_lock "$operation_id" 20 || exit 1
    action_lock=1
    printf '%s\n' "$$" > "$pid_file" || { clone_failed "$operation_id" "$source_id" "$target_id" "OPERATION_STATE_FAILED" "The clone process identity could not be persisted." false false "$target_port"; exit 1; }
    chmod 600 "$pid_file" || exit 1
    : > "$log" || exit 1
    chmod 600 "$log" || exit 1
    append_operation_log "$operation_id" START "Preparing an isolated clone from $source_id to $target_id"

    if ! acquire_instance_lock "$source_id" "$operation_id" 1; then
        clone_failed "$operation_id" "$source_id" "$target_id" "SOURCE_LOCKED" "Another mutating operation owns the source instance." false true "$target_port"
        exit 1
    fi
    source_lock=1
    if [ "$source_is_recovery" -eq 1 ]; then source_was_running=0; else if valid_process "$source_id"; then source_was_running=1; else source_was_running=0; fi; fi
    if ! acquire_instance_lock "$target_id" "$operation_id" 1; then
        clone_failed "$operation_id" "$source_id" "$target_id" "TARGET_LOCKED" "Another operation owns the target instance identity." "$source_was_running" true "$target_port"
        exit 1
    fi
    target_lock=1

    for managed_parent in "$INSTANCES" "$STATE" "$CLONE_ROOT"; do
        if [ -L "$managed_parent" ] || [ ! -d "$managed_parent" ]; then fail_clone "UNSAFE_CLONE_PATH" "A clone parent is not a real managed directory: $managed_parent"; fi
    done
    [ "$source_id" != "$target_id" ] || fail_clone "INVALID_TARGET" "The clone target must have a new instance identity."
    [ -d "$source" ] && [ ! -L "$source" ] || fail_clone "SOURCE_NOT_INSTALLED" "The source instance directory is missing or unsafe."
    [ ! -e "$target" ] && [ ! -L "$target" ] || fail_clone "TARGET_ALREADY_EXISTS" "The target instance path already exists and was left untouched."
    metadata="$source/metadata.json"; repo="$source/repo"
    [ -f "$metadata" ] && [ ! -L "$metadata" ] && [ -d "$repo" ] && [ ! -L "$repo" ] || fail_clone "SOURCE_NOT_INSTALLED" "The source metadata or repository is missing or unsafe."
    variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    [ "$variant_id" = pocketrisu ] || fail_clone "UNSUPPORTED_VARIANT" "Phase 1 cloning supports PocketRisu only."
    clone_metadata_fields "$metadata" || fail_clone "SOURCE_VALIDATION_FAILED" "The installed source commit metadata is invalid."
    [ "$clone_instance_id" = "$source_id" ] || fail_clone "SOURCE_VALIDATION_FAILED" "The installed source identity does not match its managed path."
    source_commit="$clone_commit"; source_version="$clone_version"
    [ -f "$repo/server/node/server.cjs" ] && [ -f "$repo/dist/index.html" ] && [ -f "$repo/package.json" ] || fail_clone "SOURCE_VALIDATION_FAILED" "The PocketRisu source installation is incomplete."
    pocketrisu_clone_auth_contract_supported "$repo/server/node/server.cjs" || fail_clone "UNSUPPORTED_AUTH_LAYOUT" "This installed PocketRisu version has an unrecognized authentication/session layout and cannot be cloned safely."
    [ "$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)" = "$source_commit" ] || fail_clone "SOURCE_VERSION_MISMATCH" "The source checkout no longer matches its installed commit metadata."
    if [ -L "$repo/save" ] || { [ -e "$repo/save" ] && [ ! -d "$repo/save" ]; }; then
        fail_clone "SOURCE_DATABASE_UNSAFE" "The source SQLite storage path is unsafe."
    fi
    if [ -L "$repo/save/risuai.db" ] || { [ -e "$repo/save/risuai.db" ] && [ ! -f "$repo/save/risuai.db" ]; }; then
        fail_clone "SOURCE_DATABASE_UNSAFE" "The source SQLite database is not a regular managed file."
    fi
    if find "$source" \( -type b -o -type c -o -type p -o -type s \) -print -quit | grep -q .; then
        fail_clone "UNSAFE_SOURCE_ENTRY" "The source contains a device, FIFO, or socket and cannot be cloned safely."
    fi
    if [ "$source_is_recovery" -eq 1 ]; then
        recovery_snapshot_contract "$recovery_id" "$source_id" || fail_clone "RECOVERY_INVALID" "The recovery-point clone source failed integrity or compatibility validation."
        loaded_config_file="$source/manager/server.conf"
        cfg_port="$(config_value port "$loaded_config_file")"; cfg_bind_scope="$(config_value bind_scope "$loaded_config_file")"; cfg_protocol="$(config_value protocol "$loaded_config_file")"
        cfg_body_mb="$(config_value body_limit_mb "$loaded_config_file")"; cfg_save_seconds="$(config_value save_interval_seconds "$loaded_config_file")"
        cfg_snapshot_minutes="$(config_value snapshot_interval_minutes "$loaded_config_file")"; cfg_snapshot_count="$(config_value snapshot_retention_count "$loaded_config_file")"; cfg_snapshot_mb="$(config_value snapshot_max_mb "$loaded_config_file")"
    else
        load_config "$source_id" || fail_clone "INVALID_CONFIG" "The source server configuration is invalid."
    fi
    source_port="$cfg_port"; clone_body_mb="$cfg_body_mb"; clone_save_seconds="$cfg_save_seconds"
    clone_snapshot_minutes="$cfg_snapshot_minutes"; clone_snapshot_count="$cfg_snapshot_count"; clone_snapshot_mb="$cfg_snapshot_mb"
    [ "$target_port" != "$source_port" ] || fail_clone "PORT_CONFLICT" "The clone port must differ from the source port."
    managed_port_available "$target_port" "$target_id" || fail_clone "PORT_CONFLICT" "The selected clone port is reserved or currently unavailable."

    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PREPARED "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The prepared clone journal could not be persisted before stopping the source."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING INSPECTING "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The clone state could not be persisted before stopping the source."
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" STOPPING "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The source-stop recovery intent could not be persisted."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING STOPPING_SOURCE "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The source-stop state could not be persisted."

    if [ "$source_is_recovery" -eq 0 ] && [ "$source_was_running" -eq 1 ]; then
        source_pid="$(read_pid "$source_id")" || fail_clone "SOURCE_INSPECTION_FAILED" "The running source PID could not be read."
        kill -TERM "$source_pid" 2>/dev/null || true
        stop_attempts=0
        while valid_process "$source_id" && [ "$stop_attempts" -lt 30 ]; do stop_attempts=$((stop_attempts + 1)); sleep 1; done
        if valid_process "$source_id"; then fail_clone "SOURCE_STOP_FAILED" "PocketRisu did not stop gracefully; cloning was cancelled without forcing it to exit."; fi
        rm -f "$source/run/server.pid"
        source_stopped=1
    fi
    [ "$source_is_recovery" -eq 0 ] && valid_process "$source_id" && fail_clone "SOURCE_STOP_FAILED" "The source is still running after the snapshot boundary."
    source_stopped=1
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" SOURCE_STOPPED "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The stopped-source recovery state could not be persisted."

    required_kb="$(du -sk "$source" 2>/dev/null | awk 'NR==1 {print $1}')"
    available_kb="$(df -Pk "$CLONE_ROOT" 2>/dev/null | awk 'NR==2 {print $4}')"
    valid_uint "$required_kb" && valid_uint "$available_kb" || fail_clone "DISK_SPACE_CHECK_FAILED" "Available clone storage could not be measured safely."
    [ "$available_kb" -gt $((required_kb + 1024)) ] || fail_clone "INSUFFICIENT_DISK_SPACE" "There is not enough free storage for an independent clone."
    [ ! -e "$operation_dir" ] && [ ! -L "$operation_dir" ] || fail_clone "UNSAFE_CLONE_PATH" "The operation staging path already exists unexpectedly."
    mkdir "$operation_dir" || fail_clone "STAGING_CREATE_FAILED" "The operation-owned staging directory could not be created."
    chmod 700 "$operation_dir" || fail_clone "STAGING_CREATE_FAILED" "The operation-owned staging directory could not be made private."
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" COPYING "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The copy recovery state could not be persisted."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING COPYING "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The copy state could not be persisted."
    cp -a "$source" "$staged" >> "$log" 2>&1 || fail_clone "COPY_FAILED" "The source instance could not be copied into operation-owned staging."
    [ -d "$staged" ] && [ ! -L "$staged" ] || fail_clone "COPY_FAILED" "The staged clone is not a real directory."
    source_manifest="$(tree_manifest_hash "$source" 2>> "$log")" || fail_clone "COPY_VALIDATION_FAILED" "The source copy manifest could not be computed."
    staged_manifest="$(tree_manifest_hash "$staged" 2>> "$log")" || fail_clone "COPY_VALIDATION_FAILED" "The staged copy manifest could not be computed."
    [ "$source_manifest" = "$staged_manifest" ] || fail_clone "COPY_VALIDATION_FAILED" "The staged clone bytes do not match the stopped source snapshot."
    verify_independent_regular_files "$source" "$staged" >> "$log" 2>&1 || fail_clone "MUTABLE_DATA_SHARED" "The clone copy shares a mutable regular-file inode with the source."
    sanitize_clone_symlinks "$source" "$staged" "$target" >> "$log" 2>&1 || fail_clone "UNSAFE_CLONE_SYMLINK" "The source contains a broken or escaping symbolic link."

    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" TRANSFORMING "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The transform recovery state could not be persisted."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING TRANSFORMING "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The transform state could not be persisted."
    rm -rf "$staged/run" "$staged/manager" "$staged/repo/.update-tmp" "$staged/repo/save/model-jobs" "$staged/repo/backups" || fail_clone "CONFIG_TRANSFORM_FAILED" "Runtime state could not be removed from the staged clone."
    rm -f "$staged/repo/save/model-jobs.db" "$staged/repo/save/model-jobs.db-wal" "$staged/repo/save/model-jobs.db-shm" \
        "$staged/repo/save/__sessions" "$staged/repo/save/__jwt_secret" "$staged/repo/save/__instance_id" "$staged/repo/save/__authcode" \
        "$staged/repo/server/node/ssl/certificate/server.key" "$staged/repo/server/node/ssl/certificate/server.crt" || fail_clone "CONFIG_TRANSFORM_FAILED" "Instance-specific credentials or runtime state could not be reset."
    mkdir -p "$staged/run" "$staged/manager" "$staged/repo/save" "$staged/repo/backups" || fail_clone "CONFIG_TRANSFORM_FAILED" "Independent runtime and storage directories could not be created."
    chmod 700 "$staged/run" "$staged/manager" "$staged/repo/save" "$staged/repo/backups" || fail_clone "CONFIG_TRANSFORM_FAILED" "Independent runtime and storage directories could not be made private."
    if [ ! -e "$staged/repo/save/risuai.db" ] && [ ! -L "$staged/repo/save/risuai.db" ]; then
        initialize_clone_sqlite "$staged/repo" >> "$log" 2>&1 || fail_clone "DATABASE_INITIALIZATION_FAILED" "The pristine clone SQLite database could not be initialized."
    fi
    new_jwt_secret="$(od -An -N64 -tx1 /dev/urandom | tr -d ' \n')"
    case "$new_jwt_secret" in ''|*[!0-9a-f]*) fail_clone "CREDENTIAL_GENERATION_FAILED" "A new PocketRisu JWT secret could not be generated." ;; esac
    [ "${#new_jwt_secret}" -eq 128 ] || fail_clone "CREDENTIAL_GENERATION_FAILED" "A new PocketRisu JWT secret had an invalid length."
    new_runtime_id="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || node -e 'process.stdout.write(require("crypto").randomUUID())' 2>/dev/null || true)"
    printf '%s' "$new_runtime_id" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' || fail_clone "CREDENTIAL_GENERATION_FAILED" "A new PocketRisu runtime identity could not be generated."
    printf '%s\n' "$new_jwt_secret" > "$staged/repo/save/__jwt_secret" || fail_clone "CONFIG_TRANSFORM_FAILED" "The new JWT secret could not be persisted."
    printf '%s\n' "$new_runtime_id" > "$staged/repo/save/__instance_id" || fail_clone "CONFIG_TRANSFORM_FAILED" "The new runtime identity could not be persisted."
    chmod 600 "$staged/repo/save/__jwt_secret" "$staged/repo/save/__instance_id" || fail_clone "CONFIG_TRANSFORM_FAILED" "New credentials could not be made private."
    final_backup_path="$target/repo/backups"
    reset_clone_database_paths "$staged/repo" "$final_backup_path" >> "$log" 2>&1 || fail_clone "CONFIG_TRANSFORM_FAILED" "The clone backup path could not be made independent."
    printf '%s\n' "$final_backup_path" > "$staged/repo/save/__backup_path" || fail_clone "CONFIG_TRANSFORM_FAILED" "The independent backup path marker could not be written."
    write_config_file "$staged/manager/server.conf" "$target_port" LOOPBACK HTTP "$clone_body_mb" "$clone_save_seconds" "$clone_snapshot_minutes" "$clone_snapshot_count" "$clone_snapshot_mb" || fail_clone "CONFIG_TRANSFORM_FAILED" "The local-only clone configuration could not be written."
    metadata_temp="$staged/metadata.json.tmp.$$"
    printf '{"instanceId":"%s","variantId":"pocketrisu","commit":"%s","version":"%s","workDir":"repo","entrypoint":"server/node/server.cjs"}\n' "$target_id" "$source_commit" "$(json_escape "$source_version")" > "$metadata_temp" || fail_clone "CONFIG_TRANSFORM_FAILED" "The clone identity metadata could not be generated."
    chmod 600 "$metadata_temp" && mv -f "$metadata_temp" "$staged/metadata.json" || fail_clone "CONFIG_TRANSFORM_FAILED" "The clone identity metadata could not be committed."

    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" VALIDATING "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The validation recovery state could not be persisted."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING VALIDATING "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The validation state could not be persisted."
    clone_metadata_fields "$staged/metadata.json" || fail_clone "VALIDATION_FAILED" "The staged clone metadata is invalid."
    [ "$clone_instance_id" = "$target_id" ] || fail_clone "VALIDATION_FAILED" "The staged clone identity does not match its target path."
    [ "$clone_commit" = "$source_commit" ] && [ "$clone_version" = "$source_version" ] || fail_clone "VERSION_NOT_PRESERVED" "The staged clone did not preserve the installed source version."
    [ "$(git -C "$staged/repo" rev-parse HEAD 2>/dev/null || true)" = "$source_commit" ] || fail_clone "VERSION_NOT_PRESERVED" "The staged repository commit differs from the source."
    [ -f "$staged/repo/server/node/server.cjs" ] && [ -f "$staged/repo/dist/index.html" ] && [ -f "$staged/repo/package.json" ] || fail_clone "VALIDATION_FAILED" "The staged clone is missing required PocketRisu server files."
    validate_clone_sqlite "$staged/repo" >> "$log" 2>&1 || fail_clone "VALIDATION_FAILED" "The staged clone SQLite database failed its integrity check."
    target_db_hash="$(sha256sum "$staged/repo/save/risuai.db" | awk '{print $1}')"
    case "$target_db_hash" in ''|*[!0-9a-f]*) fail_clone "VALIDATION_FAILED" "The staged database hash is invalid." ;; esac
    [ ! -e "$staged/repo/save/__sessions" ] && [ ! -e "$staged/repo/save/__authcode" ] || fail_clone "VALIDATION_FAILED" "A session or server-registration credential remained in the staged clone."
    [ -f "$staged/repo/save/__jwt_secret" ] && [ -f "$staged/repo/save/__instance_id" ] || fail_clone "VALIDATION_FAILED" "The staged clone is missing its regenerated identity."
    [ "$(cat "$staged/repo/save/__jwt_secret")" != "$(cat "$repo/save/__jwt_secret" 2>/dev/null || true)" ] || fail_clone "VALIDATION_FAILED" "The staged clone reused the source JWT secret."
    [ "$(cat "$staged/repo/save/__instance_id")" != "$(cat "$repo/save/__instance_id" 2>/dev/null || true)" ] || fail_clone "VALIDATION_FAILED" "The staged clone reused the source runtime identity."
    marker="$staged/manager/clone-origin.conf"; marker_tmp="$marker.tmp.$$"
    {
        printf 'schema=1\noperation_id=%s\nsource_id=%s\ntarget_id=%s\nport=%s\ncommit=%s\nversion=%s\ndatabase_sha256=%s\n' \
            "$operation_id" "$source_id" "$target_id" "$target_port" "$source_commit" "$source_version" "$target_db_hash"
    } > "$marker_tmp" && chmod 600 "$marker_tmp" && mv -f "$marker_tmp" "$marker" || fail_clone "VALIDATION_FAILED" "The publication ownership marker could not be committed."
    clone_marker_valid "$marker" "$operation_id" "$source_id" "$target_id" || fail_clone "VALIDATION_FAILED" "The publication ownership marker is invalid."

    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" RESTORING_SOURCE "$source_was_running" 0 0 0 || fail_clone "OPERATION_STATE_FAILED" "The source-restoration intent could not be persisted."
    clone_write_state "$operation_id" "$source_id" "$target_id" RUNNING RESTORING_SOURCE "$source_was_running" false "$target_port" || fail_clone "OPERATION_STATE_FAILED" "The source-restoration state could not be persisted."
    if [ "$source_is_recovery" -eq 0 ] && ! restore_clone_source "$source_id" "$source_was_running" "$log"; then fail_clone "SOURCE_RESTART_FAILED" "The validated clone was not published because the previously running source could not be restarted."; fi
    source_restored=1
    if [ "$source_is_recovery" -eq 1 ]; then
        recovery_manifest="$(recovery_dir "$recovery_id")/manifest.conf"
        rewrite_recovery_manifest_flags "$recovery_id" "$(record_value pinned "$recovery_manifest")" 0 0 "$(record_value restored "$recovery_manifest")" "$(record_value restoration_state "$recovery_manifest")" || fail_clone "RECOVERY_METADATA_FAILED" "The recovery-point clone reservation could not be released."
    fi
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" READY_TO_PUBLISH "$source_was_running" 1 0 0 || fail_clone "OPERATION_STATE_FAILED" "The ready-to-publish record could not be persisted."
    recovery_extra=""; [ "$source_is_recovery" -eq 1 ] && recovery_extra=",\"recoveryPointId\":\"$recovery_id\""
    clone_write_state "$operation_id" "$source_id" "$target_id" READY_TO_PUBLISH READY_TO_PUBLISH "$source_was_running" true "$target_port" ",\"commit\":\"$source_commit\",\"version\":\"$(json_escape "$source_version")\"$recovery_extra" || fail_clone "OPERATION_STATE_FAILED" "The ready-to-publish state could not be persisted."
    append_operation_log "$operation_id" READY_TO_PUBLISH "The validated clone is stopped, local-only, and waiting for atomic publication."
    keep_reservation=1
)

recover_interrupted_clone() (
    operation_id="$1"; record="$(clone_record_file "$operation_id")"
    acquire_clone_action_lock "$operation_id" 0 || return 4
    trap 'release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    clone_record_valid "$record" || { emit_error "CLONE_RECORD_INVALID" "The durable clone recovery record is invalid."; return 5; }
    source_id="$(clone_record_value source_id "$record")"; target_id="$(clone_record_value target_id "$record")"
    target_port="$(clone_record_value target_port "$record")"; clone_stage="$(clone_record_value stage "$record")"
    source_was_running="$(clone_record_value source_was_running "$record")"; source_restored="$(clone_record_value source_restored "$record")"
    staged="$(clone_staged_instance "$operation_id")"; target="$(instance_dir "$target_id")"; log="$LOGS/$operation_id.log"
    recovery_extra=""; recovery_id=""; binding="$(recovery_clone_binding_file "$operation_id")"
    if recovery_clone_binding_valid "$binding"; then recovery_id="$(record_value recovery_id "$binding")"; recovery_extra=",\"recoveryPointId\":\"$recovery_id\""; fi
    acquire_instance_lock "$source_id" "$operation_id" 1 || return 4
    if ! acquire_instance_lock "$target_id" "$operation_id" 1; then
        release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
        return 4
    fi
    if [ -d "$target" ] && [ ! -L "$target" ] && clone_marker_valid "$target/manager/clone-origin.conf" "$operation_id" "$source_id" "$target_id"; then
        clone_metadata_fields "$target/metadata.json" || return 5
        [ "$clone_instance_id" = "$target_id" ] || return 5
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PUBLISHED "$source_was_running" 1 1 1 || return 5
        clone_write_state "$operation_id" "$source_id" "$target_id" SUCCEEDED PUBLISHED "$source_was_running" true "$target_port" ",\"commit\":\"$clone_commit\",\"version\":\"$(json_escape "$clone_version")\"$recovery_extra" || return 5
        release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
        release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
        return 0
    fi
    if [ "$clone_stage" = READY_TO_PUBLISH ] && [ "$source_restored" = 1 ] && [ -d "$staged" ] && clone_marker_valid "$staged/manager/clone-origin.conf" "$operation_id" "$source_id" "$target_id"; then
        clone_metadata_fields "$staged/metadata.json" || return 5
        [ "$clone_instance_id" = "$target_id" ] || return 5
        clone_write_state "$operation_id" "$source_id" "$target_id" READY_TO_PUBLISH READY_TO_PUBLISH "$source_was_running" true "$target_port" ",\"commit\":\"$clone_commit\",\"version\":\"$(json_escape "$clone_version")\"$recovery_extra" || return 5
        return 0
    fi
    recovered=1
    if [ "$source_was_running" = 1 ] && ! restore_clone_source "$source_id" 1 "$log"; then recovered=0; fi
    [ "$source_was_running" = unknown ] && recovered=1
    cleanup_ok=1; safe_remove_clone_staging "$operation_id" || cleanup_ok=0
    if [ -n "$recovery_id" ] && recovery_manifest_valid "$recovery_id"; then
        recovery_manifest="$(recovery_dir "$recovery_id")/manifest.conf"
        rewrite_recovery_manifest_flags "$recovery_id" "$(record_value pinned "$recovery_manifest")" 0 0 "$(record_value restored "$recovery_manifest")" "$(record_value restoration_state "$recovery_manifest")" || cleanup_ok=0
    fi
    release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
    release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
    if [ "$recovered" -eq 1 ] && [ "$cleanup_ok" -eq 1 ]; then
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" FAILED "$source_was_running" 1 0 0 || return 5
        clone_failed "$operation_id" "$source_id" "$target_id" "CLONE_PROCESS_LOST" "The clone process stopped before publication; owned staging was discarded and the source state was restored." "$source_was_running" true "$target_port"
    elif [ "$recovered" -ne 1 ]; then
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" FAILED "$source_was_running" 0 0 0 || true
        clone_failed "$operation_id" "$source_id" "$target_id" "SOURCE_RESTART_FAILED" "The interrupted clone was not published and the source could not be restarted automatically." "$source_was_running" false "$target_port"
    else
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" FAILED "$source_was_running" 1 0 0 || true
        clone_failed "$operation_id" "$source_id" "$target_id" "CLONE_CLEANUP_FAILED" "The interrupted clone was not published, but its owned staging could not be cleaned safely." "$source_was_running" true "$target_port"
    fi
)

command_clone_publish() (
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "clone-publish requires an operation ID."; exit 2; }
    operation_id="$1"; valid_operation_id "$operation_id" || { emit_error "INVALID_OPERATION_ID" "The operation ID is invalid."; exit 2; }
    acquire_clone_action_lock "$operation_id" 0 || { emit_error "CLONE_BUSY" "Another process is changing this clone operation."; exit 4; }
    trap 'release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    record="$(clone_record_file "$operation_id")"; clone_record_valid "$record" || { emit_error "CLONE_RECORD_INVALID" "The durable clone record is missing or invalid."; exit 3; }
    source_id="$(clone_record_value source_id "$record")"; target_id="$(clone_record_value target_id "$record")"; target_port="$(clone_record_value target_port "$record")"
    clone_stage="$(clone_record_value stage "$record")"; source_was_running="$(clone_record_value source_was_running "$record")"; source_restored="$(clone_record_value source_restored "$record")"
    staged="$(clone_staged_instance "$operation_id")"; target="$(instance_dir "$target_id")"
    recovery_extra=""; binding="$(recovery_clone_binding_file "$operation_id")"
    if recovery_clone_binding_valid "$binding"; then recovery_extra=",\"recoveryPointId\":\"$(record_value recovery_id "$binding")\""; fi
    acquire_instance_lock "$source_id" "$operation_id" 1 || { emit_error "SOURCE_LOCKED" "The clone source reservation is not owned by this operation."; exit 4; }
    acquire_instance_lock "$target_id" "$operation_id" 1 || { release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true; emit_error "TARGET_LOCKED" "The clone target reservation is not owned by this operation."; exit 4; }
    if [ -d "$target" ] && [ ! -L "$target" ] && clone_marker_valid "$target/manager/clone-origin.conf" "$operation_id" "$source_id" "$target_id"; then
        clone_metadata_fields "$target/metadata.json" || { emit_error "PUBLISHED_CLONE_INVALID" "The published clone metadata is invalid."; exit 5; }
        [ "$clone_instance_id" = "$target_id" ] || { emit_error "PUBLISHED_CLONE_INVALID" "The published clone identity does not match its target path."; exit 5; }
        write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PUBLISHED "$source_was_running" 1 1 1 || exit 5
        clone_write_state "$operation_id" "$source_id" "$target_id" SUCCEEDED PUBLISHED "$source_was_running" true "$target_port" ",\"commit\":\"$clone_commit\",\"version\":\"$(json_escape "$clone_version")\"$recovery_extra" || exit 5
        release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
        release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
        cat "$(state_file "$operation_id")"
        exit 0
    fi
    [ "$clone_stage" = READY_TO_PUBLISH ] && [ "$source_restored" = 1 ] || { emit_error "CLONE_NOT_READY" "The clone is not safely ready for publication."; exit 4; }
    [ ! -e "$target" ] && [ ! -L "$target" ] || { emit_error "PUBLICATION_CONFLICT" "The target path is occupied by another instance."; exit 4; }
    clone_marker_valid "$staged/manager/clone-origin.conf" "$operation_id" "$source_id" "$target_id" || { emit_error "VALIDATION_FAILED" "The staged publication marker is invalid."; exit 5; }
    clone_metadata_fields "$staged/metadata.json" || { emit_error "VALIDATION_FAILED" "The staged clone metadata is invalid."; exit 5; }
    [ "$clone_instance_id" = "$target_id" ] || { emit_error "VALIDATION_FAILED" "The staged clone identity does not match its target path."; exit 5; }
    marker_commit="$(config_value commit "$staged/manager/clone-origin.conf")"; marker_hash="$(config_value database_sha256 "$staged/manager/clone-origin.conf")"
    [ "$clone_commit" = "$marker_commit" ] && [ "$(git -C "$staged/repo" rev-parse HEAD 2>/dev/null || true)" = "$marker_commit" ] || { emit_error "VERSION_NOT_PRESERVED" "The staged clone version changed before publication."; exit 5; }
    validate_clone_sqlite "$staged/repo" || { emit_error "VALIDATION_FAILED" "The staged database failed its final integrity check."; exit 5; }
    [ "$(sha256sum "$staged/repo/save/risuai.db" | awk '{print $1}')" = "$marker_hash" ] || { emit_error "VALIDATION_FAILED" "The staged database changed after validation."; exit 5; }
    managed_port_available "$target_port" "$target_id" || { emit_error "PORT_CONFLICT" "The clone port became unavailable before publication."; exit 4; }
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PUBLISHING "$source_was_running" 1 1 0 || { emit_error "OPERATION_STATE_FAILED" "Publication intent could not be persisted."; exit 5; }
    mv "$staged" "$target" || { emit_error "PUBLICATION_FAILED" "The validated clone could not be atomically published."; exit 5; }
    if ! clone_marker_valid "$target/manager/clone-origin.conf" "$operation_id" "$source_id" "$target_id"; then emit_error "PUBLICATION_FAILED" "The published target lost its ownership marker."; exit 5; fi
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PUBLISHED "$source_was_running" 1 1 1 || true
    clone_write_state "$operation_id" "$source_id" "$target_id" SUCCEEDED PUBLISHED "$source_was_running" true "$target_port" ",\"commit\":\"$clone_commit\",\"version\":\"$(json_escape "$clone_version")\"$recovery_extra" || true
    rmdir "$(clone_operation_dir "$operation_id")" 2>/dev/null || true
    release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
    release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
    cat "$(state_file "$operation_id")"
)

command_clone_abort() (
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "clone-abort requires an operation ID."; exit 2; }
    operation_id="$1"; valid_operation_id "$operation_id" || { emit_error "INVALID_OPERATION_ID" "The operation ID is invalid."; exit 2; }
    acquire_clone_action_lock "$operation_id" 0 || { emit_error "CLONE_BUSY" "Another process is changing this clone operation."; exit 4; }
    trap 'release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    record="$(clone_record_file "$operation_id")"; clone_record_valid "$record" || { emit_error "CLONE_RECORD_INVALID" "The durable clone record is missing or invalid."; exit 3; }
    source_id="$(clone_record_value source_id "$record")"; target_id="$(clone_record_value target_id "$record")"; target_port="$(clone_record_value target_port "$record")"
    source_was_running="$(clone_record_value source_was_running "$record")"; published="$(clone_record_value published "$record")"
    binding="$(recovery_clone_binding_file "$operation_id")"; recovery_id=""
    recovery_clone_binding_valid "$binding" && recovery_id="$(record_value recovery_id "$binding")"
    [ "$published" = 0 ] || { emit_error "CLONE_ALREADY_PUBLISHED" "A published clone cannot be removed by clone-abort."; exit 4; }
    acquire_instance_lock "$source_id" "$operation_id" 1 || { emit_error "SOURCE_LOCKED" "The clone source reservation is busy."; exit 4; }
    acquire_instance_lock "$target_id" "$operation_id" 1 || { release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true; emit_error "TARGET_LOCKED" "The clone target reservation is busy."; exit 4; }
    target="$(instance_dir "$target_id")"
    [ ! -e "$target" ] && [ ! -L "$target" ] || { emit_error "PUBLICATION_CONFLICT" "The target instance path exists; clone-abort will not delete it."; exit 4; }
    source_restored=1
    if [ "$source_was_running" = 1 ] && ! restore_clone_source "$source_id" 1 "$LOGS/$operation_id.log"; then source_restored=0; fi
    safe_remove_clone_staging "$operation_id" || { emit_error "CLONE_CLEANUP_FAILED" "The operation-owned staging directory could not be cleaned safely."; exit 5; }
    if [ -n "$recovery_id" ] && recovery_manifest_valid "$recovery_id"; then
        recovery_manifest="$(recovery_dir "$recovery_id")/manifest.conf"
        rewrite_recovery_manifest_flags "$recovery_id" "$(record_value pinned "$recovery_manifest")" 0 0 "$(record_value restored "$recovery_manifest")" "$(record_value restoration_state "$recovery_manifest")" || { emit_error "RECOVERY_METADATA_FAILED" "The recovery clone reservation could not be released."; exit 5; }
    fi
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" ABORTED "$source_was_running" "$source_restored" 0 0 || exit 5
    release_instance_lock "$target_id" "$operation_id" >/dev/null 2>&1 || true
    release_instance_lock "$source_id" "$operation_id" >/dev/null 2>&1 || true
    if [ "$source_restored" -eq 1 ]; then
        clone_failed "$operation_id" "$source_id" "$target_id" "CLONE_ABORTED" "The provisional clone was safely discarded." "$source_was_running" true "$target_port"
        printf '{"ok":true,"operation":"clone-abort","operationId":"%s","sourceInstanceId":"%s","targetInstanceId":"%s","sourceRestored":true}\n' "$operation_id" "$source_id" "$target_id"
    else
        clone_failed "$operation_id" "$source_id" "$target_id" "SOURCE_RESTART_FAILED" "The provisional clone was discarded, but the source could not be restarted." "$source_was_running" false "$target_port"
        cat "$(state_file "$operation_id")"
        exit 5
    fi
)

metadata_field() {
    key="$1"; file="$2"
    sed -n "s/.*\"${key}\":\"\([^\"]*\)\".*/\1/p" "$file" | sed -n '1p'
}

instance_update_contract() {
    instance_id="$1"; instance="$(instance_dir "$instance_id")"; repo="$instance/repo"; metadata="$instance/metadata.json"
    [ -d "$instance" ] && [ ! -L "$instance" ] && [ -d "$repo/.git" ] && [ ! -L "$repo" ] && [ -f "$metadata" ] && [ ! -L "$metadata" ] || return 1
    [ "$(metadata_field instanceId "$metadata")" = "$instance_id" ] || return 1
    [ "$(metadata_field variantId "$metadata")" = pocketrisu ] || return 1
    metadata_commit="$(metadata_field commit "$metadata")"; valid_commit "$metadata_commit" || return 1
    repo_commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"; valid_commit "$repo_commit" || return 1
    [ "$metadata_commit" = "$repo_commit" ] || return 1
    [ -f "$repo/server/node/server.cjs" ] && [ -f "$repo/package.json" ]
}

instance_size_bytes() {
    size_kb="$(du -sk "$1" 2>/dev/null | awk '{print $1}')"
    valid_uint "$size_kb" || return 1
    printf '%s' "$((size_kb * 1024))"
}

available_bytes() {
    available_kb="$(df -Pk "$1" 2>/dev/null | awk 'NR==2 {print $4}')"
    valid_uint "$available_kb" || return 1
    printf '%s' "$((available_kb * 1024))"
}

command_recovery_estimate() (
    [ "$#" -eq 1 ] || { emit_error INVALID_ARGUMENT "recovery-estimate requires an instance ID."; return 2; }
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    instance_id="$1"; valid_identifier "$instance_id" || { emit_error INVALID_INSTANCE_ID "The instance ID is invalid."; return 2; }
    instance_update_contract "$instance_id" || { emit_error UPDATE_NOT_SUPPORTED "Only a consistent trusted PocketRisu Git instance can be updated."; return 3; }
    size="$(instance_size_bytes "$(instance_dir "$instance_id")")" || { emit_error SIZE_ESTIMATE_FAILED "The instance size could not be measured."; return 4; }
    available="$(available_bytes "$ROOT")" || { emit_error SIZE_ESTIMATE_FAILED "Available storage could not be measured."; return 4; }
    required="$((size * 3 + 67108864))"
    current_version="$(metadata_field version "$(instance_dir "$instance_id")/metadata.json")"
    printf '{"ok":true,"operation":"recovery-estimate","instanceId":"%s","currentCommit":"%s","currentVersion":"%s","size":%s,"requiredBytes":%s,"availableBytes":%s}\n' \
        "$instance_id" "$metadata_commit" "$(json_escape "$current_version")" "$size" "$required" "$available"
)

safe_remove_update_path() (
    path="$1"; expected_parent="$2"
    case "$path" in "$expected_parent"/*) ;; *) return 1 ;; esac
    [ -L "$expected_parent" ] && return 1
    if [ -L "$path" ] || { [ -e "$path" ] && [ ! -d "$path" ]; }; then return 1; fi
    [ -e "$path" ] || return 0
    rm -rf "$path"
)

write_recovery_manifest() (
    operation_id="$1"; instance_id="$2"; recovery_id="$3"; source_sha="$4"; target_sha="$5"; created_ms="$6"
    recovery="$(recovery_dir "$recovery_id")"; copy="$recovery/instance"; manifest="$recovery/manifest.conf"; files="$recovery/files.manifest"
    node -e 'const f=require("fs"),p=require("path"),c=require("crypto"),r=p.resolve(process.argv[1]);function walk(d,x=""){for(const n of f.readdirSync(d).sort()){const q=p.join(d,n),z=x?x+"/"+n:n,s=f.lstatSync(q);if(s.isDirectory()){console.log("d\\t"+z);walk(q,z)}else if(s.isFile()){const h=c.createHash("sha256").update(f.readFileSync(q)).digest("hex");console.log("f\\t"+s.size+"\\t"+h+"\\t"+z)}else if(s.isSymbolicLink())console.log("l\\t"+f.readlinkSync(q)+"\\t"+z);else process.exit(3)}}walk(r);' "$copy" > "$files" || return 1
    chmod 600 "$files"
    files_sha="$(sha256sum "$files" | awk '{print $1}')" || return 1
    tree_sha="$(tree_manifest_hash "$copy")" || return 1
    size="$(instance_size_bytes "$copy")" || return 1
    if [ -d "$copy/repo/save" ]; then data_sha="$(tree_manifest_hash "$copy/repo/save")" || return 1; else data_sha="$(printf empty | sha256sum | awk '{print $1}')"; fi
    [ -d "$copy/manager" ] || return 1
    config_sha="$(tree_manifest_hash "$copy/manager")" || return 1
    case "$files_sha:$tree_sha:$data_sha:$config_sha" in *[!0-9a-f:]*) return 1 ;; esac
    [ "${#files_sha}" -eq 64 ] && [ "${#tree_sha}" -eq 64 ] && [ "${#data_sha}" -eq 64 ] && [ "${#config_sha}" -eq 64 ] || return 1
    {
        printf 'format_version=1\nmanager_owned=1\noperation_id=%s\ninstance_id=%s\nrecovery_id=%s\n' "$operation_id" "$instance_id" "$recovery_id"
        printf 'source_sha=%s\ntarget_sha=%s\ncreated_ms=%s\ncompleted_ms=0\nexpires_ms=0\n' "$source_sha" "$target_sha" "$created_ms"
        printf 'size=%s\nfiles_sha256=%s\ntree_sha256=%s\ndata_digest_sha256=%s\nconfiguration_digest_sha256=%s\n' "$size" "$files_sha" "$tree_sha" "$data_sha" "$config_sha"
        printf 'pinned=0\nin_use=1\nrecovery_required=1\nrestored=0\nrestoration_state=NOT_REQUIRED\n'
    } > "$manifest"
    chmod 600 "$manifest"
    sha256sum "$manifest" | awk '{print $1}' > "$recovery/manifest.sha256"
    chmod 600 "$recovery/manifest.sha256"
)

recovery_manifest_valid() (
    recovery_id="$1"; recovery="$(recovery_dir "$recovery_id")"; manifest="$recovery/manifest.conf"; copy="$recovery/instance"
    [ -d "$recovery" ] && [ ! -L "$recovery" ] && [ -d "$copy" ] && [ ! -L "$copy" ] && [ -f "$manifest" ] && [ ! -L "$manifest" ] || return 1
    [ "$(record_value format_version "$manifest")" = 1 ] && [ "$(record_value manager_owned "$manifest")" = 1 ] || return 1
    [ "$(record_value recovery_id "$manifest")" = "$recovery_id" ] || return 1
    valid_operation_id "$(record_value operation_id "$manifest")" || return 1
    valid_identifier "$(record_value instance_id "$manifest")" || return 1
    valid_commit "$(record_value source_sha "$manifest")" || return 1
    valid_commit "$(record_value target_sha "$manifest")" || return 1
    for manifest_number in created_ms completed_ms expires_ms size; do valid_uint "$(record_value "$manifest_number" "$manifest")" || return 1; done
    case "$(record_value pinned "$manifest"):$(record_value in_use "$manifest"):$(record_value recovery_required "$manifest"):$(record_value restored "$manifest")" in
        [01]:[01]:[01]:[01]) ;;
        *) return 1 ;;
    esac
    case "$(record_value restoration_state "$manifest")" in NOT_REQUIRED|RESTORING|RESTORED|RESTORE_FAILED) ;; *) return 1 ;; esac
    expected="$(sed -n '1p' "$recovery/manifest.sha256" 2>/dev/null || true)"; actual="$(sha256sum "$manifest" | awk '{print $1}')"
    case "$expected:$actual" in *[!0-9a-f:]*) return 1 ;; esac
    [ "${#expected}" -eq 64 ] && [ "${#actual}" -eq 64 ] || return 1
    [ "$expected" = "$actual" ] || return 1
    [ -f "$recovery/files.manifest" ] && [ ! -L "$recovery/files.manifest" ] || return 1
    [ "$(sha256sum "$recovery/files.manifest" | awk '{print $1}')" = "$(record_value files_sha256 "$manifest")" ] || return 1
    [ "$(tree_manifest_hash "$copy")" = "$(record_value tree_sha256 "$manifest")" ] || return 1
    [ "$(instance_size_bytes "$copy")" = "$(record_value size "$manifest")" ] || return 1
    if [ -d "$copy/repo/save" ]; then verified_data_sha="$(tree_manifest_hash "$copy/repo/save")" || return 1; else verified_data_sha="$(printf empty | sha256sum | awk '{print $1}')"; fi
    [ "$verified_data_sha" = "$(record_value data_digest_sha256 "$manifest")" ] || return 1
    [ -d "$copy/manager" ] && [ "$(tree_manifest_hash "$copy/manager")" = "$(record_value configuration_digest_sha256 "$manifest")" ] || return 1
)

recovery_manifest_matches_operation() (
    recovery_id="$1"; expected_operation_id="$2"; expected_instance_id="$3"; expected_source_sha="$4"; expected_target_sha="$5"
    recovery_manifest_valid "$recovery_id" || return 1
    manifest="$(recovery_dir "$recovery_id")/manifest.conf"
    [ "$(record_value operation_id "$manifest")" = "$expected_operation_id" ] &&
        [ "$(record_value instance_id "$manifest")" = "$expected_instance_id" ] &&
        [ "$(record_value source_sha "$manifest")" = "$expected_source_sha" ] &&
        [ "$(record_value target_sha "$manifest")" = "$expected_target_sha" ]
)

refresh_recovery_manifest() (
    recovery_id="$1"; completed_ms="$2"; expires_ms="$3"; in_use="$4"; required="$5"
    restored="${6:-0}"; restoration_state="${7:-NOT_REQUIRED}"
    recovery="$(recovery_dir "$recovery_id")"; manifest="$recovery/manifest.conf"; tmp="$manifest.tmp.$$"
    sed -e "s/^completed_ms=.*/completed_ms=$completed_ms/" -e "s/^expires_ms=.*/expires_ms=$expires_ms/" \
        -e "s/^in_use=.*/in_use=$in_use/" -e "s/^recovery_required=.*/recovery_required=$required/" \
        -e "s/^restored=.*/restored=$restored/" -e "s/^restoration_state=.*/restoration_state=$restoration_state/" "$manifest" > "$tmp" || return 1
    chmod 600 "$tmp" && mv -f "$tmp" "$manifest" || return 1
    sync -f "$manifest" 2>/dev/null || sync "$manifest" 2>/dev/null || true
    sha256sum "$manifest" | awk '{print $1}' > "$recovery/manifest.sha256"
    chmod 600 "$recovery/manifest.sha256"
    sync -f "$recovery/manifest.sha256" 2>/dev/null || sync "$recovery/manifest.sha256" 2>/dev/null || true
)

rewrite_recovery_manifest_flags() (
    recovery_id="$1"; pinned="$2"; in_use="$3"; required="$4"; restored="$5"; restoration_state="$6"
    recovery="$(recovery_dir "$recovery_id")"; manifest="$recovery/manifest.conf"; tmp="$manifest.tmp.$$"
    recovery_manifest_valid "$recovery_id" || return 1
    sed -e "s/^pinned=.*/pinned=$pinned/" -e "s/^in_use=.*/in_use=$in_use/" \
        -e "s/^recovery_required=.*/recovery_required=$required/" -e "s/^restored=.*/restored=$restored/" \
        -e "s/^restoration_state=.*/restoration_state=$restoration_state/" "$manifest" > "$tmp" || return 1
    chmod 600 "$tmp" && mv -f "$tmp" "$manifest" || { rm -f "$tmp"; return 1; }
    sync -f "$manifest" 2>/dev/null || sync "$manifest" 2>/dev/null || true
    sha256sum "$manifest" | awk '{print $1}' > "$recovery/manifest.sha256" || return 1
    chmod 600 "$recovery/manifest.sha256" || return 1
    sync -f "$recovery/manifest.sha256" 2>/dev/null || sync "$recovery/manifest.sha256" 2>/dev/null || true
)

recovery_snapshot_contract() {
    recovery_id="$1"; expected_instance_id="$2"; recovery="$(recovery_dir "$recovery_id")"; snapshot="$recovery/instance"
    recovery_manifest_valid "$recovery_id" || return 1
    manifest="$recovery/manifest.conf"
    [ "$(record_value instance_id "$manifest")" = "$expected_instance_id" ] || return 1
    clone_metadata_fields "$snapshot/metadata.json" || return 1
    [ "$clone_instance_id" = "$expected_instance_id" ] && [ "$clone_variant_id" = pocketrisu ] || return 1
    [ "$clone_commit" = "$(record_value source_sha "$manifest")" ] || return 1
    [ "$(git -C "$snapshot/repo" rev-parse HEAD 2>/dev/null || true)" = "$clone_commit" ] || return 1
    [ -f "$snapshot/repo/server/node/server.cjs" ] && [ -f "$snapshot/repo/package.json" ] || return 1
    config="$snapshot/manager/server.conf"
    [ -f "$config" ] && [ ! -L "$config" ] && [ "$(wc -l < "$config" | tr -d ' ')" = 9 ] || return 1
    [ "$(config_value schema "$config")" = 1 ] || return 1
    valid_config_values "$(config_value port "$config")" "$(config_value bind_scope "$config")" "$(config_value protocol "$config")" \
        "$(config_value body_limit_mb "$config")" "$(config_value save_interval_seconds "$config")" \
        "$(config_value snapshot_interval_minutes "$config")" "$(config_value snapshot_retention_count "$config")" \
        "$(config_value snapshot_max_mb "$config")"
}

recovery_owner_terminal() (
    recovery_id="$1"; manifest="$(recovery_dir "$recovery_id")/manifest.conf"
    recovery_manifest_valid "$recovery_id" || return 1
    owner="$(record_value operation_id "$manifest")"; instance_id="$(record_value instance_id "$manifest")"
    update_record="$(update_record_file "$owner")"
    if update_record_valid "$update_record" &&
       [ "$(record_value recovery_id "$update_record")" = "$recovery_id" ] &&
       [ "$(record_value instance_id "$update_record")" = "$instance_id" ]; then
        case "$(record_value stage "$update_record")" in SUCCEEDED|FAILED|ROLLED_BACK) return 0 ;; esac
    fi
    rollback_record="$(rollback_record_file "$owner")"
    if rollback_record_valid "$rollback_record" &&
       [ "$(record_value pre_recovery_id "$rollback_record")" = "$recovery_id" ] &&
       [ "$(record_value instance_id "$rollback_record")" = "$instance_id" ]; then
        case "$(record_value stage "$rollback_record")" in SUCCEEDED|FAILED) return 0 ;; esac
    fi
    return 1
)

command_recovery_inspect() (
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    [ "$#" -eq 2 ] || { emit_error INVALID_ARGUMENT "recovery-inspect requires instance and recovery point IDs."; return 2; }
    instance_id="$1"; recovery_id="$2"
    valid_identifier "$instance_id" && valid_recovery_id "$recovery_id" || { emit_error INVALID_ARGUMENT "The recovery inspection identifiers are invalid."; return 2; }
    instance_update_contract "$instance_id" || { emit_error UPDATE_NOT_SUPPORTED "Only a consistent trusted PocketRisu instance can be inspected."; return 3; }
    current_sha="$repo_commit"; current_version="$(metadata_field version "$(instance_dir "$instance_id")/metadata.json")"
    recovery_snapshot_contract "$recovery_id" "$instance_id" || { emit_error RECOVERY_INVALID "The selected recovery point failed format, integrity, target, or dependency validation."; return 3; }
    recovery_owner_terminal "$recovery_id" || { emit_error RECOVERY_NOT_MANAGER_OWNED "No terminal Manager journal owns the selected recovery point."; return 3; }
    manifest="$(recovery_dir "$recovery_id")/manifest.conf"; target_sha="$clone_commit"; target_version="$clone_version"
    current_size="$(instance_size_bytes "$(instance_dir "$instance_id")")" || { emit_error SIZE_ESTIMATE_FAILED "The current instance size could not be measured."; return 4; }
    selected_size="$(record_value size "$manifest")"; available="$(available_bytes "$ROOT")" || { emit_error SIZE_ESTIMATE_FAILED "Available storage could not be measured."; return 4; }
    required="$((current_size + selected_size + 67108864))"
    if [ -d "$(instance_dir "$instance_id")/repo/save" ]; then current_data="$(tree_manifest_hash "$(instance_dir "$instance_id")/repo/save")" || return 4; else current_data="$(printf empty | sha256sum | awk '{print $1}')"; fi
    current_config="$(tree_manifest_hash "$(instance_dir "$instance_id")/manager")" || return 4
    data_sha="$(record_value data_digest_sha256 "$manifest")"; config_sha="$(record_value configuration_digest_sha256 "$manifest")"
    data_changed=false; config_changed=false; [ "$current_data" = "$data_sha" ] || data_changed=true; [ "$current_config" = "$config_sha" ] || config_changed=true
    printf '{"ok":true,"operation":"recovery-inspect","instanceId":"%s","recoveryPointId":"%s","currentCommit":"%s","currentVersion":"%s","targetCommit":"%s","targetVersion":"%s","size":%s,"currentSize":%s,"requiredBytes":%s,"availableBytes":%s,"manifestSha256":"%s","dataDigestSha256":"%s","configurationDigestSha256":"%s","currentDataDigestSha256":"%s","currentConfigurationDigestSha256":"%s","dataChanged":%s,"configurationChanged":%s}\n' \
        "$instance_id" "$recovery_id" "$current_sha" "$(json_escape "$current_version")" "$target_sha" "$(json_escape "$target_version")" "$selected_size" "$current_size" "$required" "$available" \
        "$(sed -n '1p' "$(recovery_dir "$recovery_id")/manifest.sha256")" "$data_sha" "$config_sha" "$current_data" "$current_config" "$data_changed" "$config_changed"
)

command_recovery_pin() (
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    [ "$#" -eq 2 ] || { emit_error INVALID_ARGUMENT "recovery-pin requires a recovery point ID and true or false."; return 2; }
    recovery_id="$1"; requested="$2"; valid_recovery_id "$recovery_id" || { emit_error INVALID_RECOVERY_ID "The recovery point ID is invalid."; return 2; }
    case "$requested" in true|1) pinned=1; json_pinned=true ;; false|0) pinned=0; json_pinned=false ;; *) emit_error INVALID_ARGUMENT "Pinned must be true or false."; return 2 ;; esac
    lock="$LOCKS/recovery-action-$recovery_id"; mkdir "$lock" 2>/dev/null || { emit_error RECOVERY_IN_USE "Another action owns this recovery point."; return 4; }
    trap 'rmdir "$lock" 2>/dev/null || true' EXIT HUP INT TERM
    recovery_manifest_valid "$recovery_id" && recovery_owner_terminal "$recovery_id" || { emit_error RECOVERY_NOT_MANAGER_OWNED "The recovery point is not a verified Manager-owned point."; return 3; }
    manifest="$(recovery_dir "$recovery_id")/manifest.conf"
    [ "$(record_value in_use "$manifest")" = 0 ] && [ "$(record_value recovery_required "$manifest")" = 0 ] || { emit_error RECOVERY_IN_USE "A recovery operation still requires this point."; return 4; }
    rewrite_recovery_manifest_flags "$recovery_id" "$pinned" 0 0 "$(record_value restored "$manifest")" "$(record_value restoration_state "$manifest")" || { emit_error RECOVERY_METADATA_FAILED "The pinned state could not be persisted."; return 5; }
    printf '{"ok":true,"operation":"recovery-pin","recoveryPointId":"%s","pinned":%s}\n' "$recovery_id" "$json_pinned"
)

write_updated_metadata() (
    metadata="$1"; target_sha="$2"; version="$3"; tmp="$metadata.tmp.$$"
    node -e 'const f=require("fs"),p=process.argv[1],o=JSON.parse(f.readFileSync(p,"utf8"));o.commit=process.argv[2];o.version=process.argv[3];f.writeFileSync(process.argv[4],JSON.stringify(o)+"\n",{mode:0o600,flag:"wx"})' "$metadata" "$target_sha" "$version" "$tmp" || return 1
    chmod 600 "$tmp" && mv -f "$tmp" "$metadata"
)

update_graceful_stop() (
    instance_id="$1"; run="$(instance_dir "$instance_id")/run"
    valid_process "$instance_id" || { rm -f "$run/server.pid"; return 0; }
    pid="$(read_pid "$instance_id")"; kill -TERM "$pid" 2>/dev/null || return 1
    attempts=0
    while valid_process "$instance_id" && [ "$attempts" -lt 30 ]; do attempts=$((attempts + 1)); sleep 1; done
    valid_process "$instance_id" && return 1
    rm -f "$run/server.pid"
)

restore_update_recovery() (
    operation_id="$1"; instance_id="$2"; recovery_id="$3"; was_running="$4"
    instance="$(instance_dir "$instance_id")"; recovery="$(recovery_dir "$recovery_id")"; operation_dir="$(update_operation_dir "$operation_id")"
    record="$(update_record_file "$operation_id")"; update_record_valid "$record" || return 1
    source_sha="$(record_value source_sha "$record")"; target_sha="$(record_value target_sha "$record")"
    [ "$(record_value instance_id "$record")" = "$instance_id" ] && [ "$(record_value recovery_id "$record")" = "$recovery_id" ] || return 1
    recovery_manifest_matches_operation "$recovery_id" "$operation_id" "$instance_id" "$source_sha" "$target_sha" || return 1
    mkdir -p "$operation_dir" || return 1
    displaced="$operation_dir/displaced-instance"; restore_ready="$operation_dir/restore-ready"
    # Reconcile a process death between the two rename operations before
    # preparing another verified restore copy.
    if [ ! -e "$instance" ] && [ -d "$displaced" ] && [ ! -L "$displaced" ]; then mv "$displaced" "$instance" || return 1; fi
    if [ -d "$instance" ] && [ "$(tree_manifest_hash "$instance" 2>/dev/null || true)" = "$(record_value tree_sha256 "$recovery/manifest.conf")" ]; then
        safe_remove_update_path "$displaced" "$operation_dir" || return 1
        if [ "$was_running" = 1 ]; then command_start "$instance_id" >/dev/null 2>&1 || return 1; else command_stop "$instance_id" >/dev/null 2>&1 || true; fi
        return 0
    fi
    safe_remove_update_path "$restore_ready" "$operation_dir" || return 1
    safe_remove_update_path "$displaced" "$operation_dir" || return 1
    mkdir "$restore_ready" || return 1
    cp -a "$recovery/instance/." "$restore_ready/" || { safe_remove_update_path "$restore_ready" "$operation_dir" || true; return 1; }
    [ "$(tree_manifest_hash "$restore_ready")" = "$(record_value tree_sha256 "$recovery/manifest.conf")" ] || { safe_remove_update_path "$restore_ready" "$operation_dir" || true; return 1; }
    valid_process "$instance_id" && command_stop "$instance_id" >/dev/null 2>&1 || true
    [ -d "$instance" ] && [ ! -L "$instance" ] || return 1
    mv "$instance" "$displaced" || return 1
    if ! mv "$restore_ready" "$instance"; then mv "$displaced" "$instance" 2>/dev/null || true; return 1; fi
    rm -f "$instance/run/server.pid"
    if [ "$(tree_manifest_hash "$instance")" != "$(record_value tree_sha256 "$recovery/manifest.conf")" ]; then
        safe_remove_update_path "$instance" "$INSTANCES" || true
        mv "$displaced" "$instance" 2>/dev/null || true
        return 1
    fi
    if [ "$was_running" = 1 ]; then
        command_start "$instance_id" >/dev/null 2>&1 || return 1
        valid_process "$instance_id" || return 1
    else
        command_stop "$instance_id" >/dev/null 2>&1 || true
        valid_process "$instance_id" && return 1
    fi
    safe_remove_update_path "$displaced" "$operation_dir" || return 1
)

do_update() (
    operation_id="$1"; instance_id="$2"; variant_id="$3"; target_sha="$4"; recovery_id="$5"; retention="$6"
    instance="$(instance_dir "$instance_id")"; repo="$instance/repo"; operation_dir="$(update_operation_dir "$operation_id")"; staged="$operation_dir/staged"
    recovery="$(recovery_dir "$recovery_id")"; record="$(update_record_file "$operation_id")"; log="$LOGS/$operation_id.log"; pid_file="$(operation_pid_file "$operation_id")"
    was_running=unknown; source_sha=0000000000000000000000000000000000000000; current_version=""; target_version=""
    recovery_verified=0; recovery_required=0; completion_ms=0; expiration_ms=0; final=0; required=0; available=0
    cleanup_update_process() { rm -f "$pid_file"; release_instance_lock "$instance_id" "$operation_id" >/dev/null 2>&1 || true; }
    fail_update() {
        code="$1"; message="$2"; restored=false; restoration_state=NOT_REQUIRED
        if [ "$recovery_required" = 1 ]; then
            write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" ROLLING_BACK "$source_sha" "$was_running" "$recovery_verified" 1 0 0 || true
            update_state "$operation_id" "$instance_id" RUNNING ROLLING_BACK "$recovery_id" || true
            refresh_recovery_manifest "$recovery_id" 0 0 1 1 0 RESTORING || true
            if restore_update_recovery "$operation_id" "$instance_id" "$recovery_id" "$was_running"; then
                recovery_required=0; restored=true; restoration_state=RESTORED
                refresh_recovery_manifest "$recovery_id" 0 0 0 0 1 RESTORED || true
                write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" ROLLED_BACK "$source_sha" "$was_running" 1 0 0 0 || true
            else
                restored=false; restoration_state=RESTORE_FAILED; code=UPDATE_RESTORE_FAILED; message="The update failed and the verified recovery point could not be restored automatically."
                refresh_recovery_manifest "$recovery_id" 0 0 1 1 0 RESTORE_FAILED || true
            fi
        elif [ "$was_running" = 1 ] && ! valid_process "$instance_id"; then
            if command_start "$instance_id" >> "$log" 2>&1; then restored=true; restoration_state=RESTORED; else restored=false; restoration_state=RESTORE_FAILED; code=UPDATE_RESTORE_FAILED; message="The update stopped the original server and it could not be restarted."; fi
        fi
        if [ "$recovery_verified" = 0 ] && { [ -e "$recovery" ] || [ -L "$recovery" ]; }; then safe_remove_update_path "$recovery" "$RECOVERY_ROOT" || true; fi
        [ "$final" -eq 1 ] || write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" FAILED "$source_sha" "$was_running" "$recovery_verified" "$recovery_required" 0 0 || true
        failure_extra=",\"currentCommit\":\"$source_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"targetVersion\":\"$(json_escape "$target_version")\",\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false),\"requiredBytes\":$required,\"availableBytes\":$available"
        update_failed "$operation_id" "$instance_id" "$recovery_id" "$code" "$message" "$restored" "$restoration_state" "$failure_extra" || true
        final=1
        exit 1
    }
    interrupted_update() { trap - HUP INT TERM; fail_update UPDATE_INTERRUPTED "The update helper was interrupted; the pre-update state was restored."; }
    trap cleanup_update_process EXIT
    trap interrupted_update HUP INT TERM
    umask 077
    ensure_update_storage || fail_update UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."
    printf '%s\n' "$$" > "$pid_file"; chmod 600 "$pid_file"
    : > "$log"; chmod 600 "$log"
    [ "$variant_id" = pocketrisu ] || fail_update UNSUPPORTED_VARIANT "Updates are currently supported only for PocketRisu."
    valid_uint "$retention" && [ "$retention" -ge 1 ] && [ "$retention" -le 31536000 ] || fail_update INVALID_RETENTION "Recovery retention must be between 1 second and 365 days."
    load_profile pocketrisu || fail_update UPDATE_NOT_SUPPORTED "The trusted PocketRisu update profile is unavailable."
    instance_update_contract "$instance_id" || fail_update UPDATE_NOT_SUPPORTED "The installed metadata, repository HEAD, or PocketRisu variant is inconsistent."
    source_sha="$repo_commit"; current_version="$(metadata_field version "$instance/metadata.json")"
    [ "$source_sha" != "$target_sha" ] || fail_update ALREADY_AT_TARGET "The installed PocketRisu commit already matches the target."
    size="$(instance_size_bytes "$instance")" || fail_update SIZE_ESTIMATE_FAILED "The installed instance size could not be measured."
    available="$(available_bytes "$ROOT")" || fail_update SIZE_ESTIMATE_FAILED "Available storage could not be measured."
    required="$((size * 3 + 67108864))"
    [ "$available" -ge "$required" ] || fail_update INSUFFICIENT_DISK_SPACE "There is not enough free storage for isolated staging, a verified recovery point, and safe transition."
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" STAGING "$source_sha" "$was_running" 0 0 0 0 || fail_update OPERATION_STATE_FAILED "The update journal could not be initialized."
    update_state "$operation_id" "$instance_id" RUNNING STAGING "$recovery_id" ",\"currentCommit\":\"$source_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"targetVersion\":\"$target_version\",\"requiredBytes\":$required,\"availableBytes\":$available"
    safe_remove_update_path "$operation_dir" "$UPDATE_ROOT" || fail_update UNSAFE_UPDATE_PATH "The update staging path is unsafe."
    mkdir -p "$staged" || fail_update STAGING_FAILED "The isolated update staging directory could not be created."
    git init "$staged/repo" >> "$log" 2>&1 || fail_update FETCH_FAILED "The immutable staging repository could not be initialized."
    git -C "$staged/repo" remote add origin "$profile_repository" >> "$log" 2>&1 || fail_update FETCH_FAILED "The trusted PocketRisu repository could not be configured."
    git -C "$staged/repo" fetch --depth 1 origin "$target_sha" >> "$log" 2>&1 || fail_update FETCH_FAILED "The immutable target commit could not be fetched."
    git -C "$staged/repo" checkout --detach FETCH_HEAD >> "$log" 2>&1 || fail_update CHECKOUT_FAILED "The immutable target commit could not be checked out."
    [ "$(git -C "$staged/repo" rev-parse HEAD 2>/dev/null || true)" = "$target_sha" ] || fail_update REVISION_MISMATCH "The staged checkout does not match the requested full SHA."
    node_version_supported 22 || fail_update NODE_VERSION_UNSUPPORTED "The existing Node.js toolchain is not supported; shared packages were not changed."
    run_trusted_update_build "$staged/repo" "$log" "$operation_dir/private-bin" "$operation_dir/private-store" || fail_update BUILD_FAILED "PocketRisu failed to build in isolated staging without changing shared Termux packages."
    [ -f "$staged/repo/server/node/server.cjs" ] && [ -d "$staged/repo/dist" ] || fail_update BUILD_FAILED "The staged build is incomplete."
    apply_managed_server_patch "$staged/repo/server/node/server.cjs" "$log" || fail_update INCOMPATIBLE_SERVER_LAYOUT "The staged server does not match the managed runtime contract."
    target_version="$(sed -n 's/.*\"version\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p' "$staged/repo/package.json" | sed -n '1p')"
    [ -n "$target_version" ] || fail_update INVALID_TARGET_VERSION "The target commit does not declare a PocketRisu version."
    size="$(instance_size_bytes "$instance")" || fail_update SIZE_ESTIMATE_FAILED "The installed instance size could not be measured."
    available="$(available_bytes "$ROOT")" || fail_update SIZE_ESTIMATE_FAILED "Available storage could not be measured."
    required="$((size * 2 + 67108864))"; [ "$available" -ge "$required" ] || fail_update INSUFFICIENT_DISK_SPACE "There is not enough free storage for a verified recovery point and safe transition."
    instance_update_contract "$instance_id" && [ "$repo_commit" = "$source_sha" ] && [ "$(metadata_field version "$instance/metadata.json")" = "$current_version" ] || fail_update SOURCE_CHANGED "The installed PocketRisu instance changed while the target was staged."
    was_running=0; valid_process "$instance_id" && was_running=1
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" STOPPING "$source_sha" "$was_running" 0 0 0 0 || fail_update OPERATION_STATE_FAILED "The stop intent could not be persisted."
    update_state "$operation_id" "$instance_id" RUNNING STOPPING "$recovery_id" ",\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false)"
    update_graceful_stop "$instance_id" || fail_update GRACEFUL_STOP_FAILED "PocketRisu did not stop gracefully; no recovery copy or update was attempted."
    [ ! -e "$recovery" ] && [ ! -L "$recovery" ] || fail_update RECOVERY_POINT_CONFLICT "The requested recovery point ID already exists."
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" RECOVERY_CREATING "$source_sha" "$was_running" 0 0 0 0 || fail_update OPERATION_STATE_FAILED "The recovery creation intent could not be persisted."
    update_state "$operation_id" "$instance_id" RUNNING RECOVERY_CREATING "$recovery_id"
    mkdir "$recovery" || fail_update RECOVERY_CREATE_FAILED "The private recovery point could not be created."
    mkdir "$recovery/instance" || fail_update RECOVERY_CREATE_FAILED "The private recovery instance directory could not be created."
    chmod 700 "$recovery" "$recovery/instance" || fail_update RECOVERY_CREATE_FAILED "The private recovery point permissions could not be enforced."
    cp -a "$instance/." "$recovery/instance/" || fail_update RECOVERY_CREATE_FAILED "The complete instance could not be copied into recovery storage."
    created_ms="$(( $(date +%s) * 1000 ))"
    write_recovery_manifest "$operation_id" "$instance_id" "$recovery_id" "$source_sha" "$target_sha" "$created_ms" || fail_update RECOVERY_VERIFY_FAILED "Recovery manifest generation failed."
    recovery_manifest_matches_operation "$recovery_id" "$operation_id" "$instance_id" "$source_sha" "$target_sha" || fail_update RECOVERY_VERIFY_FAILED "The complete recovery point failed integrity and identity verification."
    recovery_verified=1; recovery_required=1
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" RECOVERY_VERIFIED "$source_sha" "$was_running" 1 1 0 0 || fail_update OPERATION_STATE_FAILED "The verified recovery intent could not be persisted."
    manifest_sha="$(sed -n '1p' "$recovery/manifest.sha256")"; recovery_size="$(record_value size "$recovery/manifest.conf")"; data_sha="$(record_value data_digest_sha256 "$recovery/manifest.conf")"; config_sha="$(record_value configuration_digest_sha256 "$recovery/manifest.conf")"
    update_state "$operation_id" "$instance_id" RUNNING RECOVERY_VERIFIED "$recovery_id" ",\"size\":$recovery_size,\"manifestSha256\":\"$manifest_sha\",\"dataDigestSha256\":\"$data_sha\",\"configurationDigestSha256\":\"$config_sha\",\"createdAt\":$created_ms,\"pinned\":false,\"inUse\":true"
    rm -rf "$staged/repo/save" "$staged/repo/backups"
    if [ -d "$repo/save" ]; then
        cp -a "$repo/save" "$staged/repo/save" || fail_update DATA_TRANSFER_FAILED "PocketRisu save data could not be copied into the staged update."
    fi
    if [ -d "$repo/backups" ]; then
        cp -a "$repo/backups" "$staged/repo/backups" || fail_update DATA_TRANSFER_FAILED "PocketRisu backups could not be copied into the staged update."
    fi
    if [ -d "$repo/server/node/ssl" ]; then rm -rf "$staged/repo/server/node/ssl"; cp -a "$repo/server/node/ssl" "$staged/repo/server/node/ssl" || fail_update DATA_TRANSFER_FAILED "TLS identity could not be preserved."; fi
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" SWITCHING "$source_sha" "$was_running" 1 1 0 0 || fail_update OPERATION_STATE_FAILED "The switch intent could not be persisted."
    update_state "$operation_id" "$instance_id" RUNNING SWITCHING "$recovery_id"
    mv "$repo" "$operation_dir/old-repo" || fail_update SWITCH_FAILED "The installed repository could not be moved into the private transition area."
    mv "$staged/repo" "$repo" || fail_update SWITCH_FAILED "The staged repository could not be activated."
    write_updated_metadata "$instance/metadata.json" "$target_sha" "$target_version" || fail_update METADATA_FAILED "Installed-version metadata could not be updated."
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" STARTING "$source_sha" "$was_running" 1 1 0 0 || fail_update OPERATION_STATE_FAILED "The launch intent could not be persisted."
    update_state "$operation_id" "$instance_id" RUNNING STARTING "$recovery_id"
    command_start "$instance_id" >> "$log" 2>&1 || fail_update HEALTH_CHECK_FAILED "The updated PocketRisu server did not launch and pass its health check."
    active_sha="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"
    [ "$active_sha" = "$target_sha" ] || fail_update ACTIVE_REVISION_MISMATCH "The healthy server is not running from the requested target commit."
    valid_process "$instance_id" || fail_update HEALTH_CHECK_FAILED "The updated server process disappeared after health verification."
    load_config "$instance_id" && http_healthy "$cfg_port" "$cfg_protocol" "$repo" "$profile_health_path" "$(tls_state_file "$instance_id")" || fail_update HEALTH_CHECK_FAILED "The updated server failed the final HTTP health confirmation."
    if [ "$was_running" = 0 ]; then command_stop "$instance_id" >> "$log" 2>&1 || fail_update RESTORE_RUNTIME_STATE_FAILED "The updated instance was originally stopped but could not be returned to stopped state."; fi
    completion_ms="$(( $(date +%s) * 1000 ))"; expiration_ms="$((completion_ms + retention * 1000))"
    refresh_recovery_manifest "$recovery_id" "$completion_ms" "$expiration_ms" 0 0 0 NOT_REQUIRED || fail_update OPERATION_STATE_FAILED "Recovery retention metadata could not be finalized."
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" SUCCEEDED "$source_sha" "$was_running" 1 0 "$completion_ms" "$expiration_ms" || fail_update OPERATION_STATE_FAILED "The successful update result could not be finalized."
    recovery_required=0
    update_state "$operation_id" "$instance_id" SUCCEEDED SUCCEEDED "$recovery_id" ",\"currentCommit\":\"$source_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"targetVersion\":\"$(json_escape "$target_version")\",\"activeCommit\":\"$active_sha\",\"launchConfirmed\":true,\"healthConfirmed\":true,\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false),\"restored\":false,\"restorationState\":\"NOT_REQUIRED\",\"size\":$recovery_size,\"manifestSha256\":\"$(sed -n '1p' "$recovery/manifest.sha256")\",\"dataDigestSha256\":\"$data_sha\",\"configurationDigestSha256\":\"$config_sha\",\"createdAt\":$created_ms,\"completedAt\":$completion_ms,\"expiresAt\":$expiration_ms,\"pinned\":false,\"inUse\":false,\"requiredBytes\":$required,\"availableBytes\":$available" || exit 1
    final=1
    safe_remove_update_path "$operation_dir" "$UPDATE_ROOT" || true
)

recover_interrupted_update() (
    ensure_update_storage || return 1
    operation_id="$1"; record="$(update_record_file "$operation_id")"
    update_record_valid "$record" || return 1
    instance_id="$(record_value instance_id "$record")"; target_sha="$(record_value target_sha "$record")"; recovery_id="$(record_value recovery_id "$record")"; retention="$(record_value retention_seconds "$record")"
    source_sha="$(record_value source_sha "$record")"; was_running="$(record_value was_running "$record")"; recovery_verified="$(record_value recovery_verified "$record")"; recovery_required="$(record_value recovery_required "$record")"
    update_stage="$(record_value stage "$record")"
    acquire_instance_lock "$instance_id" "$operation_id" 0 || return 1
    trap 'release_instance_lock "$instance_id" "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    if [ "$update_stage" = SUCCEEDED ]; then
        recovery_manifest_matches_operation "$recovery_id" "$operation_id" "$instance_id" "$source_sha" "$target_sha" || return 1
        instance_update_contract "$instance_id" || return 1
        [ "$repo_commit" = "$target_sha" ] || return 1
        manifest="$(recovery_dir "$recovery_id")/manifest.conf"; metadata="$(instance_dir "$instance_id")/metadata.json"
        current_version="$(metadata_field version "$(recovery_dir "$recovery_id")/instance/metadata.json")"; target_version="$(metadata_field version "$metadata")"
        size="$(record_value size "$manifest")"; created_ms="$(record_value created_ms "$manifest")"; completion_ms="$(record_value completion_ms "$manifest")"; expiration_ms="$(record_value expires_ms "$manifest")"
        manifest_sha="$(sed -n '1p' "$(recovery_dir "$recovery_id")/manifest.sha256")"; data_sha="$(record_value data_digest_sha256 "$manifest")"; config_sha="$(record_value configuration_digest_sha256 "$manifest")"
        available="$(available_bytes "$ROOT" 2>/dev/null || printf 0)"; required="$((size * 2 + 67108864))"
        update_state "$operation_id" "$instance_id" SUCCEEDED SUCCEEDED "$recovery_id" ",\"currentCommit\":\"$source_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"targetVersion\":\"$(json_escape "$target_version")\",\"activeCommit\":\"$repo_commit\",\"launchConfirmed\":true,\"healthConfirmed\":true,\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false),\"restored\":false,\"restorationState\":\"NOT_REQUIRED\",\"size\":$size,\"manifestSha256\":\"$manifest_sha\",\"dataDigestSha256\":\"$data_sha\",\"configurationDigestSha256\":\"$config_sha\",\"createdAt\":$created_ms,\"completedAt\":$completion_ms,\"expiresAt\":$expiration_ms,\"pinned\":false,\"inUse\":false,\"requiredBytes\":$required,\"availableBytes\":$available"
        return $?
    fi
    if [ "$recovery_required" = 1 ]; then
        refresh_recovery_manifest "$recovery_id" 0 0 1 1 0 RESTORING || true
        if restore_update_recovery "$operation_id" "$instance_id" "$recovery_id" "$was_running"; then
            refresh_recovery_manifest "$recovery_id" 0 0 0 0 1 RESTORED || true
            write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" FAILED "$source_sha" "$was_running" 1 0 0 0 || true
            update_failed "$operation_id" "$instance_id" "$recovery_id" UPDATE_PROCESS_LOST "The update process stopped; the verified pre-update state was restored." true RESTORED ",\"currentCommit\":\"$source_sha\",\"targetCommit\":\"$target_sha\",\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false)"
        else
            refresh_recovery_manifest "$recovery_id" 0 0 1 1 0 RESTORE_FAILED || true
            update_failed "$operation_id" "$instance_id" "$recovery_id" UPDATE_RESTORE_FAILED "The update process stopped and automatic recovery failed." false RESTORE_FAILED ",\"currentCommit\":\"$source_sha\",\"targetCommit\":\"$target_sha\",\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false)"
        fi
    else
        if [ "$was_running" = 1 ] && ! valid_process "$instance_id"; then command_start "$instance_id" >/dev/null 2>&1 || true; fi
        update_failed "$operation_id" "$instance_id" "$recovery_id" UPDATE_PROCESS_LOST "The update process stopped before completion; the installed instance was not switched." false NOT_REQUIRED ",\"currentCommit\":\"$source_sha\",\"targetCommit\":\"$target_sha\",\"sourceWasRunning\":$([ "$was_running" = 1 ] && printf true || printf false)"
    fi
)

rollback_point_json() {
    recovery_id="$1"; manifest="$(recovery_dir "$recovery_id")/manifest.conf"
    printf ',"createdAt":%s,"completedAt":%s,"expiresAt":%s,"size":%s,"manifestSha256":"%s","dataDigestSha256":"%s","configurationDigestSha256":"%s"' \
        "$(record_value created_ms "$manifest")" "$(record_value completed_ms "$manifest")" "$(record_value expires_ms "$manifest")" \
        "$(record_value size "$manifest")" "$(sed -n '1p' "$(recovery_dir "$recovery_id")/manifest.sha256")" \
        "$(record_value data_digest_sha256 "$manifest")" "$(record_value configuration_digest_sha256 "$manifest")"
}

restore_manual_recovery() (
    operation_id="$1"; instance_id="$2"; recovery_id="$3"; was_running="$4"; slot="$5"
    operation_dir="$(rollback_operation_dir "$operation_id")"; instance="$(instance_dir "$instance_id")"
    recovery="$(recovery_dir "$recovery_id")"; manifest="$recovery/manifest.conf"
    recovery_snapshot_contract "$recovery_id" "$instance_id" || return 1
    target_sha="$clone_commit"; target_version="$clone_version"
    mkdir -p "$operation_dir" || return 1
    restore_ready="$operation_dir/$slot-restore-ready"; displaced="$operation_dir/$slot-displaced"
    if [ ! -e "$instance" ] && [ -d "$displaced" ] && [ ! -L "$displaced" ]; then mv "$displaced" "$instance" || return 1; fi
    safe_remove_update_path "$restore_ready" "$operation_dir" || return 1
    safe_remove_update_path "$displaced" "$operation_dir" || return 1
    mkdir "$restore_ready" || return 1
    cp -a "$recovery/instance/." "$restore_ready/" || { safe_remove_update_path "$restore_ready" "$operation_dir" || true; return 1; }
    [ "$(tree_manifest_hash "$restore_ready")" = "$(record_value tree_sha256 "$manifest")" ] || { safe_remove_update_path "$restore_ready" "$operation_dir" || true; return 1; }
    valid_process "$instance_id" && command_stop "$instance_id" >/dev/null 2>&1 || true
    [ -d "$instance" ] && [ ! -L "$instance" ] || return 1
    mv "$instance" "$displaced" || return 1
    if ! mv "$restore_ready" "$instance"; then mv "$displaced" "$instance" 2>/dev/null || true; return 1; fi
    rm -f "$instance/run/server.pid"
    if [ "$(tree_manifest_hash "$instance")" != "$(record_value tree_sha256 "$manifest")" ]; then
        safe_remove_update_path "$instance" "$INSTANCES" || true
        mv "$displaced" "$instance" 2>/dev/null || true
        return 1
    fi
    instance_update_contract "$instance_id" && [ "$repo_commit" = "$target_sha" ] || return 1
    [ "$(metadata_field version "$instance/metadata.json")" = "$target_version" ] || return 1
    command_start "$instance_id" >/dev/null 2>&1 || return 1
    valid_process "$instance_id" || return 1
    load_config "$instance_id" && http_healthy "$cfg_port" "$cfg_protocol" "$instance/repo" "$profile_health_path" "$(tls_state_file "$instance_id")" || return 1
    if [ "$was_running" = 0 ]; then
        command_stop "$instance_id" >/dev/null 2>&1 || return 1
        valid_process "$instance_id" && return 1
    fi
    safe_remove_update_path "$displaced" "$operation_dir" || return 1
)

do_rollback() (
    operation_id="$1"; instance_id="$2"; selected_id="$3"; pre_id="$4"; retention="$5"
    record="$(rollback_record_file "$operation_id")"; instance="$(instance_dir "$instance_id")"; selected="$(recovery_dir "$selected_id")"
    pre="$(recovery_dir "$pre_id")"; operation_dir="$(rollback_operation_dir "$operation_id")"; pid_file="$(operation_pid_file "$operation_id")"
    was_running=unknown; current_sha=0000000000000000000000000000000000000000; target_sha=0000000000000000000000000000000000000000
    current_version=""; target_version=""; pre_verified=0; recovery_required=0; final=0; created_ms=0; completion_ms=0; expiration_ms=0
    cleanup_rollback_process() { rm -f "$pid_file"; release_instance_lock "$instance_id" "$operation_id" >/dev/null 2>&1 || true; }
    fail_rollback() {
        code="$1"; message="$2"; restored=false; restoration_state=NOT_REQUIRED
        if [ "$pre_verified" = 1 ]; then
            write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" RECOVERING_CURRENT "$current_sha" "$target_sha" "$was_running" 1 1 0 0 || true
            rollback_state "$operation_id" "$instance_id" RUNNING RECOVERING_CURRENT "$selected_id" "$pre_id" || true
            refresh_recovery_manifest "$pre_id" 0 0 1 1 0 RESTORING || true
            if restore_manual_recovery "$operation_id" "$instance_id" "$pre_id" "$was_running" pre; then
                restored=true; restoration_state=RESTORED; recovery_required=0
                completion_ms="$(( $(date +%s) * 1000 ))"; expiration_ms="$((completion_ms + retention * 1000))"
                refresh_recovery_manifest "$pre_id" "$completion_ms" "$expiration_ms" 0 0 1 RESTORED || true
            else
                code=ROLLBACK_RECOVERY_FAILED; message="Rollback failed and the verified pre-rollback state could not be restored."; restoration_state=RESTORE_FAILED; recovery_required=1
                refresh_recovery_manifest "$pre_id" 0 0 1 1 0 RESTORE_FAILED || true
            fi
        elif [ "$was_running" = 1 ] && ! valid_process "$instance_id"; then
            command_start "$instance_id" >/dev/null 2>&1 && restored=true && restoration_state=RESTORED
        fi
        if recovery_manifest_valid "$selected_id"; then
            sm="$selected/manifest.conf"; rewrite_recovery_manifest_flags "$selected_id" "$(record_value pinned "$sm")" 0 0 "$(record_value restored "$sm")" "$(record_value restoration_state "$sm")" || true
        fi
        if [ "$pre_verified" = 0 ] && { [ -e "$pre" ] || [ -L "$pre" ]; }; then safe_remove_update_path "$pre" "$RECOVERY_ROOT" || true; fi
        write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" FAILED "$current_sha" "$target_sha" "$was_running" "$pre_verified" "$recovery_required" "$completion_ms" "$expiration_ms" || true
        extra=",\"currentCommit\":\"$current_sha\",\"targetCommit\":\"$target_sha\",\"activeCommit\":\"$(git -C "$instance/repo" rev-parse HEAD 2>/dev/null || true)\",\"activeVersion\":\"$(json_escape "$(metadata_field version "$instance/metadata.json" 2>/dev/null || true)")\",\"sourceWasRunning\":$(json_boolean "$was_running")"
        [ "$pre_verified" = 1 ] && extra="$extra$(rollback_point_json "$pre_id")"
        rollback_failed "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$code" "$message" "$restored" "$restoration_state" "$extra" || true
        final=1; exit 1
    }
    interrupted_rollback() { trap - HUP INT TERM; fail_rollback ROLLBACK_INTERRUPTED "The rollback helper was interrupted."; }
    trap cleanup_rollback_process EXIT
    trap interrupted_rollback HUP INT TERM
    umask 077
    ensure_update_storage || fail_rollback UNSAFE_UPDATE_PATH "Rollback storage is unsafe."
    load_profile pocketrisu || fail_rollback ROLLBACK_NOT_SUPPORTED "The trusted PocketRisu profile is unavailable."
    printf '%s\n' "$$" > "$pid_file"; chmod 600 "$pid_file"
    : > "$LOGS/$operation_id.log"; chmod 600 "$LOGS/$operation_id.log"
    valid_uint "$retention" && [ "$retention" -ge 1 ] && [ "$retention" -le 31536000 ] || fail_rollback INVALID_RETENTION "Pre-rollback retention is invalid."
    instance_update_contract "$instance_id" || fail_rollback ROLLBACK_NOT_SUPPORTED "Only a consistent PocketRisu instance can be rolled back."
    current_sha="$repo_commit"; current_version="$(metadata_field version "$instance/metadata.json")"
    recovery_snapshot_contract "$selected_id" "$instance_id" && recovery_owner_terminal "$selected_id" || fail_rollback RECOVERY_INVALID "The selected recovery point failed validation."
    target_sha="$clone_commit"; target_version="$clone_version"
    selected_manifest="$selected/manifest.conf"; selected_pinned="$(record_value pinned "$selected_manifest")"
    rewrite_recovery_manifest_flags "$selected_id" "$selected_pinned" 1 0 "$(record_value restored "$selected_manifest")" "$(record_value restoration_state "$selected_manifest")" || fail_rollback RECOVERY_METADATA_FAILED "The selected recovery point could not be reserved."
    current_size="$(instance_size_bytes "$instance")" || fail_rollback SIZE_ESTIMATE_FAILED "The current instance size could not be measured."
    selected_size="$(record_value size "$selected_manifest")"; available="$(available_bytes "$ROOT")" || fail_rollback SIZE_ESTIMATE_FAILED "Available storage could not be measured."
    required="$((current_size + selected_size + 67108864))"; [ "$available" -ge "$required" ] || fail_rollback INSUFFICIENT_DISK_SPACE "There is not enough storage for a verified pre-rollback point and staged restore."
    was_running=0; valid_process "$instance_id" && was_running=1
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" STOPPING "$current_sha" "$target_sha" "$was_running" 0 0 0 0 || fail_rollback OPERATION_STATE_FAILED "Rollback stop intent could not be persisted."
    rollback_state "$operation_id" "$instance_id" RUNNING STOPPING "$selected_id" "$pre_id" ",\"sourceWasRunning\":$(json_boolean "$was_running")"
    update_graceful_stop "$instance_id" || fail_rollback GRACEFUL_STOP_FAILED "PocketRisu did not stop; no backup or rollback was attempted."
    [ ! -e "$pre" ] && [ ! -L "$pre" ] || fail_rollback RECOVERY_POINT_CONFLICT "The pre-rollback recovery point already exists."
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" PRE_RECOVERY_CREATING "$current_sha" "$target_sha" "$was_running" 0 0 0 0 || fail_rollback OPERATION_STATE_FAILED "Pre-rollback backup intent could not be persisted."
    rollback_state "$operation_id" "$instance_id" RUNNING PRE_RECOVERY_CREATING "$selected_id" "$pre_id"
    mkdir "$pre" && mkdir "$pre/instance" && chmod 700 "$pre" "$pre/instance" || fail_rollback PRE_ROLLBACK_BACKUP_FAILED "The pre-rollback recovery directory could not be created."
    cp -a "$instance/." "$pre/instance/" || fail_rollback PRE_ROLLBACK_BACKUP_FAILED "The current instance could not be copied before rollback."
    created_ms="$(( $(date +%s) * 1000 ))"
    write_recovery_manifest "$operation_id" "$instance_id" "$pre_id" "$current_sha" "$target_sha" "$created_ms" || fail_rollback PRE_ROLLBACK_BACKUP_FAILED "The pre-rollback manifest could not be generated."
    recovery_manifest_matches_operation "$pre_id" "$operation_id" "$instance_id" "$current_sha" "$target_sha" || fail_rollback PRE_ROLLBACK_BACKUP_FAILED "The pre-rollback recovery point failed verification."
    pre_verified=1; recovery_required=1
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" PRE_RECOVERY_VERIFIED "$current_sha" "$target_sha" "$was_running" 1 1 0 0 || fail_rollback OPERATION_STATE_FAILED "Verified pre-rollback state could not be journaled."
    rollback_state "$operation_id" "$instance_id" RUNNING PRE_RECOVERY_VERIFIED "$selected_id" "$pre_id" "$(rollback_point_json "$pre_id")"
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" RESTORING "$current_sha" "$target_sha" "$was_running" 1 1 0 0 || fail_rollback OPERATION_STATE_FAILED "Restore intent could not be persisted."
    rollback_state "$operation_id" "$instance_id" RUNNING RESTORING "$selected_id" "$pre_id"
    restore_manual_recovery "$operation_id" "$instance_id" "$selected_id" "$was_running" selected || fail_rollback ROLLBACK_RESTORE_FAILED "The selected recovery point could not be restored and verified."
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" VERIFYING "$current_sha" "$target_sha" "$was_running" 1 1 0 0 || fail_rollback OPERATION_STATE_FAILED "Rollback verification intent could not be persisted."
    completion_ms="$(( $(date +%s) * 1000 ))"; expiration_ms="$((completion_ms + retention * 1000))"
    refresh_recovery_manifest "$pre_id" "$completion_ms" "$expiration_ms" 0 0 0 NOT_REQUIRED || fail_rollback OPERATION_STATE_FAILED "Pre-rollback retention metadata could not be finalized."
    selected_manifest="$selected/manifest.conf"; rewrite_recovery_manifest_flags "$selected_id" "$(record_value pinned "$selected_manifest")" 0 0 1 RESTORED || fail_rollback OPERATION_STATE_FAILED "Selected recovery metadata could not be finalized."
    recovery_required=0
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" SUCCEEDED "$current_sha" "$target_sha" "$was_running" 1 0 "$completion_ms" "$expiration_ms" || fail_rollback OPERATION_STATE_FAILED "Rollback success could not be finalized."
    load_config "$instance_id" || fail_rollback INVALID_CONFIG "Restored server configuration is invalid."
    rollback_state "$operation_id" "$instance_id" SUCCEEDED SUCCEEDED "$selected_id" "$pre_id" ",\"currentCommit\":\"$current_sha\",\"targetCommit\":\"$target_sha\",\"activeCommit\":\"$target_sha\",\"activeVersion\":\"$(json_escape "$target_version")\",\"sourceWasRunning\":$(json_boolean "$was_running"),\"launchConfirmed\":true,\"healthConfirmed\":true,\"restored\":true,\"restorationState\":\"RESTORED\",$(config_json_fields)$(rollback_point_json "$pre_id")" || exit 1
    final=1; safe_remove_update_path "$operation_dir" "$UPDATE_ROOT" || true
)

recover_interrupted_rollback() (
    operation_id="$1"; record="$(rollback_record_file "$operation_id")"; rollback_record_valid "$record" || return 1
    instance_id="$(record_value instance_id "$record")"; selected_id="$(record_value selected_recovery_id "$record")"; pre_id="$(record_value pre_recovery_id "$record")"
    retention="$(record_value retention_seconds "$record")"; current_sha="$(record_value current_sha "$record")"; target_sha="$(record_value target_sha "$record")"
    was_running="$(record_value was_running "$record")"; pre_verified="$(record_value pre_verified "$record")"; stage="$(record_value stage "$record")"
    acquire_instance_lock "$instance_id" "$operation_id" 0 || return 1
    trap 'release_instance_lock "$instance_id" "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    operation_dir="$(rollback_operation_dir "$operation_id")"; instance="$(instance_dir "$instance_id")"
    if [ "$stage" = SUCCEEDED ]; then
        if load_profile pocketrisu &&
           recovery_snapshot_contract "$selected_id" "$instance_id" &&
           recovery_snapshot_contract "$pre_id" "$instance_id" &&
           instance_update_contract "$instance_id" && [ "$repo_commit" = "$target_sha" ] &&
           [ "$(metadata_field version "$instance/metadata.json")" = "$(metadata_field version "$(recovery_dir "$selected_id")/instance/metadata.json")" ] &&
           load_config "$instance_id" &&
           { [ "$was_running" != 1 ] || { valid_process "$instance_id" && http_healthy "$cfg_port" "$cfg_protocol" "$instance/repo" "$profile_health_path" "$(tls_state_file "$instance_id")"; }; }; then
            rollback_state "$operation_id" "$instance_id" SUCCEEDED SUCCEEDED "$selected_id" "$pre_id" ",\"currentCommit\":\"$current_sha\",\"targetCommit\":\"$target_sha\",\"activeCommit\":\"$target_sha\",\"activeVersion\":\"$(json_escape "$(metadata_field version "$instance/metadata.json")")\",\"sourceWasRunning\":$(json_boolean "$was_running"),\"launchConfirmed\":true,\"healthConfirmed\":true,\"restored\":true,\"restorationState\":\"RESTORED\",$(config_json_fields)$(rollback_point_json "$pre_id")"
            return $?
        fi
    fi
    if [ ! -e "$instance" ] && [ -d "$operation_dir/selected-displaced" ] && [ ! -L "$operation_dir/selected-displaced" ]; then mv "$operation_dir/selected-displaced" "$instance" || true; fi
    if [ "$pre_verified" = 1 ] && recovery_snapshot_contract "$pre_id" "$instance_id"; then
        refresh_recovery_manifest "$pre_id" 0 0 1 1 0 RESTORING || true
        if restore_manual_recovery "$operation_id" "$instance_id" "$pre_id" "$was_running" interrupted; then
            completion_ms="$(( $(date +%s) * 1000 ))"; expiration_ms="$((completion_ms + retention * 1000))"
            refresh_recovery_manifest "$pre_id" "$completion_ms" "$expiration_ms" 0 0 1 RESTORED || true
            selected_manifest="$(recovery_dir "$selected_id")/manifest.conf"; recovery_manifest_valid "$selected_id" && rewrite_recovery_manifest_flags "$selected_id" "$(record_value pinned "$selected_manifest")" 0 0 "$(record_value restored "$selected_manifest")" "$(record_value restoration_state "$selected_manifest")" || true
            write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" FAILED "$current_sha" "$target_sha" "$was_running" 1 0 "$completion_ms" "$expiration_ms" || true
            rollback_failed "$operation_id" "$instance_id" "$selected_id" "$pre_id" ROLLBACK_PROCESS_LOST "The rollback process stopped; the verified pre-rollback state was restored." true RESTORED ",\"activeCommit\":\"$current_sha\",\"activeVersion\":\"$(json_escape "$(metadata_field version "$instance/metadata.json")")\"$(rollback_point_json "$pre_id")"
            return 0
        fi
        refresh_recovery_manifest "$pre_id" 0 0 1 1 0 RESTORE_FAILED || true
        write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" FAILED "$current_sha" "$target_sha" "$was_running" 1 1 0 0 || true
        rollback_failed "$operation_id" "$instance_id" "$selected_id" "$pre_id" ROLLBACK_RECOVERY_FAILED "The rollback process stopped and pre-rollback recovery failed." false RESTORE_FAILED
        return 1
    fi
    [ "$was_running" = 1 ] && ! valid_process "$instance_id" && command_start "$instance_id" >/dev/null 2>&1 || true
    selected_manifest="$(recovery_dir "$selected_id")/manifest.conf"; recovery_manifest_valid "$selected_id" && rewrite_recovery_manifest_flags "$selected_id" "$(record_value pinned "$selected_manifest")" 0 0 "$(record_value restored "$selected_manifest")" "$(record_value restoration_state "$selected_manifest")" || true
    [ -e "$(recovery_dir "$pre_id")" ] && safe_remove_update_path "$(recovery_dir "$pre_id")" "$RECOVERY_ROOT" || true
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" FAILED "$current_sha" "$target_sha" "$was_running" 0 0 0 0 || true
    rollback_failed "$operation_id" "$instance_id" "$selected_id" "$pre_id" ROLLBACK_PROCESS_LOST "The rollback process stopped before the current state was replaced." false NOT_REQUIRED
)

command_recovery_cleanup() (
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    [ "$#" -eq 2 ] || { emit_error INVALID_ARGUMENT "recovery-cleanup requires recovery point ID and current epoch milliseconds."; return 2; }
    recovery_id="$1"; now_ms="$2"; valid_recovery_id "$recovery_id" && valid_uint "$now_ms" || { emit_error INVALID_ARGUMENT "The recovery cleanup arguments are invalid."; return 2; }
    remove_recovery_point "$recovery_id" "$now_ms" true recovery-cleanup
)

command_recovery_delete() (
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    [ "$#" -eq 1 ] || { emit_error INVALID_ARGUMENT "recovery-delete requires a recovery point ID."; return 2; }
    recovery_id="$1"; valid_recovery_id "$recovery_id" || { emit_error INVALID_RECOVERY_ID "The recovery point ID is invalid."; return 2; }
    remove_recovery_point "$recovery_id" 0 false recovery-delete
)

remove_recovery_point() (
    recovery_id="$1"; now_ms="$2"; require_expired="$3"; operation_name="$4"
    cleanup_lock="$LOCKS/recovery-action-$recovery_id"
    if ! mkdir "$cleanup_lock" 2>/dev/null; then emit_error RECOVERY_IN_USE "Another cleanup or recovery action owns this recovery point."; return 4; fi
    trap 'rmdir "$cleanup_lock" 2>/dev/null || true' EXIT HUP INT TERM
    recovery="$(recovery_dir "$recovery_id")"
    [ -e "$recovery" ] || { printf '{"ok":true,"operation":"%s","recoveryPointId":"%s","deleted":false}\n' "$operation_name" "$recovery_id"; return 0; }
    recovery_manifest_valid "$recovery_id" || { emit_error RECOVERY_NOT_MANAGER_OWNED "The recovery point is not a verified Manager-owned recovery point."; return 3; }
    recovery_owner_terminal "$recovery_id" || { emit_error RECOVERY_NOT_MANAGER_OWNED "No terminal Manager journal owns this recovery point."; return 3; }
    manifest="$recovery/manifest.conf"
    [ "$(record_value pinned "$manifest")" = 0 ] || { emit_error RECOVERY_PINNED "Pinned recovery points cannot be deleted."; return 4; }
    [ "$(record_value in_use "$manifest")" = 0 ] || { emit_error RECOVERY_IN_USE "The recovery point is still in use."; return 4; }
    [ "$(record_value recovery_required "$manifest")" = 0 ] || { emit_error RECOVERY_REQUIRED "The recovery point is still required for failure recovery."; return 4; }
    if [ "$require_expired" = true ]; then
        expires="$(record_value expires_ms "$manifest")"; valid_uint "$expires" && [ "$expires" -gt 0 ] && [ "$now_ms" -ge "$expires" ] || { emit_error RECOVERY_NOT_EXPIRED "The recovery point has not expired."; return 4; }
    fi
    safe_remove_update_path "$recovery" "$RECOVERY_ROOT" || { emit_error RECOVERY_CLEANUP_FAILED "The recovery point could not be removed safely."; return 5; }
    printf '{"ok":true,"operation":"%s","recoveryPointId":"%s","deleted":true}\n' "$operation_name" "$recovery_id"
)

command_version() {
    printf '{"ok":true,"operation":"version","helperVersion":%s}\n' "$HELPER_VERSION"
}

command_doctor() {
    node_version="$(node --version 2>/dev/null || true)"
    platform="$(uname -o 2>/dev/null || uname -s 2>/dev/null || true)"
    device_architecture="$(uname -m 2>/dev/null || true)"
    node_architecture="$(node -p 'process.arch' 2>/dev/null || true)"
    node_abi="$(node -p 'process.versions.modules' 2>/dev/null || true)"
    pnpm_version="$(pnpm --version 2>/dev/null || true)"
    git_version="$(git --version 2>/dev/null || true)"
    printf '{"ok":true,"operation":"doctor","helperVersion":%s,"node":"%s","platform":"%s","deviceArchitecture":"%s","nodeArchitecture":"%s","nodeAbi":"%s","pnpm":"%s","git":"%s"}\n' \
        "$HELPER_VERSION" "$(json_escape "$node_version")" "$(json_escape "$platform")" "$(json_escape "$device_architecture")" "$(json_escape "$node_architecture")" "$(json_escape "$node_abi")" "$(json_escape "$pnpm_version")" "$(json_escape "$git_version")"
}

dispatch_clone() (
    operation_id="$1"; source_id="$2"; target_id="$3"; target_port="$4"
    valid_identifier "$target_id" || { emit_error "INVALID_TARGET_INSTANCE_ID" "The clone target instance ID is invalid."; exit 2; }
    [ "$target_id" != "$source_id" ] || { emit_error "INVALID_TARGET" "The clone target must have a new instance identity."; exit 2; }
    valid_uint "$target_port" && [ "$target_port" -ge 1024 ] && [ "$target_port" -le 65535 ] || { emit_error "INVALID_PORT" "The automatic clone port is outside the allowed range."; exit 2; }
    target="$(instance_dir "$target_id")"
    [ ! -e "$target" ] && [ ! -L "$target" ] || { emit_error "TARGET_ALREADY_EXISTS" "The target instance path already exists."; exit 3; }
    managed_port_available "$target_port" "$target_id" || { emit_error "PORT_UNAVAILABLE" "The automatic clone port is reserved or currently unavailable."; exit 4; }
    acquire_clone_action_lock "$operation_id" 0 || { emit_error "CLONE_BUSY" "Another process is preparing this clone operation."; exit 4; }
    trap 'release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PREPARED unknown 0 0 0 || { emit_error "OPERATION_STATE_FAILED" "The durable clone record could not be prepared."; exit 5; }
    clone_write_state "$operation_id" "$source_id" "$target_id" QUEUED QUEUED false false "$target_port" || { emit_error "OPERATION_STATE_FAILED" "The queued clone state could not be persisted."; exit 5; }
    nohup "$BIN" _clone "$operation_id" "$source_id" "$target_id" "$target_port" >/dev/null 2>&1 < /dev/null &
    clone_pid="$!"
    printf '%s\n' "$clone_pid" > "$(operation_pid_file "$operation_id")" || { emit_error "OPERATION_STATE_FAILED" "The queued clone process identity could not be persisted."; exit 5; }
    chmod 600 "$(operation_pid_file "$operation_id")" || { emit_error "OPERATION_STATE_FAILED" "The queued clone process identity could not be protected."; exit 5; }
)

dispatch_recovery_clone() (
    operation_id="$1"; source_id="$2"; recovery_id="$3"; target_id="$4"; target_port="$5"
    ensure_update_storage || { emit_error "UNSAFE_UPDATE_PATH" "Recovery storage is unsafe."; return 3; }
    valid_recovery_id "$recovery_id" && valid_identifier "$target_id" && [ "$source_id" != "$target_id" ] || { emit_error "INVALID_ARGUMENT" "Recovery clone identifiers are invalid."; return 2; }
    valid_uint "$target_port" && [ "$target_port" -ge 1024 ] && [ "$target_port" -le 65535 ] || { emit_error "INVALID_PORT" "The recovery clone port is invalid."; return 2; }
    recovery_snapshot_contract "$recovery_id" "$source_id" && recovery_owner_terminal "$recovery_id" || { emit_error "RECOVERY_INVALID" "The recovery clone source failed integrity, compatibility, or ownership validation."; return 3; }
    target="$(instance_dir "$target_id")"; [ ! -e "$target" ] && [ ! -L "$target" ] || { emit_error "TARGET_ALREADY_EXISTS" "The target instance already exists."; return 3; }
    managed_port_available "$target_port" "$target_id" || { emit_error "PORT_UNAVAILABLE" "The automatic clone port is unavailable."; return 4; }
    acquire_clone_action_lock "$operation_id" 0 || { emit_error "CLONE_BUSY" "Another process is preparing this recovery clone."; return 4; }
    trap 'release_clone_action_lock "$operation_id" >/dev/null 2>&1 || true' EXIT HUP INT TERM
    write_recovery_clone_binding "$operation_id" "$source_id" "$recovery_id" "$target_id" "$target_port" || { emit_error "OPERATION_STATE_FAILED" "The recovery clone binding could not be persisted."; return 5; }
    manifest="$(recovery_dir "$recovery_id")/manifest.conf"; rewrite_recovery_manifest_flags "$recovery_id" "$(record_value pinned "$manifest")" 1 0 "$(record_value restored "$manifest")" "$(record_value restoration_state "$manifest")" || { emit_error "RECOVERY_METADATA_FAILED" "The recovery point could not be reserved for cloning."; return 5; }
    write_clone_record "$operation_id" "$source_id" "$target_id" "$target_port" PREPARED 0 0 0 0 || { emit_error "OPERATION_STATE_FAILED" "The recovery clone record could not be prepared."; return 5; }
    clone_write_state "$operation_id" "$source_id" "$target_id" QUEUED QUEUED false false "$target_port" ",\"recoveryPointId\":\"$recovery_id\"" || { emit_error "OPERATION_STATE_FAILED" "The recovery clone state could not be persisted."; return 5; }
    nohup "$BIN" _clone "$operation_id" "$source_id" "$target_id" "$target_port" >/dev/null 2>&1 < /dev/null &
    clone_pid="$!"; printf '%s\n' "$clone_pid" > "$(operation_pid_file "$operation_id")" || { emit_error "OPERATION_STATE_FAILED" "The recovery clone process identity could not be persisted."; return 5; }
    chmod 600 "$(operation_pid_file "$operation_id")" || return 5
)

dispatch_update() (
    operation_id="$1"; instance_id="$2"; variant_id="$3"; target_sha="$4"; recovery_id="$5"; retention="$6"
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Update and recovery storage must be real private directories."; return 3; }
    [ "$variant_id" = pocketrisu ] || { emit_error UNSUPPORTED_VARIANT "Updates are currently supported only for PocketRisu."; return 3; }
    valid_commit "$target_sha" || { emit_error INVALID_REVISION "Update requires a lowercase full target commit SHA."; return 2; }
    valid_recovery_id "$recovery_id" || { emit_error INVALID_RECOVERY_ID "The recovery point ID is invalid."; return 2; }
    valid_uint "$retention" && [ "$retention" -ge 1 ] && [ "$retention" -le 31536000 ] || { emit_error INVALID_RETENTION "Recovery retention must be between 1 second and 365 days."; return 2; }
    [ ! -e "$(recovery_dir "$recovery_id")" ] && [ ! -L "$(recovery_dir "$recovery_id")" ] || { emit_error RECOVERY_POINT_CONFLICT "The requested recovery point ID already exists."; return 3; }
    load_profile pocketrisu || { emit_error UPDATE_NOT_SUPPORTED "The trusted PocketRisu update profile is unavailable."; return 3; }
    instance_update_contract "$instance_id" || { emit_error UPDATE_NOT_SUPPORTED "Only a consistent trusted PocketRisu Git instance can be updated."; return 3; }
    source_sha="$repo_commit"; current_version="$(metadata_field version "$(instance_dir "$instance_id")/metadata.json")"
    [ "$source_sha" != "$target_sha" ] || { emit_error ALREADY_AT_TARGET "The installed PocketRisu commit already matches the target."; return 3; }
    size="$(instance_size_bytes "$(instance_dir "$instance_id")")" || { emit_error SIZE_ESTIMATE_FAILED "The instance size could not be measured."; return 4; }
    available="$(available_bytes "$ROOT")" || { emit_error SIZE_ESTIMATE_FAILED "Available storage could not be measured."; return 4; }
    required="$((size * 3 + 67108864))"
    [ "$available" -ge "$required" ] || { emit_error INSUFFICIENT_DISK_SPACE "There is not enough free storage for isolated staging, recovery, and transition."; return 4; }
    write_update_record "$operation_id" "$instance_id" "$target_sha" "$recovery_id" "$retention" PREPARED "$source_sha" unknown 0 0 0 0 || { emit_error OPERATION_STATE_FAILED "The durable update record could not be prepared."; return 5; }
    update_state "$operation_id" "$instance_id" QUEUED PREPARED "$recovery_id" ",\"currentCommit\":\"$source_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"requiredBytes\":$required,\"availableBytes\":$available" || { emit_error OPERATION_STATE_FAILED "The queued update state could not be persisted."; return 5; }
    nohup "$BIN" _update "$operation_id" "$instance_id" "$variant_id" "$target_sha" "$recovery_id" "$retention" >/dev/null 2>&1 < /dev/null &
    update_pid="$!"
    printf '%s\n' "$update_pid" > "$(operation_pid_file "$operation_id")" || { emit_error OPERATION_STATE_FAILED "The update process identity could not be persisted."; return 5; }
    chmod 600 "$(operation_pid_file "$operation_id")" || { emit_error OPERATION_STATE_FAILED "The update process identity could not be protected."; return 5; }
)

dispatch_rollback() (
    operation_id="$1"; instance_id="$2"; selected_id="$3"; pre_id="$4"; retention="$5"
    ensure_update_storage || { emit_error UNSAFE_UPDATE_PATH "Rollback storage must be a real private directory."; return 3; }
    valid_recovery_id "$selected_id" && valid_recovery_id "$pre_id" && [ "$selected_id" != "$pre_id" ] || { emit_error INVALID_RECOVERY_ID "Rollback requires distinct valid selected and pre-rollback recovery IDs."; return 2; }
    valid_uint "$retention" && [ "$retention" -ge 1 ] && [ "$retention" -le 31536000 ] || { emit_error INVALID_RETENTION "Pre-rollback retention must be between 1 second and 365 days."; return 2; }
    [ ! -e "$(recovery_dir "$pre_id")" ] && [ ! -L "$(recovery_dir "$pre_id")" ] || { emit_error RECOVERY_POINT_CONFLICT "The pre-rollback recovery point already exists."; return 3; }
    load_profile pocketrisu || { emit_error ROLLBACK_NOT_SUPPORTED "The trusted PocketRisu profile is unavailable."; return 3; }
    instance_update_contract "$instance_id" || { emit_error ROLLBACK_NOT_SUPPORTED "Only a consistent trusted PocketRisu instance can be rolled back."; return 3; }
    current_sha="$repo_commit"; current_version="$(metadata_field version "$(instance_dir "$instance_id")/metadata.json")"
    recovery_snapshot_contract "$selected_id" "$instance_id" && recovery_owner_terminal "$selected_id" || { emit_error RECOVERY_INVALID "The selected recovery point failed format, integrity, target, dependency, or ownership validation."; return 3; }
    target_sha="$clone_commit"; target_version="$clone_version"
    current_size="$(instance_size_bytes "$(instance_dir "$instance_id")")" || { emit_error SIZE_ESTIMATE_FAILED "The current instance size could not be measured."; return 4; }
    selected_size="$(record_value size "$(recovery_dir "$selected_id")/manifest.conf")"; available="$(available_bytes "$ROOT")" || { emit_error SIZE_ESTIMATE_FAILED "Available storage could not be measured."; return 4; }
    required="$((current_size + selected_size + 67108864))"; [ "$available" -ge "$required" ] || { emit_error INSUFFICIENT_DISK_SPACE "There is not enough storage for the pre-rollback recovery point and staged restore."; return 4; }
    write_rollback_record "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" PREPARED "$current_sha" "$target_sha" unknown 0 0 0 0 || { emit_error OPERATION_STATE_FAILED "The durable rollback record could not be prepared."; return 5; }
    manifest="$(recovery_dir "$selected_id")/manifest.conf"; rewrite_recovery_manifest_flags "$selected_id" "$(record_value pinned "$manifest")" 1 0 "$(record_value restored "$manifest")" "$(record_value restoration_state "$manifest")" || { emit_error RECOVERY_METADATA_FAILED "The selected recovery point could not be reserved."; return 5; }
    rollback_state "$operation_id" "$instance_id" QUEUED PREPARED "$selected_id" "$pre_id" ",\"currentCommit\":\"$current_sha\",\"currentVersion\":\"$(json_escape "$current_version")\",\"targetCommit\":\"$target_sha\",\"targetVersion\":\"$(json_escape "$target_version")\",\"requiredBytes\":$required,\"availableBytes\":$available" || { emit_error OPERATION_STATE_FAILED "The queued rollback state could not be persisted."; return 5; }
    nohup "$BIN" _rollback "$operation_id" "$instance_id" "$selected_id" "$pre_id" "$retention" >/dev/null 2>&1 < /dev/null &
    rollback_pid="$!"; printf '%s\n' "$rollback_pid" > "$(operation_pid_file "$operation_id")" || { emit_error OPERATION_STATE_FAILED "The rollback process identity could not be persisted."; return 5; }
    chmod 600 "$(operation_pid_file "$operation_id")" || { emit_error OPERATION_STATE_FAILED "The rollback process identity could not be protected."; return 5; }
)

command_dispatch() {
    [ "$#" -ge 3 ] || { emit_error "INVALID_ARGUMENT" "dispatch requires an operation and instance."; return 2; }
    verb="$1"; operation_id="$2"; instance_id="$3"
    valid_operation_id "$operation_id" || { emit_error "INVALID_OPERATION_ID" "The operation ID is invalid."; return 2; }
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    file="$(state_file "$operation_id")"
    if [ -r "$file" ]; then
        if [ "$verb" = recovery-clone ]; then
            [ "$#" -eq 6 ] || { emit_error "OPERATION_ID_CONFLICT" "This recovery clone operation ID is already bound to immutable arguments."; return 3; }
            binding="$(recovery_clone_binding_file "$operation_id")"; recovery_clone_binding_valid "$binding" || { emit_error "OPERATION_ID_CONFLICT" "This operation ID exists without a valid recovery clone binding."; return 3; }
            [ "$(record_value source_id "$binding")" = "$instance_id" ] && [ "$(record_value recovery_id "$binding")" = "$4" ] &&
                [ "$(record_value target_id "$binding")" = "$5" ] && [ "$(record_value target_port "$binding")" = "$6" ] || {
                    emit_error "OPERATION_ID_CONFLICT" "This recovery clone operation ID is bound to different immutable arguments."
                    return 3
                }
        elif [ "$verb" = clone ]; then
            [ "$#" -eq 5 ] || { emit_error "OPERATION_ID_CONFLICT" "This clone operation ID is already bound to immutable arguments."; return 3; }
            record="$(clone_record_file "$operation_id")"
            clone_record_valid "$record" || { emit_error "OPERATION_ID_CONFLICT" "This operation ID already exists without a matching clone record."; return 3; }
            [ "$(clone_record_value source_id "$record")" = "$instance_id" ] &&
                [ "$(clone_record_value target_id "$record")" = "$4" ] &&
                [ "$(clone_record_value target_port "$record")" = "$5" ] || {
                    emit_error "OPERATION_ID_CONFLICT" "This clone operation ID is already bound to different source, target, or port values."
                    return 3
                }
        elif [ "$verb" = update ]; then
            [ "$#" -eq 7 ] || { emit_error "OPERATION_ID_CONFLICT" "This update operation ID is already bound to immutable arguments."; return 3; }
            record="$(update_record_file "$operation_id")"
            update_record_valid "$record" || { emit_error "OPERATION_ID_CONFLICT" "This operation ID already exists without a matching update record."; return 3; }
            [ "$(record_value instance_id "$record")" = "$instance_id" ] &&
                [ "$(record_value target_sha "$record")" = "$5" ] &&
                [ "$(record_value recovery_id "$record")" = "$6" ] &&
                [ "$(record_value retention_seconds "$record")" = "$7" ] &&
                [ "$4" = pocketrisu ] || {
                    emit_error "OPERATION_ID_CONFLICT" "This update operation ID is already bound to different immutable arguments."
                    return 3
                }
        elif [ "$verb" = rollback ]; then
            [ "$#" -eq 6 ] || { emit_error "OPERATION_ID_CONFLICT" "This rollback operation ID is already bound to immutable arguments."; return 3; }
            record="$(rollback_record_file "$operation_id")"; rollback_record_valid "$record" || { emit_error "OPERATION_ID_CONFLICT" "This operation ID exists without a matching rollback record."; return 3; }
            [ "$(record_value instance_id "$record")" = "$instance_id" ] &&
                [ "$(record_value selected_recovery_id "$record")" = "$4" ] &&
                [ "$(record_value pre_recovery_id "$record")" = "$5" ] &&
                [ "$(record_value retention_seconds "$record")" = "$6" ] || {
                    emit_error "OPERATION_ID_CONFLICT" "This rollback operation ID is bound to different immutable arguments."
                    return 3
                }
        fi
        file="$(state_file "$operation_id")"
        cat "$file"; return 0
    fi
    case "$verb" in
        install)
            { [ "$#" -eq 5 ] || [ "$#" -eq 8 ]; } || { emit_error "INVALID_ARGUMENT" "install dispatch requires a trusted source revision and optional verified archive metadata."; return 2; }
            variant_id="$4"; revision="$5"
            valid_identifier "$variant_id" || { emit_error "INVALID_VARIANT_ID" "The variant ID is invalid."; return 2; }
            valid_commit "$revision" || { emit_error "INVALID_REVISION" "Installation requires a lowercase full commit SHA."; return 2; }
            write_state "$operation_id" "{\"ok\":true,\"operation\":\"install\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"QUEUED\",\"stage\":\"QUEUED\"}"
            if [ "$#" -eq 8 ]; then
                nohup "$BIN" _install "$operation_id" "$instance_id" "$variant_id" "$revision" "$6" "$7" "$8" >/dev/null 2>&1 < /dev/null &
            else
                nohup "$BIN" _install "$operation_id" "$instance_id" "$variant_id" "$revision" >/dev/null 2>&1 < /dev/null &
            fi
            ;;
        backup)
            [ "$#" -eq 3 ] || { emit_error "INVALID_ARGUMENT" "backup dispatch accepts only operation and instance."; return 2; }
            write_state "$operation_id" "{\"ok\":true,\"operation\":\"backup\",\"operationId\":\"$operation_id\",\"instanceId\":\"$instance_id\",\"state\":\"QUEUED\",\"stage\":\"QUEUED\"}"
            nohup "$BIN" _backup "$operation_id" "$instance_id" >/dev/null 2>&1 < /dev/null &
            ;;
        clone)
            [ "$#" -eq 5 ] || { emit_error "INVALID_ARGUMENT" "clone dispatch requires operation, source, target, and automatic port."; return 2; }
            dispatch_clone "$operation_id" "$instance_id" "$4" "$5" || return $?
            ;;
        recovery-clone)
            [ "$#" -eq 6 ] || { emit_error "INVALID_ARGUMENT" "recovery-clone dispatch requires operation, source instance, recovery point, target instance, and automatic port."; return 2; }
            dispatch_recovery_clone "$operation_id" "$instance_id" "$4" "$5" "$6" || return $?
            ;;
        update)
            [ "$#" -eq 7 ] || { emit_error "INVALID_ARGUMENT" "update dispatch requires operation, instance, PocketRisu variant, full target SHA, recovery point, and retention seconds."; return 2; }
            dispatch_update "$operation_id" "$instance_id" "$4" "$5" "$6" "$7" || return $?
            ;;
        rollback)
            [ "$#" -eq 6 ] || { emit_error "INVALID_ARGUMENT" "rollback dispatch requires operation, instance, selected recovery point, pre-rollback recovery point, and retention seconds."; return 2; }
            dispatch_rollback "$operation_id" "$instance_id" "$4" "$5" "$6" || return $?
            ;;
        *) emit_error "UNSUPPORTED_OPERATION" "This helper does not support that operation."; return 2 ;;
    esac
    file="$(state_file "$operation_id")"
    cat "$file"
}

command_backup_cleanup() {
    [ "$#" -eq 3 ] || { emit_error "INVALID_ARGUMENT" "backup-cleanup requires operation, instance, and token."; return 2; }
    operation_id="$1"; instance_id="$2"; supplied="$3"
    valid_operation_id "$operation_id" && valid_identifier "$instance_id" || { emit_error "INVALID_ARGUMENT" "The backup cleanup identifiers are invalid."; return 2; }
    token_file="$ROOT/backups/$operation_id.token"; pid_file="$ROOT/backups/$operation_id.pid"
    [ -r "$token_file" ] || { printf '{"ok":true,"operation":"backup-cleanup"}\n'; return 0; }
    expected="$(sed -n '1p' "$token_file")"
    [ "$supplied" = "$expected" ] || { emit_error "INVALID_TOKEN" "The backup cleanup token is invalid."; return 3; }
    pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
    case "$pid" in ''|*[!0-9]*) ;; *)
        cmdline="$( { tr '\000' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
        case "$cmdline" in *'risu-backup-server.cjs'*) kill -TERM "$pid" 2>/dev/null || true ;; esac
        ;;
    esac
    rm -f "$ROOT/backups/$operation_id.bin" "$ROOT/backups/$operation_id.port" "$token_file" "$pid_file"
    printf '{"ok":true,"operation":"backup-cleanup"}\n'
}

command_operation() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "operation requires an operation ID."; return 2; }
    valid_operation_id "$1" || { emit_error "INVALID_OPERATION_ID" "The operation ID is invalid."; return 2; }
    file="$(state_file "$1")"
    [ -r "$file" ] || { emit_error "OPERATION_NOT_FOUND" "No persisted helper operation has this ID."; return 3; }
    if grep -q '"operation":"install"' "$file" && grep -q '"state":"RUNNING"' "$file"; then
        pid_file="$(operation_pid_file "$1")"
        pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
        case "$pid" in
            ''|*[!0-9]*) alive=false ;;
            *)
                cmdline="$( { tr '\000' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
                case "$cmdline" in *'risu-manager _install '*"$1"*) alive=true ;; *) alive=false ;; esac
                ;;
        esac
        if [ "$alive" != true ]; then
            instance_id="$(sed -n 's/.*"instanceId":"\([a-z0-9-]*\)".*/\1/p' "$file")"
            operation_failed "$1" "$instance_id" "INSTALL_PROCESS_LOST" "The Termux installation process stopped before completion. Retry the installation."
            rm -f "$pid_file"
        fi
    fi
    if grep -q '"operation":"update"' "$file"; then
        record="$(update_record_file "$1")"
        if update_record_valid "$record"; then
            update_pid_file="$(operation_pid_file "$1")"
            update_pid="$(sed -n '1p' "$update_pid_file" 2>/dev/null || true)"
            case "$update_pid" in
                ''|*[!0-9]*) update_alive=false ;;
                *)
                    if kill -0 "$update_pid" 2>/dev/null && [ -r "/proc/$update_pid/cmdline" ]; then
                        update_cmdline="$( { tr '\000' ' ' < "/proc/$update_pid/cmdline"; } 2>/dev/null || true)"
                    else
                        update_cmdline=""
                    fi
                    case "$update_cmdline" in *'risu-manager _update '*"$1"*) update_alive=true ;; *) update_alive=false ;; esac
                    ;;
            esac
            update_needs_recovery=false
            if grep -q '"state":"QUEUED"\|"state":"RUNNING"' "$file" && [ "$update_alive" != true ]; then update_needs_recovery=true; fi
            if [ "$(record_value recovery_required "$record")" = 1 ] && [ "$update_alive" != true ]; then update_needs_recovery=true; fi
            if [ "$update_needs_recovery" = true ]; then
                recover_interrupted_update "$1" >/dev/null || true
                rm -f "$update_pid_file"
            fi
        fi
    fi
    if grep -q '"operation":"rollback"' "$file"; then
        record="$(rollback_record_file "$1")"
        if rollback_record_valid "$record"; then
            rollback_pid_file="$(operation_pid_file "$1")"; rollback_pid="$(sed -n '1p' "$rollback_pid_file" 2>/dev/null || true)"
            case "$rollback_pid" in
                ''|*[!0-9]*) rollback_alive=false ;;
                *)
                    if kill -0 "$rollback_pid" 2>/dev/null && [ -r "/proc/$rollback_pid/cmdline" ]; then rollback_cmdline="$( { tr '\000' ' ' < "/proc/$rollback_pid/cmdline"; } 2>/dev/null || true)"; else rollback_cmdline=""; fi
                    case "$rollback_cmdline" in *'risu-manager _rollback '*"$1"*) rollback_alive=true ;; *) rollback_alive=false ;; esac
                    ;;
            esac
            if grep -q '"state":"QUEUED"\|"state":"RUNNING"' "$file" && [ "$rollback_alive" != true ]; then
                recover_interrupted_rollback "$1" >/dev/null || true
                rm -f "$rollback_pid_file"
            fi
        fi
    fi
    if grep -q '"operation":"clone"' "$file"; then
        needs_recovery=false
        if grep -q '"state":"QUEUED"' "$file"; then
            pid_file="$(operation_pid_file "$1")"
            pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
            case "$pid" in ''|*[!0-9]*) alive=false ;; *) if kill -0 "$pid" 2>/dev/null; then alive=true; else alive=false; fi ;; esac
            [ "$alive" = true ] || needs_recovery=true
        elif grep -q '"state":"RUNNING"' "$file"; then
            pid_file="$(operation_pid_file "$1")"
            pid="$(sed -n '1p' "$pid_file" 2>/dev/null || true)"
            case "$pid" in
                ''|*[!0-9]*) alive=false ;;
                *)
                    if kill -0 "$pid" 2>/dev/null && [ -r "/proc/$pid/cmdline" ]; then
                        cmdline="$( { tr '\000' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
                    else
                        cmdline=""
                    fi
                    case "$cmdline" in *'risu-manager _clone '*"$1"*) alive=true ;; *) alive=false ;; esac
                    ;;
            esac
            [ "$alive" = true ] || needs_recovery=true
        elif grep -q '"state":"FAILED"' "$file" && grep -q '"sourceRestored":false' "$file"; then
            needs_recovery=true
        fi
        if [ "$needs_recovery" = true ]; then
            recover_interrupted_clone "$1" >/dev/null || true
            rm -f "$(operation_pid_file "$1")"
        fi
    fi
    file="$(state_file "$1")"
    cat "$file"
}

command_config_validate() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "config-validate requires an instance ID."; return 2; }
    instance_id="$1"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    if ! load_config "$instance_id"; then
        emit_error "INVALID_CONFIG" "The stored server configuration is missing or outside its allowlisted ranges."
        return 2
    fi
    if [ "$cfg_protocol" = "HTTPS" ] && ! validate_tls "$instance_id"; then
        emit_error "TLS_INVALID" "HTTPS requires a matching readable certificate and private key."
        return 2
    fi
    printf '{"ok":true,"operation":"config-validate","instanceId":"%s",%s}\n' "$instance_id" "$(config_json_fields)"
}

command_config_get() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "config-get requires an instance ID."; return 2; }
    instance_id="$1"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 2; }
    desired="$(config_json_fields)"
    applied_file="$(applied_config_file "$instance_id")"
    if [ -r "$applied_file" ]; then
        original="$loaded_config_file"; loaded_config_file="$applied_file"
        cfg_port="$(config_value port "$loaded_config_file")"; cfg_bind_scope="$(config_value bind_scope "$loaded_config_file")"
        cfg_protocol="$(config_value protocol "$loaded_config_file")"; cfg_body_mb="$(config_value body_limit_mb "$loaded_config_file")"
        cfg_save_seconds="$(config_value save_interval_seconds "$loaded_config_file")"; cfg_snapshot_minutes="$(config_value snapshot_interval_minutes "$loaded_config_file")"
        cfg_snapshot_count="$(config_value snapshot_retention_count "$loaded_config_file")"; cfg_snapshot_mb="$(config_value snapshot_max_mb "$loaded_config_file")"
        if [ "$(wc -l < "$loaded_config_file" | tr -d ' ')" = "9" ] &&
           [ "$(config_value schema "$loaded_config_file")" = "1" ] &&
           [ "$(grep -Ec '^(schema|port|bind_scope|protocol|body_limit_mb|save_interval_seconds|snapshot_interval_minutes|snapshot_retention_count|snapshot_max_mb)=' "$loaded_config_file")" = "9" ] &&
           valid_config_values "$cfg_port" "$cfg_bind_scope" "$cfg_protocol" "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"; then
            applied=",\"applied\":{$(config_json_fields)}"
        else
            applied=',"applied":null'
        fi
        loaded_config_file="$original"
    else
        applied=',"applied":null'
    fi
    last_error="$(sed -n '1p' "$(instance_dir "$instance_id")/manager/last-apply-error" 2>/dev/null || true)"
    printf '{"ok":true,"operation":"config-get","instanceId":"%s",%s%s,"lastApplyError":"%s"}\n' "$instance_id" "$desired" "$applied" "$(json_escape "$last_error")"
}

command_config_set() {
    [ "$#" -eq 9 ] || { emit_error "INVALID_ARGUMENT" "config-set requires an instance ID and eight normalized values."; return 2; }
    instance_id="$1"; shift
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    valid_config_values "$@" || { emit_error "INVALID_CONFIG" "A server setting is outside its allowlisted range."; return 2; }
    write_config_file "$(config_file "$instance_id")" "$@"
    command_config_get "$instance_id"
}

valid_domain() {
    value="$1"
    [ "${#value}" -ge 1 ] && [ "${#value}" -le 253 ] || return 1
    case "$value" in *[!A-Za-z0-9.-]*|.*|*.|*..*) return 1 ;; esac
    old_ifs="$IFS"; IFS='.'
    set -- $value
    IFS="$old_ifs"
    [ "$#" -ge 2 ] || return 1
    for label in "$@"; do
        [ "${#label}" -ge 1 ] && [ "${#label}" -le 63 ] || return 1
        case "$label" in -*|*-) return 1 ;; esac
    done
}

valid_email() {
    value="$1"
    [ "${#value}" -ge 3 ] && [ "${#value}" -le 254 ] || return 1
    case "$value" in *' '*|*'/'*|*'\\'*|*'<'*|*'>'*|*'|'*|*'"'*|*'`'*) return 1 ;; esac
    case "$value" in *@*.*) ;; *) return 1 ;; esac
}

valid_challenge_port() {
    valid_uint "$1" && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

acme_state_valid() {
    file="$1"
    [ -r "$file" ] || return 1
    [ "$(wc -l < "$file" | tr -d ' ')" = "9" ] || return 1
    [ "$(config_value schema "$file")" = "1" ] || return 1
    [ "$(config_value mode "$file")" = "LETS_ENCRYPT" ] || return 1
    valid_domain "$(config_value domain "$file")" || return 1
    valid_email "$(config_value email "$file")" || return 1
    valid_challenge_port "$(config_value challenge_port "$file")" || return 1
}

acme_home() { printf '%s/instances/%s/manager/acme' "$ROOT" "$1"; }
acme_binary() { printf '%s/acme.sh-%s/acme.sh' "$ACME_ROOT" "$ACME_VERSION"; }

write_tls_state() {
    instance_id="$1"; domain="$2"; email="$3"; challenge_port="$4"; expires="$5"; last_check="$6"; last_success="$7"; last_error="$8"
    destination="$(tls_state_file "$instance_id")"; directory="$(dirname "$destination")"; temporary="$destination.tmp.$$"
    mkdir -p "$directory"; chmod 700 "$directory"; umask 077
    {
        printf 'schema=1\nmode=LETS_ENCRYPT\ndomain=%s\nemail=%s\nchallenge_port=%s\ncertificate_expires_at=%s\nlast_renew_check_at=%s\nlast_renew_success_at=%s\nlast_renew_error=%s\n' \
            "$domain" "$email" "$challenge_port" "$expires" "$last_check" "$last_success" "$last_error"
    } > "$temporary"
    chmod 600 "$temporary"; mv -f "$temporary" "$destination"
}

json_array_records() {
    domain="$1"; family="$2"; result="[]"
    if command -v dig >/dev/null 2>&1; then
        if [ "$family" = "4" ]; then records="$(dig +short A "$domain" 2>/dev/null | sort -u || true)"; else records="$(dig +short AAAA "$domain" 2>/dev/null | sort -u || true)"; fi
        for record in $records; do
            case "$record" in *[!0-9A-Fa-f:.]*) continue ;; esac
            escaped="$(json_escape "$record")"
            [ "$result" = "[]" ] && result="[\"$escaped\"]" || result="${result%]}\",\"$escaped\"]"
        done
    elif command -v getent >/dev/null 2>&1; then
        if [ "$family" = "4" ]; then records="$(getent ahostsv4 "$domain" 2>/dev/null | awk '{print $1}' | sort -u || true)"; else records="$(getent ahostsv6 "$domain" 2>/dev/null | awk '{print $1}' | sort -u || true)"; fi
        for record in $records; do
            case "$record" in *[!0-9A-Fa-f:.]*) continue ;; esac
            escaped="$(json_escape "$record")"
            [ "$result" = "[]" ] && result="[\"$escaped\"]" || result="${result%]}\",\"$escaped\"]"
        done
    fi
    printf '%s' "$result"
}

acme_dns_json() {
    domain="$1"; printf '%s|%s' "$(json_array_records "$domain" 4)" "$(json_array_records "$domain" 6)"
}

acme_archive_ready() {
    binary="$(acme_binary)"
    [ -x "$binary" ] && [ -d "$(dirname "$binary")/dnsapi" ] &&
        [ "$(sed -n '1p' "$(dirname "$binary")/.archive-sha256" 2>/dev/null || true)" = "$ACME_ARCHIVE_SHA256" ] || return 1
}

command_acme_install() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "acme-install requires an instance ID."; return 2; }
    instance_id="$1"; valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    mkdir -p "$ACME_ROOT"; chmod 700 "$ACME_ROOT"; binary="$(acme_binary)"
    dependency_log="$LOGS/acme-dependencies.log"; umask 077
    dependencies_ready=true
    if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1 || ! command -v dig >/dev/null 2>&1; then dependencies_ready=false; fi
    if [ "$dependencies_ready" = true ] && acme_archive_ready; then printf '{"ok":true,"operation":"acme-install","version":"%s"}\n' "$ACME_VERSION"; return 0; fi
    lock="$ROOT/locks/acme-install"; mkdir -p "$ROOT/locks"; chmod 700 "$ROOT/locks"
    if ! mkdir "$lock" 2>/dev/null; then emit_error "ACME_ALREADY_RUNNING" "Another ACME client installation is already active."; return 4; fi
    archive="$ACME_ROOT/acme.sh-$ACME_VERSION.tar.gz"; staging="$ACME_ROOT/install-$ACME_VERSION-$$"
    cleanup() { rm -rf "$staging" "$archive"; rmdir "$lock" 2>/dev/null || true; }
    trap cleanup EXIT INT TERM
    if [ "$dependencies_ready" != true ]; then
        if ! pkg install -y curl tar openssl-tool coreutils dnsutils > "$dependency_log" 2>&1; then
            chmod 600 "$dependency_log" 2>/dev/null || true
            trap - EXIT INT TERM; cleanup
            emit_error "ACME_DEPENDENCY_FAILED" "Termux could not install the bounded ACME dependencies."
            return 5
        fi
        chmod 600 "$dependency_log" 2>/dev/null || true
    fi
    if acme_archive_ready; then trap - EXIT INT TERM; cleanup; printf '{"ok":true,"operation":"acme-install","version":"%s"}\n' "$ACME_VERSION"; return 0; fi
    if ! command -v curl >/dev/null 2>&1 || ! curl --proto '=https' --tlsv1.2 --fail --location --output "$archive" "$ACME_ARCHIVE_URL" >/dev/null 2>&1; then
        trap - EXIT INT TERM; cleanup; emit_error "ACME_DOWNLOAD_FAILED" "The pinned acme.sh release could not be downloaded."; return 5
    fi
    actual="$(sha256sum "$archive" 2>/dev/null | awk '{print $1}')"
    if [ "$actual" != "$ACME_ARCHIVE_SHA256" ]; then trap - EXIT INT TERM; cleanup; emit_error "ACME_CHECKSUM_MISMATCH" "The pinned acme.sh release failed SHA-256 verification."; return 5; fi
    mkdir "$staging"; tar -xzf "$archive" -C "$staging" --no-same-owner --no-same-permissions >/dev/null 2>&1 || { trap - EXIT INT TERM; cleanup; emit_error "ACME_EXTRACT_FAILED" "The verified acme.sh release could not be extracted."; return 5; }
    source_dir="$staging/acme.sh-$ACME_VERSION"; target_dir="$ACME_ROOT/acme.sh-$ACME_VERSION"
    [ -f "$source_dir/acme.sh" ] && [ -d "$source_dir/dnsapi" ] || { trap - EXIT INT TERM; cleanup; emit_error "ACME_INSTALL_FAILED" "The verified acme.sh release is incomplete."; return 5; }
    rm -rf "$target_dir"; mkdir "$target_dir"; cp -R "$source_dir/." "$target_dir/"; chmod 700 "$target_dir" "$binary"; find "$target_dir" -type f ! -name acme.sh -exec chmod 600 {} \;
    printf '%s\n' "$ACME_ARCHIVE_SHA256" > "$target_dir/.archive-sha256"; chmod 600 "$target_dir/.archive-sha256"
    trap - EXIT INT TERM; cleanup
    printf '{"ok":true,"operation":"acme-install","version":"%s","sha256":"%s"}\n' "$ACME_VERSION" "$ACME_ARCHIVE_SHA256"
}

certificate_expiry_epoch() {
    openssl x509 -in "$1" -noout -enddate 2>/dev/null | sed -n 's/^notAfter=//p' | xargs -r -I{} date -d '{}' +%s 2>/dev/null || true
}

validate_certificate_files() {
    key="$1"; cert="$2"; domain="$3"
    [ -r "$key" ] && [ -r "$cert" ] && command -v openssl >/dev/null 2>&1 || return 1
    key_pub="${key}.pub.$$"; cert_pub="${cert}.pub.$$"
    openssl pkey -in "$key" -pubout -out "$key_pub" 2>/dev/null || return 1
    openssl x509 -in "$cert" -pubkey -noout > "$cert_pub" 2>/dev/null || return 1
    cmp -s "$key_pub" "$cert_pub" || { rm -f "$key_pub" "$cert_pub"; return 2; }
    openssl x509 -in "$cert" -noout -checkend 0 >/dev/null 2>&1 || { rm -f "$key_pub" "$cert_pub"; return 3; }
    expires="$(certificate_expiry_epoch "$cert")"; valid_uint "$expires" && [ "$expires" -gt "$(date +%s)" ] || { rm -f "$key_pub" "$cert_pub"; return 3; }
    openssl pkey -in "$key" -text -noout 2>/dev/null | grep -Eq 'ASN1 OID: prime256v1|NIST CURVE: P-256' || { rm -f "$key_pub" "$cert_pub"; return 6; }
    san="$(openssl x509 -in "$cert" -noout -ext subjectAltName 2>/dev/null || true)"
    printf '%s\n' "$san" | tr ',' '\n' | sed 's/^[[:space:]]*//' | grep -Fx "DNS:$domain" >/dev/null || { rm -f "$key_pub" "$cert_pub"; return 4; }
    [ "$(grep -c 'BEGIN CERTIFICATE' "$cert" 2>/dev/null || true)" -ge 2 ] || { rm -f "$key_pub" "$cert_pub"; return 5; }
    openssl crl2pkcs7 -nocrl -certfile "$cert" 2>/dev/null | openssl pkcs7 -print_certs -noout >/dev/null 2>&1 || { rm -f "$key_pub" "$cert_pub"; return 5; }
    rm -f "$key_pub" "$cert_pub"
}

run_acme_logged() {
    destination="$1"; shift; raw="$destination.raw.$$"
    "$@" > "$raw" 2>&1
    status=$?
    # ACME responses can contain bearer tokens and account identifiers. Keep a
    # useful private diagnostic log while replacing long token-like values.
    sed -E 's/[A-Za-z0-9_+=\/-]{24,}/[REDACTED]/g' "$raw" >> "$destination" 2>/dev/null || printf '%s\n' 'ACME output could not be sanitized.' >> "$destination"
    rm -f "$raw"
    return "$status"
}

tls_backup_and_deploy() {
    instance_id="$1"; source_key="$2"; source_cert="$3"; domain="$4"; repo="$(instance_dir "$instance_id")/repo"; tls="$repo/server/node/ssl/certificate"; staging="$(instance_dir "$instance_id")/manager/tls/staging"; backup="$(instance_dir "$instance_id")/manager/tls/backup-$(date +%s)-$$"
    mkdir -p "$staging" "$backup"; chmod 700 "$staging" "$backup"
    cp "$source_key" "$staging/server.key"; cp "$source_cert" "$staging/server.crt"; chmod 600 "$staging/server.key" "$staging/server.crt"
    key_pub="$staging/key.pub.$$"; cert_pub="$staging/cert.pub.$$"; validate_certificate_files "$staging/server.key" "$staging/server.crt" "$domain"; result=$?; rm -f "$key_pub" "$cert_pub"
    [ "$result" -eq 0 ] || return "$result"
    had_key=0; had_cert=0; [ -f "$tls/server.key" ] && { cp "$tls/server.key" "$backup/server.key"; had_key=1; }; [ -f "$tls/server.crt" ] && { cp "$tls/server.crt" "$backup/server.crt"; had_cert=1; }
    mkdir -p "$tls"; cp "$staging/server.key" "$tls/server.key.tmp.$$"; cp "$staging/server.crt" "$tls/server.crt.tmp.$$"; chmod 600 "$tls/server.key.tmp.$$" "$tls/server.crt.tmp.$$"; mv -f "$tls/server.key.tmp.$$" "$tls/server.key"; mv -f "$tls/server.crt.tmp.$$" "$tls/server.crt"
    printf '%s|%s|%s' "$backup" "$had_key" "$had_cert"
}

tls_restore_backup() {
    tls="$1"; backup="$2"; had_key="$3"; had_cert="$4"
    if [ "$had_key" = 1 ]; then cp "$backup/server.key" "$tls/server.key.tmp.$$"; chmod 600 "$tls/server.key.tmp.$$"; mv -f "$tls/server.key.tmp.$$" "$tls/server.key"; else rm -f "$tls/server.key"; fi
    if [ "$had_cert" = 1 ]; then cp "$backup/server.crt" "$tls/server.crt.tmp.$$"; chmod 600 "$tls/server.crt.tmp.$$"; mv -f "$tls/server.crt.tmp.$$" "$tls/server.crt"; else rm -f "$tls/server.crt"; fi
}

acme_status_json() {
    instance_id="$1"; file="$(tls_state_file "$instance_id")"; domain="$(config_value domain "$file" 2>/dev/null || true)"; email="$(config_value email "$file" 2>/dev/null || true)"; challenge_port="$(config_value challenge_port "$file" 2>/dev/null || true)"; expires="$(config_value certificate_expires_at "$file" 2>/dev/null || true)"; check="$(config_value last_renew_check_at "$file" 2>/dev/null || true)"; success="$(config_value last_renew_success_at "$file" 2>/dev/null || true)"; error="$(config_value last_renew_error "$file" 2>/dev/null || true)"
    printf '{"ok":true,"operation":"acme-certificate-status","instanceId":"%s","certificateMode":"LETS_ENCRYPT","domain":"%s","email":"%s","challengePort":%s,"certificateExpiresAt":%s,"autoRenew":true,"lastRenewCheckAt":%s,"lastRenewSuccessAt":%s,"lastRenewError":"%s"}\n' "$instance_id" "$(json_escape "$domain")" "$(json_escape "$email")" "${challenge_port:-null}" "${expires:-null}" "${check:-null}" "${success:-null}" "$(json_escape "$error")"
}

command_acme_preflight() {
    [ "$#" -eq 4 ] || { emit_error "INVALID_ARGUMENT" "acme-preflight requires instance, domain, email, and challenge port."; return 2; }
    instance_id="$1"; domain="$2"; email="$3"; challenge_port="$4"; valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }; valid_domain "$domain" || { emit_error "ACME_INVALID_DOMAIN" "The domain is invalid for a single-host certificate."; return 2; }; valid_email "$email" || { emit_error "ACME_INVALID_EMAIL" "The ACME email address is invalid."; return 2; }; valid_challenge_port "$challenge_port" || { emit_error "ACME_INVALID_PORT" "The HTTP-01 challenge port must be between 1024 and 65535."; return 2; }
    dns="$(acme_dns_json "$domain")"; dns_a="${dns%%|*}"; dns_aaaa="${dns#*|}"; available=false; port_available 0.0.0.0 "$challenge_port" && available=true
    printf '{"ok":true,"operation":"acme-preflight","instanceId":"%s","domain":"%s","dnsA":%s,"dnsAAAA":%s,"internalPortAvailable":%s,"externalPortCheck":"CHECK_REQUIRED","message":"Forward external TCP 80 to the internal challenge port and verify DNS/CGNAT manually."}\n' "$instance_id" "$(json_escape "$domain")" "$dns_a" "$dns_aaaa" "$available"
}

command_acme_issue() {
    [ "$#" -eq 4 ] || { emit_error "INVALID_ARGUMENT" "acme-issue requires instance, domain, email, and challenge port."; return 2; }
    instance_id="$1"; domain="$2"; email="$3"; challenge_port="$4"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    valid_domain "$domain" || { emit_error "ACME_INVALID_DOMAIN" "The domain is invalid for a single-host certificate."; return 2; }
    valid_email "$email" || { emit_error "ACME_INVALID_EMAIL" "The ACME email address is invalid."; return 2; }
    valid_challenge_port "$challenge_port" || { emit_error "ACME_INVALID_PORT" "The HTTP-01 challenge port must be between 1024 and 65535."; return 2; }
    instance="$(instance_dir "$instance_id")"; repo="$instance/repo"; metadata="$instance/metadata.json"; variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" 2>/dev/null | sed -n '1p')"
    [ "$variant_id" = "pocketrisu" ] || { emit_error "UNSUPPORTED_VARIANT" "ACME HTTPS is supported only for PocketRisu."; return 3; }
    load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 3; }
    port_available 0.0.0.0 "$challenge_port" || { emit_error "ACME_HTTP01_UNREACHABLE" "The internal HTTP-01 challenge port is already in use."; return 4; }
    command_acme_install "$instance_id" >/dev/null || { emit_error "ACME_INSTALL_FAILED" "The pinned acme.sh client is not available."; return 4; }
    lock="$ROOT/locks/acme-$instance_id"; mkdir -p "$ROOT/locks"; chmod 700 "$ROOT/locks"
    if ! mkdir "$lock" 2>/dev/null; then emit_error "ACME_ALREADY_RUNNING" "Another ACME operation is already active for this PocketRisu instance."; return 4; fi
    log="$LOGS/acme-$instance_id.log"; umask 077; : > "$log"; chmod 600 "$log"
    old_config="$instance/manager/server.conf"; config_backup="$instance/manager/tls/config-backup.$$"; old_state="$(tls_state_file "$instance_id")"; state_backup="$instance/manager/tls/state-backup.$$"; backup=""; had_key=0; had_cert=0; was_running=0; rollback_needed=0
    mkdir -p "$instance/manager/tls"; cp "$old_config" "$config_backup"; [ -f "$old_state" ] && cp "$old_state" "$state_backup" || true
    valid_process "$instance_id" && was_running=1
    cleanup_acme_issue() { rm -f "$config_backup" "$state_backup"; rmdir "$lock" 2>/dev/null || true; }
    trap cleanup_acme_issue EXIT INT TERM
    home="$(acme_home "$instance_id")"; staging_home="$home/staging"; production_home="$home/production"; code_home="$(dirname "$(acme_binary)")"; mkdir -p "$staging_home" "$production_home"; chmod 700 "$home" "$staging_home" "$production_home"; binary="$(acme_binary)"
    set +e
    run_acme_logged "$log" "$binary" --home "$code_home" --config-home "$staging_home" --cert-home "$staging_home/certs" --server letsencrypt_test --issue --domain "$domain" --standalone --httpport "$challenge_port" --keylength ec-256 --accountemail "$email" --force
    staging_status=$?
    set -e
    if [ "$staging_status" -ne 0 ]; then
        trap - EXIT INT TERM; cleanup_acme_issue; emit_error "ACME_STAGING_FAILED" "Let's Encrypt staging validation failed. Check the redacted ACME operation log."; return 5
    fi
    set +e
    run_acme_logged "$log" "$binary" --home "$code_home" --config-home "$production_home" --cert-home "$production_home/certs" --server letsencrypt --issue --domain "$domain" --standalone --httpport "$challenge_port" --keylength ec-256 --accountemail "$email"
    production_status=$?
    set -e
    if [ "$production_status" -ne 0 ] && [ "$production_status" -ne 2 ]; then
        trap - EXIT INT TERM; cleanup_acme_issue; emit_error "ACME_PRODUCTION_FAILED" "Let's Encrypt production issuance failed; no PocketRisu certificate was changed."; return 5
    fi
    key="$production_home/certs/${domain}_ecc/$domain.key"; cert="$production_home/certs/${domain}_ecc/fullchain.cer"
    [ -r "$key" ] && [ -r "$cert" ] || { trap - EXIT INT TERM; cleanup_acme_issue; emit_error "CERT_INVALID" "acme.sh did not produce a complete certificate and private key."; return 5; }
    if ! deploy_result="$(tls_backup_and_deploy "$instance_id" "$key" "$cert" "$domain")"; then trap - EXIT INT TERM; cleanup_acme_issue; emit_error "CERT_INVALID" "The issued certificate failed key, validity, SAN, or full-chain validation."; return 5; fi
    backup="${deploy_result%%|*}"; remainder="${deploy_result#*|}"; had_key="${remainder%%|*}"; had_cert="${remainder##*|}"; rollback_needed=1
    expires_seconds="$(certificate_expiry_epoch "$cert")"; expires_ms="${expires_seconds:-0}000"; now_ms="$(( $(date +%s) * 1000 ))"
    write_tls_state "$instance_id" "$domain" "$email" "$challenge_port" "$expires_ms" "$now_ms" "$now_ms" ""
    if [ "$was_running" -eq 1 ]; then command_stop "$instance_id" >/dev/null || true; fi
    write_config_file "$old_config" "$cfg_port" "$cfg_bind_scope" HTTPS "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"
    if ! command_config_validate "$instance_id" >/dev/null; then deploy_failed=1; else deploy_failed=0; fi
    if [ "$deploy_failed" -eq 0 ] && ! command_start "$instance_id" >/dev/null; then deploy_failed=1; fi
    if [ "$deploy_failed" -ne 0 ]; then
        [ "$was_running" -eq 1 ] && command_stop "$instance_id" >/dev/null 2>&1 || true
        tls_restore_backup "$repo/server/node/ssl/certificate" "$backup" "$had_key" "$had_cert" || true
        cp "$config_backup" "$old_config"; chmod 600 "$old_config"
        if [ -f "$state_backup" ]; then cp "$state_backup" "$old_state"; chmod 600 "$old_state"; else rm -f "$old_state"; fi
        if [ "$was_running" -eq 1 ]; then command_start "$instance_id" >/dev/null 2>&1 || true; else command_stop "$instance_id" >/dev/null 2>&1 || true; fi
        trap - EXIT INT TERM; cleanup_acme_issue; emit_error "HTTPS_HEALTHCHECK_FAILED" "PocketRisu did not pass the HTTPS health check; the previous certificate and settings were restored."; return 6
    fi
    trap - EXIT INT TERM; cleanup_acme_issue
    printf '{"ok":true,"operation":"acme-issue","instanceId":"%s","certificateMode":"LETS_ENCRYPT","domain":"%s","email":"%s","challengePort":%s,"certificateExpiresAt":%s,"autoRenew":true,"lastRenewCheckAt":%s,"lastRenewSuccessAt":%s}\n' "$instance_id" "$(json_escape "$domain")" "$(json_escape "$email")" "$challenge_port" "$expires_ms" "$now_ms" "$now_ms"
}

command_acme_renew_check() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "acme-renew-check requires an instance ID."; return 2; }
    instance_id="$1"; valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }; state="$(tls_state_file "$instance_id")"
    if ! acme_state_valid "$state"; then printf '{"ok":true,"operation":"acme-renew-check","instanceId":"%s","renewed":false,"skipped":true,"reason":"NOT_CONFIGURED"}\n' "$instance_id"; return 0; fi
    domain="$(config_value domain "$state")"; email="$(config_value email "$state")"; challenge_port="$(config_value challenge_port "$state")"; instance="$(instance_dir "$instance_id")"; repo="$instance/repo"; load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 3; }
    command_acme_install "$instance_id" >/dev/null || { emit_error "ACME_INSTALL_FAILED" "The pinned acme.sh client is not available."; return 4; }
    lock="$ROOT/locks/acme-$instance_id"; mkdir -p "$ROOT/locks"; chmod 700 "$ROOT/locks"; if ! mkdir "$lock" 2>/dev/null; then emit_error "ACME_ALREADY_RUNNING" "Another ACME operation is already active for this PocketRisu instance."; return 4; fi
    log="$LOGS/acme-renew-$instance_id.log"; umask 077; : > "$log"; chmod 600 "$log"; home="$(acme_home "$instance_id")"; production_home="$home/production"; code_home="$(dirname "$(acme_binary)")"; binary="$(acme_binary)"; old_hash="$(sha256sum "$repo/server/node/ssl/certificate/server.crt" 2>/dev/null | awk '{print $1}')"; now_ms="$(( $(date +%s) * 1000 ))"
    set +e
    run_acme_logged "$log" "$binary" --home "$code_home" --config-home "$production_home" --cert-home "$production_home/certs" --server letsencrypt --cron
    renew_status=$?
    set -e
    if [ "$renew_status" -ne 0 ]; then
        old_error="$(config_value last_renew_error "$state")"; write_tls_state "$instance_id" "$domain" "$email" "$challenge_port" "$(config_value certificate_expires_at "$state")" "$now_ms" "$(config_value last_renew_success_at "$state")" "ACME renewal command failed"; rmdir "$lock" 2>/dev/null || true; emit_error "ACME_RENEW_FAILED" "Certificate renewal failed; the existing certificate was kept."; return 5
    fi
    key="$production_home/certs/${domain}_ecc/$domain.key"; cert="$production_home/certs/${domain}_ecc/fullchain.cer"; new_hash="$(sha256sum "$cert" 2>/dev/null | awk '{print $1}')"
    if [ -n "$old_hash" ] && [ "$old_hash" = "$new_hash" ]; then last_success="$(config_value last_renew_success_at "$state")"; expires="$(config_value certificate_expires_at "$state")"; write_tls_state "$instance_id" "$domain" "$email" "$challenge_port" "$expires" "$now_ms" "$last_success" ""; rmdir "$lock" 2>/dev/null || true; printf '{"ok":true,"operation":"acme-renew-check","instanceId":"%s","renewed":false,"certificateMode":"LETS_ENCRYPT","certificateExpiresAt":%s,"lastRenewCheckAt":%s,"lastRenewSuccessAt":%s}\n' "$instance_id" "${expires:-null}" "$now_ms" "${last_success:-null}"; return 0; fi
    was_running=0; valid_process "$instance_id" && was_running=1; config_backup="$instance/manager/tls/config-renew-backup.$$"; state_backup="$instance/manager/tls/state-renew-backup.$$"; cp "$state" "$state_backup"; cp "$instance/manager/server.conf" "$config_backup"; deploy_result=""; if ! deploy_result="$(tls_backup_and_deploy "$instance_id" "$key" "$cert" "$domain")"; then cp "$state_backup" "$state"; rm -f "$config_backup" "$state_backup"; rmdir "$lock" 2>/dev/null || true; emit_error "CERT_INVALID" "The renewed certificate failed validation; the existing certificate was kept."; return 5; fi
    backup="${deploy_result%%|*}"; remainder="${deploy_result#*|}"; had_key="${remainder%%|*}"; had_cert="${remainder##*|}"; expires_seconds="$(certificate_expiry_epoch "$cert")"; expires_ms="${expires_seconds:-0}000"; write_tls_state "$instance_id" "$domain" "$email" "$challenge_port" "$expires_ms" "$now_ms" "$now_ms" ""
    renew_failed=0; [ "$was_running" -eq 1 ] && { command_stop "$instance_id" >/dev/null 2>&1 || true; command_start "$instance_id" >/dev/null 2>&1 || renew_failed=1; }
    if [ "$renew_failed" -ne 0 ]; then command_stop "$instance_id" >/dev/null 2>&1 || true; tls_restore_backup "$repo/server/node/ssl/certificate" "$backup" "$had_key" "$had_cert" || true; cp "$config_backup" "$instance/manager/server.conf"; cp "$state_backup" "$state"; chmod 600 "$instance/manager/server.conf" "$state"; [ "$was_running" -eq 1 ] && command_start "$instance_id" >/dev/null 2>&1 || true; rm -f "$config_backup" "$state_backup"; rmdir "$lock" 2>/dev/null || true; emit_error "HTTPS_HEALTHCHECK_FAILED" "Renewed HTTPS failed its health check; the previous certificate was restored."; return 6; fi
    rm -f "$config_backup" "$state_backup"; rmdir "$lock" 2>/dev/null || true; printf '{"ok":true,"operation":"acme-renew-check","instanceId":"%s","renewed":true,"certificateMode":"LETS_ENCRYPT","certificateExpiresAt":%s,"lastRenewCheckAt":%s,"lastRenewSuccessAt":%s}\n' "$instance_id" "$expires_ms" "$now_ms" "$now_ms"
}

command_acme_certificate_status() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "acme-certificate-status requires an instance ID."; return 2; }
    valid_identifier "$1" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    file="$(tls_state_file "$1")"; [ -r "$file" ] && acme_state_valid "$file" || { printf '{"ok":true,"operation":"acme-certificate-status","instanceId":"%s","certificateMode":"MANUAL","autoRenew":false}\n' "$1"; return 0; }
    acme_status_json "$1"
}

command_acme_disable() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "acme-disable requires an instance ID."; return 2; }
    instance_id="$1"; valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }; instance="$(instance_dir "$instance_id")"; load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 3; }; old_config="$instance/manager/server.conf"; backup="$instance/manager/tls/disable-config-backup.$$"; state_backup="$instance/manager/tls/disable-state-backup.$$"; cp "$old_config" "$backup"; [ -f "$(tls_state_file "$instance_id")" ] && cp "$(tls_state_file "$instance_id")" "$state_backup" || true; was_running=0; valid_process "$instance_id" && was_running=1; [ "$was_running" -eq 1 ] && command_stop "$instance_id" >/dev/null 2>&1 || true; write_config_file "$old_config" "$cfg_port" "$cfg_bind_scope" HTTP "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"; if [ "$was_running" -eq 1 ] && ! command_start "$instance_id" >/dev/null 2>&1; then command_stop "$instance_id" >/dev/null 2>&1 || true; cp "$backup" "$old_config"; chmod 600 "$old_config"; [ -f "$state_backup" ] && cp "$state_backup" "$(tls_state_file "$instance_id")" || true; command_start "$instance_id" >/dev/null 2>&1 || true; rm -f "$backup" "$state_backup"; emit_error "SERVER_RESTART_FAILED" "HTTPS could not be disabled safely; the previous configuration was restored."; return 5; fi; rm -f "$backup" "$state_backup" "$(tls_state_file "$instance_id")"; printf '{"ok":true,"operation":"acme-disable","instanceId":"%s","protocol":"HTTP","certificateMode":"MANUAL","preservedCredentials":true}\n' "$instance_id"
}

validate_tls() {
    instance_id="$1"; repo="$(instance_dir "$instance_id")/repo"; tls="$repo/server/node/ssl/certificate"
    key="$tls/server.key"; cert="$tls/server.crt"
    [ -r "$key" ] && [ -r "$cert" ] && command -v openssl >/dev/null 2>&1 || return 1
    key_pub="$tls/.key-pub.$$"; cert_pub="$tls/.cert-pub.$$"
    trap 'rm -f "$key_pub" "$cert_pub"' EXIT INT TERM
    openssl pkey -in "$key" -pubout -out "$key_pub" >/dev/null 2>&1 || return 1
    openssl x509 -in "$cert" -pubkey -noout > "$cert_pub" 2>/dev/null || return 1
    cmp -s "$key_pub" "$cert_pub" || return 1
    rm -f "$key_pub" "$cert_pub"
    trap - EXIT INT TERM
}

port_available() {
    probe_host="$1"; probe_port="$2"
    node -e "const net=require('net');const s=net.createServer();s.once('error',()=>process.exit(1));s.listen(Number(process.argv[1]),process.argv[2],()=>s.close(()=>process.exit(0)));" "$probe_port" "$probe_host" >/dev/null 2>&1
}

apply_snapshot_config() {
    repo="$1"; count="$2"; max_mb="$3"
    db="$repo/save/risuai.db"
    [ -f "$db" ] || return 0
    node -e "const path=require('path');const D=require(path.join(process.argv[1],'node_modules','better-sqlite3'));const db=new D(process.argv[2]);const q=db.prepare('INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value');const t=db.transaction(()=>{q.run('config/snapshot-max-count',Buffer.from(process.argv[3]));q.run('config/snapshot-max-bytes',Buffer.from(String(Number(process.argv[4])*1024*1024)));});t();db.close();" "$repo" "$db" "$count" "$max_mb"
}

load_db_config() {
    instance="$1"; db_config="$instance/manager/db.conf"
    [ -r "$db_config" ] && [ "$(wc -l < "$db_config" | tr -d ' ')" = "5" ] || return 1
    db_port="$(config_value db_port "$db_config")"; db_user="$(config_value db_user "$db_config")"
    db_password="$(config_value db_password "$db_config")"; db_name="$(config_value db_name "$db_config")"
    db_socket="$(config_value db_socket "$db_config")"
    valid_uint "$db_port" && [ "$db_port" -ge 1024 ] && [ "$db_port" -le 65535 ] || return 1
    case "$db_user" in ''|*[!A-Za-z0-9_]*) return 1 ;; esac
    case "$db_password" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ "${#db_password}" -eq 48 ] && [ "$db_name" = "risu_manager" ] || return 1
    instance_id="${instance##*/}"
    [ "$db_socket" = "$ROOT/sockets/$instance_id" ] || return 1
}

start_profile_storage() {
    instance="$1"; log="$2"
    profile_database_url=""; profile_save_path="$instance/save"
    [ "$profile_storage" = "postgres" ] || return 0
    load_db_config "$instance" || return 1
    data="$instance/postgres/data"
    if ! pg_ctl -D "$data" status >/dev/null 2>&1; then
        port_available 127.0.0.1 "$db_port" || return 1
        pg_ctl -D "$data" -l "$instance/postgres/postgres.log" -o "-h 127.0.0.1 -p $db_port -k $db_socket" start >> "$log" 2>&1 || return 1
    fi
    profile_database_url="postgresql://$db_user:$db_password@127.0.0.1:$db_port/$db_name"
}

stop_profile_storage() {
    instance="$1"
    [ "$profile_storage" = "postgres" ] || return 0
    data="$instance/postgres/data"
    [ -f "$data/PG_VERSION" ] || return 0
    pg_ctl -D "$data" status >/dev/null 2>&1 || return 0
    pg_ctl -D "$data" stop -m fast >/dev/null 2>&1 || return 1
}

command_start() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "start requires an instance ID."; return 2; }
    instance_id="$1"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    instance="$(instance_dir "$instance_id")"; repo="$instance/repo"; run="$instance/run"; log="$run/server.log"
    metadata="$instance/metadata.json"; variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" 2>/dev/null | sed -n '1p')"
    load_profile "$variant_id" || { emit_error "UNSUPPORTED_VARIANT" "The instance has no trusted Termux Node profile."; return 3; }
    entrypoint="$repo/$profile_entrypoint"
    [ -f "$entrypoint" ] || { emit_error "INSTANCE_NOT_INSTALLED" "The configured Node server entry point is missing."; return 3; }
    load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 3; }
    port="$cfg_port"; protocol="$cfg_protocol"
    if [ "$cfg_bind_scope" = "LOCAL_NETWORK" ]; then bind_host="0.0.0.0"; else bind_host="127.0.0.1"; fi
    if [ "$protocol" = "HTTPS" ] && ! validate_tls "$instance_id"; then
        emit_error "TLS_INVALID" "HTTPS requires a matching readable certificate and private key."
        return 3
    fi

    if valid_process "$instance_id"; then
        pid="$(read_pid "$instance_id")"
        if http_healthy "$port" "$protocol" "$repo" "$profile_health_path" "$(tls_state_file "$instance_id")"; then state="RUNNING"; else state="UNHEALTHY"; fi
        request_watch "$instance_id" || printf '%s\n' 'The server watcher could not be started.' >> "$log"
        printf '{"ok":true,"operation":"start","instanceId":"%s","state":"%s","pid":%s,"port":%s,"protocol":"%s","alreadyRunning":true}\n' "$instance_id" "$state" "$pid" "$port" "$protocol"
        return 0
    fi

    if ! port_available "$bind_host" "$port"; then
        emit_error "PORT_IN_USE" "The configured port is already in use."
        return 4
    fi
    if [ "$profile_storage" = "sqlite" ] && ! apply_snapshot_config "$repo" "$cfg_snapshot_count" "$cfg_snapshot_mb" >> "$log" 2>&1; then
        emit_error "SNAPSHOT_CONFIG_FAILED" "Snapshot retention settings could not be applied."
        return 4
    fi
    if ! start_profile_storage "$instance" "$log"; then
        emit_error "STORAGE_START_FAILED" "The isolated profile storage service could not be started."
        return 4
    fi

    mkdir -p "$run"
    rm -f "$run/server.pid"
    if [ "$(running_count)" -eq 0 ]; then termux-wake-lock >/dev/null 2>&1 || true; fi
    (
        cd "$repo" || exit 1
        if [ "$profile_storage" = "postgres" ]; then
                PORT="$port" RISU_HOST="$bind_host" RISU_SAVE_PATH="$profile_save_path" DATABASE_URL="$profile_database_url" \
                DB_VENDOR=postgres RISU_STORAGE_TYPE=fs setsid node "$profile_entrypoint" >> "$log" 2>&1 < /dev/null &
        else
            PORT="$port" RISU_BIND_HOST="$bind_host" RISU_BODY_LIMIT_MB="$cfg_body_mb" \
                RISU_SAVE_INTERVAL_MS="$((cfg_save_seconds * 1000))" \
                POCKETRISU_BACKUP_INTERVAL_MS="$((cfg_snapshot_minutes * 60 * 1000))" \
                setsid node "$profile_entrypoint" >> "$log" 2>&1 < /dev/null &
        fi
        printf '%s\n' "$!" > "$run/server.pid"
    ) || {
        stop_profile_storage "$instance" || true
        release_wake_lock_if_idle
        emit_error "SERVER_START_FAILED" "The Node server could not be launched."
        return 4
    }

    attempts=0
    while [ "$attempts" -lt 30 ]; do
        if ! valid_process "$instance_id"; then
            rm -f "$run/server.pid"
            stop_profile_storage "$instance" || true
            release_wake_lock_if_idle
            emit_error "SERVER_START_FAILED" "The Node server exited before becoming healthy."
            return 4
        fi
        if http_healthy "$port" "$protocol" "$repo" "$profile_health_path" "$(tls_state_file "$instance_id")"; then
            pid="$(read_pid "$instance_id")"
            write_config_file "$(applied_config_file "$instance_id")" "$cfg_port" "$cfg_bind_scope" "$cfg_protocol" "$cfg_body_mb" "$cfg_save_seconds" "$cfg_snapshot_minutes" "$cfg_snapshot_count" "$cfg_snapshot_mb"
            rm -f "$instance/manager/last-apply-error"
            request_watch "$instance_id" || printf '%s\n' 'The server watcher could not be started.' >> "$log"
            printf '{"ok":true,"operation":"start","instanceId":"%s","state":"RUNNING","pid":%s,"port":%s,"protocol":"%s","alreadyRunning":false}\n' "$instance_id" "$pid" "$port" "$protocol"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    pid="$(read_pid "$instance_id")"
    if valid_process "$instance_id"; then kill -TERM "$pid" 2>/dev/null || true; fi
    attempts=0
    while valid_process "$instance_id" && [ "$attempts" -lt 10 ]; do attempts=$((attempts + 1)); sleep 1; done
    if valid_process "$instance_id"; then kill -KILL "$pid" 2>/dev/null || true; fi
    attempts=0
    while valid_process "$instance_id" && [ "$attempts" -lt 2 ]; do attempts=$((attempts + 1)); sleep 1; done
    if valid_process "$instance_id"; then
        printf '%s\n' 'Health check failed, and the unhealthy server could not be stopped.' > "$instance/manager/last-apply-error"
        printf '{"ok":false,"operation":"start","instanceId":"%s","state":"UNHEALTHY","pid":%s,"port":%s,"protocol":"%s","code":"HEALTH_CHECK_FAILED","message":"The server did not respond within 30 seconds and is still running."}\n' "$instance_id" "$pid" "$port" "$protocol"
        return 5
    fi
    rm -f "$run/server.pid"
    stop_profile_storage "$instance" || true
    release_wake_lock_if_idle
    printf '%s\n' 'Health check failed after applying the requested settings.' > "$instance/manager/last-apply-error"
    printf '{"ok":false,"operation":"start","instanceId":"%s","state":"UNHEALTHY","pid":%s,"port":%s,"protocol":"%s","code":"HEALTH_CHECK_FAILED","message":"The server did not respond within 30 seconds."}\n' "$instance_id" "$pid" "$port" "$protocol"
    return 5
}

command_status() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "status requires an instance ID."; return 2; }
    instance_id="$1"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    instance="$(instance_dir "$instance_id")"; repo="$instance/repo"
    load_config "$instance_id" || { emit_error "INVALID_CONFIG" "The stored server configuration is invalid."; return 3; }
    port="$cfg_port"; protocol="$cfg_protocol"
    metadata="$instance/metadata.json"
    [ -r "$metadata" ] && [ -d "$repo" ] || { printf '{"ok":true,"operation":"status","instanceId":"%s","state":"STOPPED","installed":false,"port":%s,"protocol":"%s"}\n' "$instance_id" "$port" "$protocol"; return 0; }
    variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" | sed -n '1p')"
    load_profile "$variant_id" || { emit_error "UNSUPPORTED_VARIANT" "The instance has no trusted profile."; return 3; }
    if [ -d "$repo/.git" ]; then commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || true)"; else commit="$(sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p' "$metadata" | sed -n '1p')"; fi
    if valid_process "$instance_id"; then
        pid="$(read_pid "$instance_id")"
        if http_healthy "$port" "$protocol" "$repo" "$profile_health_path" "$(tls_state_file "$instance_id")"; then state="RUNNING"; else state="UNHEALTHY"; fi
        request_watch "$instance_id" || true
        printf '{"ok":true,"operation":"status","instanceId":"%s","state":"%s","installed":true,"pid":%s,"port":%s,"protocol":"%s","commit":"%s"}\n' "$instance_id" "$state" "$pid" "$port" "$protocol" "$commit"
    else
        [ ! -f "$(watch_requested_file "$instance_id")" ] || ensure_watcher "$instance_id" || true
        rm -f "$instance/run/server.pid"
        printf '{"ok":true,"operation":"status","instanceId":"%s","state":"STOPPED","installed":true,"port":%s,"protocol":"%s","commit":"%s"}\n' "$instance_id" "$port" "$protocol" "$commit"
    fi
}

command_stop() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "stop requires an instance ID."; return 2; }
    instance_id="$1"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    instance="$(instance_dir "$instance_id")"; run="$instance/run"
    rm -f "$(watch_requested_file "$instance_id")"
    metadata="$instance/metadata.json"; variant_id="$(sed -n 's/.*"variantId":"\([a-z0-9-]*\)".*/\1/p' "$metadata" 2>/dev/null | sed -n '1p')"
    load_profile "$variant_id" || profile_storage="none"
    if ! valid_process "$instance_id"; then
        rm -f "$run/server.pid"
        stop_profile_storage "$instance" || true
        release_wake_lock_if_idle
        printf '{"ok":true,"operation":"stop","instanceId":"%s","state":"STOPPED","alreadyStopped":true}\n' "$instance_id"
        return 0
    fi
    pid="$(read_pid "$instance_id")"
    kill -TERM "$pid" 2>/dev/null || true
    attempts=0
    while valid_process "$instance_id" && [ "$attempts" -lt 10 ]; do attempts=$((attempts + 1)); sleep 1; done
    if valid_process "$instance_id"; then kill -KILL "$pid" 2>/dev/null || true; fi
    attempts=0
    while valid_process "$instance_id" && [ "$attempts" -lt 2 ]; do attempts=$((attempts + 1)); sleep 1; done
    if valid_process "$instance_id"; then
        emit_error "SERVER_STOP_FAILED" "The server process could not be stopped."
        return 5
    fi
    rm -f "$run/server.pid"
    stop_profile_storage "$instance" || true
    release_wake_lock_if_idle
    printf '{"ok":true,"operation":"stop","instanceId":"%s","state":"STOPPED","alreadyStopped":false}\n' "$instance_id"
}

command_restart() {
    [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "restart requires an instance ID."; return 2; }
    command_stop "$1" >/dev/null || return $?
    command_start "$1"
}

command_logs() {
    [ "$#" -eq 2 ] || { emit_error "INVALID_ARGUMENT" "logs requires an instance ID and line count."; return 2; }
    instance_id="$1"; max_lines="$2"
    valid_identifier "$instance_id" || { emit_error "INVALID_INSTANCE_ID" "The instance ID is invalid."; return 2; }
    case "$max_lines" in ''|*[!0-9]*) emit_error "INVALID_ARGUMENT" "The line count must be numeric."; return 2 ;; esac
    [ "$max_lines" -ge 1 ] && [ "$max_lines" -le 1000 ] || { emit_error "INVALID_ARGUMENT" "The line count must be between 1 and 1000."; return 2; }
    file="$(instance_dir "$instance_id")/run/server.log"
    [ -r "$file" ] || exit 0
    tail -n "$max_lines" "$file"
}

command_operation_log() {
    [ "$#" -eq 2 ] || { emit_error "INVALID_ARGUMENT" "operation-log requires an operation ID and line count."; return 2; }
    operation_id="$1"; max_lines="$2"
    valid_operation_id "$operation_id" || { emit_error "INVALID_OPERATION_ID" "The operation ID is invalid."; return 2; }
    case "$max_lines" in ''|*[!0-9]*) emit_error "INVALID_ARGUMENT" "The line count must be numeric."; return 2 ;; esac
    [ "$max_lines" -ge 1 ] && [ "$max_lines" -le 2000 ] || { emit_error "INVALID_ARGUMENT" "The line count must be between 1 and 2000."; return 2; }
    file="$LOGS/$operation_id.log"
    if [ -L "$file" ] || { [ -e "$file" ] && [ ! -f "$file" ]; }; then
        emit_error "UNSAFE_LOG_PATH" "The operation log path is not a regular file."
        return 3
    fi
    [ -r "$file" ] || exit 0
    tail -n "$max_lines" "$file"
}

case "${1:-}" in
    version) shift; command_version "$@" ;;
    doctor) shift; command_doctor "$@" ;;
    dispatch) shift; command_dispatch "$@" ;;
    operation) shift; command_operation "$@" ;;
    recovery-estimate) shift; command_recovery_estimate "$@" ;;
    recovery-inspect) shift; command_recovery_inspect "$@" ;;
    recovery-pin) shift; command_recovery_pin "$@" ;;
    recovery-delete) shift; command_recovery_delete "$@" ;;
    recovery-cleanup) shift; command_recovery_cleanup "$@" ;;
    backup-cleanup) shift; command_backup_cleanup "$@" ;;
    config-get)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "config-get requires an instance ID."; exit 2; }
        with_instance_lock "$1" config-get command_config_get "$@"
        ;;
    config-set)
        shift
        [ "$#" -ge 1 ] || { emit_error "INVALID_ARGUMENT" "config-set requires an instance ID."; exit 2; }
        with_instance_lock "$1" config-set command_config_set "$@"
        ;;
    config-validate)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "config-validate requires an instance ID."; exit 2; }
        with_instance_lock "$1" config-validate command_config_validate "$@"
        ;;
    acme-install) shift; command_acme_install "$@" ;;
    acme-preflight) shift; command_acme_preflight "$@" ;;
    acme-issue)
        shift
        [ "$#" -ge 1 ] || { emit_error "INVALID_ARGUMENT" "acme-issue requires an instance ID."; exit 2; }
        with_instance_lock "$1" acme-issue command_acme_issue "$@"
        ;;
    acme-renew-check)
        shift
        [ "$#" -ge 1 ] || { emit_error "INVALID_ARGUMENT" "acme-renew-check requires an instance ID."; exit 2; }
        with_instance_lock "$1" acme-renew-check command_acme_renew_check "$@"
        ;;
    acme-certificate-status) shift; command_acme_certificate_status "$@" ;;
    acme-disable)
        shift
        [ "$#" -ge 1 ] || { emit_error "INVALID_ARGUMENT" "acme-disable requires an instance ID."; exit 2; }
        with_instance_lock "$1" acme-disable command_acme_disable "$@"
        ;;
    clone-publish) shift; command_clone_publish "$@" ;;
    clone-abort) shift; command_clone_abort "$@" ;;
    _install)
        shift
        { [ "$#" -eq 4 ] || [ "$#" -eq 7 ]; } || exit 2
        valid_operation_id "$1" && valid_identifier "$2" && valid_identifier "$3" && valid_commit "$4" || exit 2
        with_instance_lock "$2" "$1" do_install "$@"
        ;;
    _backup)
        shift
        [ "$#" -eq 2 ] || exit 2
        valid_operation_id "$1" && valid_identifier "$2" || exit 2
        with_instance_lock "$2" "$1" do_backup "$@"
        ;;
    _clone)
        shift
        [ "$#" -eq 4 ] || exit 2
        valid_operation_id "$1" && valid_identifier "$2" && valid_identifier "$3" && valid_uint "$4" || exit 2
        do_clone "$@"
        ;;
    _update)
        shift
        [ "$#" -eq 6 ] || exit 2
        valid_operation_id "$1" && valid_identifier "$2" && valid_identifier "$3" && valid_commit "$4" && valid_recovery_id "$5" && valid_uint "$6" || exit 2
        with_instance_lock "$2" "$1" do_update "$@"
        ;;
    _rollback)
        shift
        [ "$#" -eq 5 ] || exit 2
        valid_operation_id "$1" && valid_identifier "$2" && valid_recovery_id "$3" && valid_recovery_id "$4" && valid_uint "$5" || exit 2
        with_instance_lock "$2" "$1" do_rollback "$@"
        ;;
    _watch)
        shift
        [ "$#" -eq 1 ] && valid_identifier "$1" || exit 2
        command_watch "$1"
        ;;
    _watch-recover)
        shift
        [ "$#" -eq 1 ] && valid_identifier "$1" || exit 2
        with_instance_lock "$1" watch-recover command_watch_recover "$@"
        ;;
    start)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "start requires an instance ID."; exit 2; }
        with_instance_lock "$1" start command_start "$@"
        ;;
    status)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "status requires an instance ID."; exit 2; }
        with_instance_lock "$1" status command_status "$@"
        ;;
    stop)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "stop requires an instance ID."; exit 2; }
        with_instance_lock "$1" stop command_stop "$@"
        ;;
    restart)
        shift
        [ "$#" -eq 1 ] || { emit_error "INVALID_ARGUMENT" "restart requires an instance ID."; exit 2; }
        with_instance_lock "$1" restart command_restart "$@"
        ;;
    logs) shift; command_logs "$@" ;;
    operation-log) shift; command_operation_log "$@" ;;
    *) emit_error "UNKNOWN_COMMAND" "Unknown helper command."; exit 2 ;;
esac
RISU_MANAGER_HELPER

chmod 700 "$TMP"
mv -f "$TMP" "$TARGET"
printf '{"ok":true,"operation":"bootstrap","helperVersion":26}\n'
