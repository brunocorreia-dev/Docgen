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

test('the pages have no inline scripts, styles or handlers and load only same-origin assets', () => {
  const pages = files(STATIC, '.html');
  assert.deepEqual(pages.map((page) => page.slice(STATIC.length + 1)).sort(), ['index.html', 'launch.html']);
  for (const page of pages) {
    const html = readFileSync(page, 'utf8');
    assert.ok(!/<script(?![^>]*\bsrc=)[^>]*>/i.test(html), `${page}: inline <script>`);
    assert.ok(!/<style/i.test(html), `${page}: inline <style>`);
    assert.ok(!/\son[a-z]+\s*=/i.test(html), `${page}: inline event handler`);
    assert.ok(!/\sstyle\s*=/i.test(html), `${page}: style attribute`);
    assert.ok(!/(src|href)=["']https?:/i.test(html), `${page}: third-party asset`);
    assert.ok(/<meta name="referrer" content="no-referrer">/.test(html), `${page}: referrer policy`);
  }
});

test('nothing stores the session outside sessionStorage or reads cookies', () => {
  for (const file of files(join(STATIC, 'js'), '.js')) {
    const source = readFileSync(file, 'utf8');
    assert.ok(!/document\.cookie/.test(source), `${file} touches cookies`);
    if (/localStorage/.test(source)) {
      assert.ok(!/session|token/i.test(source.match(/localStorage[^;]*/g).join(' ')), `${file} keeps a credential in localStorage`);
    }
  }
});

test('stylesheets do not import remote resources', () => {
  for (const file of files(join(STATIC, 'css'), '.css')) {
    const css = readFileSync(file, 'utf8');
    assert.ok(!/@import|url\(\s*['"]?https?:/i.test(css), `${file} loads a remote resource`);
  }
});

// ADR 0003: the workspace talks only to its own origin. A URL-shaped string is
// allowed only when it is an identifier that is never requested.
const NEVER_FETCHED = new Set(['http://www.w3.org/2000/svg']);

test('sources contain no absolute URL other than never-fetched identifiers', () => {
  const sources = [...files(join(STATIC, 'js'), '.js'), ...files(STATIC, '.html'), ...files(STATIC, '.svg'), ...files(STATIC, '.css')];
  for (const file of sources) {
    for (const match of readFileSync(file, 'utf8').matchAll(/https?:\/\/[^\s"'`<>)\]]*/gi)) {
      assert.ok(NEVER_FETCHED.has(match[0]), `${file} references ${match[0]}`);
    }
  }
});

test('the API client only requests same-origin /api/ paths', () => {
  const client = readFileSync(join(STATIC, 'js', 'api.js'), 'utf8');
  const paths = [...client.matchAll(/request\(\s*'[A-Z]+',\s*[`']([^`']+)[`']/g), ...client.matchAll(/post\(\s*[`']([^`']+)[`']/g)]
    .map((match) => match[1]);
  assert.ok(paths.length > 10, 'the client paths were found');
  for (const path of paths) assert.ok(path.startsWith('/api/v1/'), `${path} is not a same-origin API path`);
});
