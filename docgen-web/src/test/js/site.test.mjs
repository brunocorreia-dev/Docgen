// The Vercel deployment serves only the static site, never the app frontend.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../../..');

test('Vercel publishes the site directory with a strict policy', () => {
  const config = JSON.parse(readFileSync(join(ROOT, 'vercel.json'), 'utf8'));
  assert.equal(config.outputDirectory, 'site');
  const policy = config.headers[0].headers.find((header) => header.key === 'Content-Security-Policy').value;
  assert.match(policy, /default-src 'none'/);
  assert.doesNotMatch(policy, /script-src/);
});

test('the site page runs no script and loads only its own assets', () => {
  const html = readFileSync(join(ROOT, 'site', 'index.html'), 'utf8');
  assert.ok(!/<script/i.test(html), 'no script');
  assert.ok(!/\son[a-z]+\s*=/i.test(html), 'no inline handler');
  for (const match of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
    const target = match[1];
    assert.ok(target.startsWith('/') || target === 'https://github.com/brunocorreia-dev/Docgen', `unexpected reference ${target}`);
  }
  assert.match(html, /DocGen Desktop/);
  assert.match(html, /Em desenvolvimento/);
});
