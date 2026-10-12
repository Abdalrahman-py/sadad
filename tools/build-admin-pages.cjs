const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const repository = path.resolve(__dirname, '..');
const source = path.join(repository, 'server/public');
const output = path.join(repository, '_site');

// Publish only the dashboard assets, never repository data or local credentials.
const plain = ['admin.html', 'admin-tajawal.woff2', 'logo-light.jpeg', 'logo-dark.png'];
// These change on every deploy, and GitHub Pages caches by filename. Stamp the content hash
// into the name and rewrite the references, so a teammate never runs a stale dashboard after a
// deploy.
const hashed = ['admin.css', 'admin.js'];

fs.rmSync(output, { recursive: true, force: true });
fs.mkdirSync(output, { recursive: true });

const digest = (file) => crypto.createHash('sha256').update(fs.readFileSync(path.join(source, file))).digest('hex').slice(0, 10);
const names = {};
for (const asset of hashed) {
  const extension = path.extname(asset);
  names[asset] = `${path.basename(asset, extension)}.${digest(asset)}${extension}`;
  fs.copyFileSync(path.join(source, asset), path.join(output, names[asset]));
}

let html = fs.readFileSync(path.join(source, 'admin.html'), 'utf8');
for (const [from, to] of Object.entries(names)) html = html.split(from).join(to);
const missed = Object.keys(names).filter((from) => html.includes(from));
if (missed.length) throw new Error(`a hashed asset reference was not rewritten in admin.html: ${missed.join(', ')}`);

for (const asset of plain) fs.copyFileSync(path.join(source, asset), path.join(output, asset));
fs.writeFileSync(path.join(output, 'admin.html'), html);
fs.writeFileSync(path.join(output, 'index.html'), html);
fs.writeFileSync(path.join(output, '.nojekyll'), '');

console.log('Built the unified admin dashboard in _site/:');
for (const asset of plain) console.log(`  ${asset}`);
for (const [from, to] of Object.entries(names)) console.log(`  ${from} -> ${to}`);
