'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const installer = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'termux-helper', 'install.sh');
const source = fs.readFileSync(installer, 'utf8');
const exporterMatch = source.match(/<<'RISU_BACKUP_EXPORTER'\r?\n([\s\S]*?)\r?\nRISU_BACKUP_EXPORTER/);
assert(exporterMatch, 'embedded backup exporter was not found');
const exporter = exporterMatch[1];
const queryMatch = exporter.match(/const keysFor = db\.prepare\(([^\n]+)\);/);
assert(queryMatch, 'keysFor query was not found in the embedded exporter');

const query = Function(`"use strict"; return (${queryMatch[1]});`)();
const tempRoot = fs.realpathSync(os.tmpdir());
const sandbox = fs.mkdtempSync(path.join(tempRoot, 'risu-helper-sql-'));
try {
  const dbPath = path.join(sandbox, 'risuai.db');
  const db = new DatabaseSync(dbPath);
  db.exec('CREATE TABLE kv (key TEXT PRIMARY KEY, value BLOB)');
  const insert = db.prepare('INSERT INTO kv(key, value) VALUES (?, ?)');
  insert.run('plugin-storage/alpha', Buffer.from('one'));
  insert.run('plugin-storage-two', Buffer.from('two'));
  insert.run('assets/icon.png', Buffer.from('three'));

  const keysFor = db.prepare(query);
  assert.deepEqual(keysFor.all('plugin-storage/%').map(row => row.key), ['plugin-storage/alpha']);
  assert.deepEqual(keysFor.all('assets/%').map(row => row.key), ['assets/icon.png']);
  db.close();
} finally {
  const resolved = fs.realpathSync(sandbox);
  assert.equal(path.dirname(resolved), tempRoot, 'cleanup must stay inside the test temp root');
  assert(path.basename(resolved).startsWith('risu-helper-sql-'));
  fs.rmSync(sandbox, { recursive: true, force: true });
}

console.log('Termux helper exporter SQLite query: PASS');
