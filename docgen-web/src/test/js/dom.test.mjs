import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDom } from './fake-dom.mjs';

let dom;
before(async () => {
  installFakeDom();
  dom = await import('../../main/resources/static/js/dom.js');
});

test('untrusted strings become text nodes, never markup', () => {
  const hostile = '<img src=x onerror=alert(1)><script>alert(2)</script>';
  const element = dom.h('p', null, hostile);
  assert.equal(element.childNodes.length, 1);
  assert.equal(element.childNodes[0].nodeType, 3);
  assert.equal(element.textContent, hostile);
});

test('inline event handler attributes are refused', () => {
  assert.throws(() => dom.h('div', { onclick: 'alert(1)' }), /not allowed/);
  assert.throws(() => dom.h('div', { ONLOAD: 'x' }), /not allowed/);
});

test('URL attributes pass the allowlist', () => {
  assert.equal(dom.h('a', { href: 'javascript:alert(1)' }).getAttribute('href'), '#');
  assert.equal(dom.h('a', { href: 'https://example.com' }).getAttribute('href'), 'https://example.com');
  assert.equal(dom.s('image', { 'xlink:href': 'data:image/svg+xml,<svg onload=x>' }).getAttribute('xlink:href'), '#');
});

test('styles go through CSSOM and listeners through addEventListener', () => {
  let clicked = false;
  const element = dom.h('button', { style: { '--delay': '40ms' }, on: { click: () => { clicked = true; } } }, 'x');
  assert.equal(element.styles.get('--delay'), '40ms');
  assert.equal(element.getAttribute('style'), null);
  element.dispatch('click');
  assert.ok(clicked);
});

test('svg helper uses the SVG namespace and flattens children', () => {
  const svg = dom.s('svg', null, [dom.s('g'), [dom.s('path', { d: 'M0 0' })]], null, false);
  assert.equal(svg.namespaceURI, 'http://www.w3.org/2000/svg');
  assert.equal(svg.childNodes.length, 2);
});

test('replace and append drop null, undefined and boolean children', () => {
  const element = dom.h('div', null, 'old');
  dom.replace(element, null, 'a', undefined, false, true, ['b', null]);
  assert.equal(element.textContent, 'ab');
});

test('append accepts a single node as well as nested arrays', () => {
  const parent = dom.h('div');
  dom.append(parent, dom.h('span', null, 'one'));
  dom.append(parent, [['two', null], dom.h('b', null, 'three')]);
  assert.equal(parent.childNodes.length, 3);
  assert.equal(parent.textContent, 'onetwothree');
});
