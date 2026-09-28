'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const installer = path.resolve(process.argv[2] || 'app/src/main/assets/termux-helper/install.sh');
const source = fs.readFileSync(installer, 'utf8');

function inlineScript(functionName) {
  const parenthesizedStart = source.indexOf(`${functionName}() (`);
  const bracedStart = source.indexOf(`${functionName}() {`);
  const functionStart = parenthesizedStart === -1 ? bracedStart : parenthesizedStart;
  assert.notEqual(functionStart, -1, `missing ${functionName}`);
  const scriptStartMarker = "node -e '";
  const scriptStart = source.indexOf(scriptStartMarker, functionStart);
  assert.notEqual(scriptStart, -1, `missing node script in ${functionName}`);
  const contentStart = scriptStart + scriptStartMarker.length;
  const contentEnd = source.indexOf("' \"$", contentStart);
  assert.notEqual(contentEnd, -1, `unterminated node script in ${functionName}`);
  return source.slice(contentStart, contentEnd);
}

function run(script, args, expectedStatus = 0) {
  const result = spawnSync(process.execPath, ['-e', script, ...args], { encoding: 'utf8' });
  assert.equal(
    result.status,
    expectedStatus,
    `node helper status ${result.status}; expected ${expectedStatus}\nstdout: ${result.stdout}\nstderr: ${result.stderr}`,
  );
  return result.stdout;
}

const manifestScript = inlineScript('tree_manifest_hash');
const independentScript = inlineScript('verify_independent_regular_files');
const symlinkScript = inlineScript('sanitize_clone_symlinks');

assert.match(manifestScript, /readSync/);
assert.doesNotMatch(manifestScript, /readFileSync/);
assert.match(manifestScript, /Buffer\.allocUnsafe\(1024\*1024\)/);
assert.match(symlinkScript, /f\.symlinkSync\(p\.relative\(p\.dirname\(q\),mapped\)\|\|"\."/);

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'risu-clone-node-'));
try {
  const sourceRoot = path.join(temp, 'source');
  const cloneRoot = path.join(temp, 'clone');
  const finalRoot = path.join(temp, 'published');
  fs.mkdirSync(path.join(sourceRoot, 'nested'), { recursive: true });
  const large = Buffer.alloc(3 * 1024 * 1024 + 17, 0x5a);
  fs.writeFileSync(path.join(sourceRoot, 'nested', 'large.bin'), large);
  fs.writeFileSync(path.join(sourceRoot, 'mutable.txt'), 'source-data');
  fs.cpSync(sourceRoot, cloneRoot, { recursive: true, dereference: false });

  const sourceHash = run(manifestScript, [sourceRoot]).trim();
  const cloneHash = run(manifestScript, [cloneRoot]).trim();
  assert.match(sourceHash, /^[0-9a-f]{64}$/);
  assert.equal(cloneHash, sourceHash);
  const cloneLarge = path.join(cloneRoot, 'nested', 'large.bin');
  const fd = fs.openSync(cloneLarge, 'r+');
  try { fs.writeSync(fd, Buffer.from([0x41]), 0, 1, 2 * 1024 * 1024); } finally { fs.closeSync(fd); }
  assert.notEqual(run(manifestScript, [cloneRoot]).trim(), sourceHash);

  run(independentScript, [sourceRoot, cloneRoot]);
  fs.rmSync(path.join(cloneRoot, 'mutable.txt'));
  fs.linkSync(path.join(sourceRoot, 'mutable.txt'), path.join(cloneRoot, 'mutable.txt'));
  const shared = spawnSync(process.execPath, ['-e', independentScript, sourceRoot, cloneRoot], { encoding: 'utf8' });
  assert.notEqual(shared.status, 0, 'shared mutable inode was accepted');

  const symlinkSource = path.join(temp, 'symlink-source');
  const symlinkClone = path.join(temp, 'symlink-clone');
  fs.mkdirSync(path.join(symlinkSource, 'data'), { recursive: true });
  fs.writeFileSync(path.join(symlinkSource, 'data', 'inside.txt'), 'inside');
  fs.mkdirSync(symlinkClone, { recursive: true });
  fs.cpSync(path.join(symlinkSource, 'data'), path.join(symlinkClone, 'data'), { recursive: true });
  fs.symlinkSync(path.join(symlinkClone, 'data'), path.join(symlinkClone, 'internal-link'), 'junction');
  run(symlinkScript, [symlinkSource, symlinkClone, finalRoot]);
  assert.equal(
    fs.realpathSync(path.join(symlinkClone, 'internal-link')),
    fs.realpathSync(path.join(symlinkClone, 'data')),
  );

  const outside = path.join(temp, 'outside');
  fs.mkdirSync(outside);
  fs.writeFileSync(path.join(outside, 'outside.txt'), 'outside');
  fs.symlinkSync(outside, path.join(symlinkClone, 'external-link'), 'junction');
  const escaping = spawnSync(
    process.execPath,
    ['-e', symlinkScript, symlinkSource, symlinkClone, finalRoot],
    { encoding: 'utf8' },
  );
  assert.notEqual(escaping.status, 0, 'external symlink was accepted');

  console.log('Termux clone Node filesystem primitives: PASS');
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
