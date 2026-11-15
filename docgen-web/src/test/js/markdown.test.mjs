import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDom } from './fake-dom.mjs';

let md;
before(async () => {
  installFakeDom();
  md = await import('../../main/resources/static/js/markdown.js');
});

test('parses the Markdown structures that generated documents use', () => {
  const ast = md.parseMarkdown([
    '# Título', '', 'Parágrafo com **negrito**, *ênfase*, `código` e [link](https://example.com).', '',
    '## Lista', '- um', '- dois', '  - aninhado', '1. primeiro', '2. segundo', '',
    '```java', 'class App {}', '```', '', '> citação', '', '| A | B |', '| --- | ---: |', '| 1 | 2 |', '', '---',
  ].join('\n'));
  assert.deepEqual(ast.map((block) => block.type),
    ['heading', 'paragraph', 'heading', 'list', 'list', 'code', 'blockquote', 'table', 'hr']);
  assert.equal(ast[3].items[1].sublist.items[0].children[0].value, 'aninhado');
  assert.equal(ast[5].language, 'java');
  assert.equal(ast[7].alignments[1], 'right');
  const inline = ast[1].children.map((node) => node.type);
  assert.ok(inline.includes('strong') && inline.includes('em') && inline.includes('code') && inline.includes('link'));
});

test('raw HTML is rendered as literal text', () => {
  const { nodes } = md.renderMarkdown('# Doc\n\n<script>alert(1)</script> <img src=x onerror=alert(1)>');
  const paragraph = nodes[1];
  assert.equal(paragraph.tagName, 'P');
  assert.equal(paragraph.textContent, '<script>alert(1)</script> <img src=x onerror=alert(1)>');
  assert.ok(paragraph.descendants().every((node) => node.nodeType === 3));
});

test('links are sanitized and external ones are isolated', () => {
  const { nodes } = md.renderMarkdown('[a](javascript:alert(1)) [b](https://example.com) [c](#secao)');
  const links = nodes[0].descendants().filter((node) => node.tagName === 'A');
  assert.equal(links[0].getAttribute('href'), '#');
  assert.equal(links[1].getAttribute('href'), 'https://example.com');
  assert.equal(links[1].getAttribute('rel'), 'noopener noreferrer nofollow');
  assert.equal(links[2].getAttribute('href'), '#secao');
});

test('images are never loaded', () => {
  const { nodes } = md.renderMarkdown('![logo](https://tracker.example/pixel.png)');
  assert.equal(nodes[0].descendants().filter((node) => node.tagName === 'IMG').length, 0);
  assert.match(nodes[0].textContent, /\[imagem: logo\]/);
});

test('headings produce a unique outline', () => {
  const { outline } = md.renderMarkdown('# Visão\n## Arquitetura\n## Arquitetura');
  assert.deepEqual(outline.map((item) => item.id), ['md-visao', 'md-arquitetura', 'md-arquitetura-1']);
});

test('unterminated constructs degrade to text', () => {
  const ast = md.parseMarkdown('**aberto e `sem fim e [link sem fim(');
  assert.equal(ast[0].type, 'paragraph');
  assert.equal(md.plainText(ast[0].children), '**aberto e `sem fim e [link sem fim(');
});
