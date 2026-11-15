// Cross-check with the web editor's own validator: what Studio wrote must be exactly what the web
// editor makes of the original package. Usage: node web_check.mjs <uiDocument.js> <originals> <written>
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const [validator, originals, written] = process.argv.slice(2);
const {validateUi} = await import(pathToFileURL(validator).href);
let n = 0;
for (const name of fs.readdirSync(originals).filter(f => f.endsWith('.ui.json'))) {
  const web = validateUi(JSON.parse(fs.readFileSync(path.join(originals, name), 'utf8')));
  const studio = JSON.parse(fs.readFileSync(path.join(written, name), 'utf8'));
  assert.deepStrictEqual(studio, JSON.parse(JSON.stringify(web)), name);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(validateUi(studio))), studio, name + ' re-read');
  n++;
}
// Format 3 (graphic components): a package Studio composed from the HVAC library must read the same.
const composed = path.join(written, 'composed.ui.json');
let v3 = '';
if (fs.existsSync(composed)) {
  const studio = JSON.parse(fs.readFileSync(composed, 'utf8'));
  assert.deepStrictEqual(JSON.parse(JSON.stringify(validateUi(studio))), studio, 'composed.ui.json');
  assert.equal(studio.version, 3);
  v3 = ', a version 3 package with ' + studio.components.length + ' graphic components too';
}
console.log('PASS screens against the web editor: ' + n + ' packages identical to its own reading' + v3);
