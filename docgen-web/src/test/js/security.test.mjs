// Static guard rails for the workspace sources: no HTML-parsing sinks, no
// dynamic code, no inline script/style/handlers, no third-party origins.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const STATIC = join(dirname(fileURLToPath(import.meta.url)), '../../main/resources/static');

function files(directory, extension) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) return files(path, extension);
    return path.endsWith(extension) ? [path] : [];
  });
}

test('JavaScript never uses HTML-parsing sinks or dynamic code execution', () => {
  const forbidden = [/\.innerHTML\b/, /\.outerHTML\b/, /insertAdjacentHTML/, /document\.write/, /\beval\s*\(/, /new\s+Function\s*\(/,
    /setTimeout\s*\(\s*['"`]/, /setInterval\s*\(\s*['"`]/, /\.setAttribute\(\s*['"]on/i, /\.setAttribute\(\s*['"]style['"]/, /createContextualFragment/, /DOMParser/];
  for (const file of files(join(STATIC, 'js'), '.js')) {
    const source = readFileSync(file, 'utf8');
    for (const pattern of forbidden) assert.ok(!pattern.test(source), `${file} matches ${pattern}`);
  }
});

test('views use the null-safe replace helper instead of native replaceChildren', () => {
  // Native replaceChildren(null) inserts the text "null"; dom.replace() skips empty children.
  for (const file of files(join(STATIC, 'js'), '.js')) {
    assert.ok(!/\.replaceChildren\(/.test(readFileSync(file, 'utf8')), `${file} calls replaceChildren`);
  }
});

test('the page has no inline scripts, styles or handlers and loads only same-origin assets', () => {
  const html = readFileSync(join(STATIC, 'index.html'), 'utf8');
  assert.ok(!/<script(?![^>]*\bsrc=)[^>]*>/i.test(html), 'inline <script>');
  assert.ok(!/<style/i.test(html), 'inline <style>');
  assert.ok(!/\son[a-z]+\s*=/i.test(html), 'inline event handler');
  assert.ok(!/\sstyle\s*=/i.test(html), 'style attribute');
  assert.ok(!/(src|href)=["']https?:/i.test(html), 'third-party asset');
});

test('stylesheets do not import remote resources', () => {
  for (const file of files(join(STATIC, 'css'), '.css')) {
    const css = readFileSync(file, 'utf8');
    assert.ok(!/@import|url\(\s*['"]?https?:/i.test(css), `${file} loads a remote resource`);
  }
});
