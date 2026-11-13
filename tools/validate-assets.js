#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..');
const htmlPath = path.join(root, 'app', 'src', 'main', 'assets', 'index.html');

if (!fs.existsSync(htmlPath)) {
    console.error(`File tidak ditemukan: ${htmlPath}`);
    process.exit(1);
}

const html = fs.readFileSync(htmlPath, 'utf8');
const errors = [];

function lineOf(index) {
    return html.slice(0, index).split('\n').length;
}

// Validasi atribut duplikat dan ID duplikat di setiap tag HTML.
const tagRe = /<[^>]+>/g;
const ids = new Map();
let tagMatch;
while ((tagMatch = tagRe.exec(html))) {
    const tag = tagMatch[0];
    const tagStart = tagMatch.index;
    if (/^<\//.test(tag) || /^<!/.test(tag) || /^<\?/.test(tag)) continue;

    const attrRe = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*"([^"]*)"/g;
    const seen = new Set();
    let attrMatch;
    while ((attrMatch = attrRe.exec(tag))) {
        const name = attrMatch[1].toLowerCase();
        if (seen.has(name)) {
            errors.push(`Atribut duplikat "${attrMatch[1]}" pada baris ${lineOf(tagStart)}`);
        }
        seen.add(name);

        if (name === 'id') {
            const value = attrMatch[2];
            if (ids.has(value)) {
                errors.push(`ID duplikat "${value}" pada baris ${lineOf(tagStart)} (pertama di baris ${ids.get(value)})`);
            } else {
                ids.set(value, lineOf(tagStart));
            }
        }
    }
}

// Validasi sintaks semua script inline (script dengan src dilewati).
const scriptRe = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
let scriptMatch;
let scriptIndex = 0;
while ((scriptMatch = scriptRe.exec(html))) {
    if (/\bsrc\s*=/i.test(scriptMatch[1])) continue;
    const code = scriptMatch[2];
    if (!code.trim()) continue;
    scriptIndex += 1;
    try {
        new vm.Script(code, { filename: `index-inline-${scriptIndex}.js` });
    } catch (error) {
        errors.push(`Syntax error pada script inline #${scriptIndex} (baris ${lineOf(scriptMatch.index)}): ${error.message}`);
    }
}

if (errors.length) {
    console.error(`Validasi aset PangDash gagal (${errors.length} masalah):`);
    errors.forEach((error) => console.error(`- ${error}`));
    process.exit(1);
}

console.log(`Validasi aset PangDash OK: ${ids.size} ID unik, ${scriptIndex} script inline, tanpa atribut duplikat.`);
