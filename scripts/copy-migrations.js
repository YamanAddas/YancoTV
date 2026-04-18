// Copy migration SQL files from src/main/services/migrations into the
// compiled output so the packaged app can find them via __dirname.
// tsc only handles .ts files, so without this the migration runner finds
// nothing on a clean install and the app boots against an empty DB.

const fs = require('fs');
const path = require('path');

const srcDir = path.join(__dirname, '..', 'src', 'main', 'services', 'migrations');
const destDir = path.join(__dirname, '..', 'dist', 'main', 'main', 'services', 'migrations');

if (!fs.existsSync(srcDir)) {
  console.error(`copy-migrations: source dir missing: ${srcDir}`);
  process.exit(1);
}

fs.mkdirSync(destDir, { recursive: true });

let count = 0;
for (const name of fs.readdirSync(srcDir)) {
  if (!name.endsWith('.sql')) continue;
  fs.copyFileSync(path.join(srcDir, name), path.join(destDir, name));
  count += 1;
}

console.log(`copy-migrations: copied ${count} .sql file(s) to ${path.relative(path.join(__dirname, '..'), destDir)}`);
