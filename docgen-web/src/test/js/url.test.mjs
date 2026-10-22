import { test } from 'node:test';
import assert from 'node:assert/strict';
import { safeUrl } from '../../main/resources/static/js/url.js';

test('allows http, https, mailto, anchors and relative references', () => {
  for (const url of ['https://example.com/a?b=1', 'http://localhost:8080', 'mailto:dev@example.com', '#install', '/docs/x.md', './a', '../b', 'docs/c.md']) {
    assert.equal(safeUrl(url), url);
  }
});

test('neutralizes executable and data schemes, including obfuscated ones', () => {
  for (const url of ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'java\tscript:alert(1)', ' javascript:alert(1)',
    'java\u0000script:alert(1)', 'data:text/html,<script>', 'vbscript:x', 'file:///etc/passwd', '//evil.example/x', '\u200bjavascript:x']) {
    assert.equal(safeUrl(url), '#', url);
  }
});

test('blob URLs are only allowed when explicitly requested', () => {
  assert.equal(safeUrl('blob:http://127.0.0.1/123'), '#');
  assert.equal(safeUrl('blob:http://127.0.0.1/123', { allowBlob: true }), 'blob:http://127.0.0.1/123');
  assert.equal(safeUrl(null), '#');
});
