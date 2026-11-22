const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const native = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/pangdash/launcher/MainActivity.kt'), 'utf8');
const elements = new Map();
const writes = [];
let canWrite = true;
const context = vm.createContext({
    Date,
    document: { getElementById(id) {
        if (!elements.has(id)) elements.set(id, { value: '90', style: {}, innerText: '' });
        return elements.get(id);
    } },
    window: { AndroidBridge: {
        canWriteSystemSettings: () => canWrite,
        setSystemBrightness: value => writes.push(value)
    } }
});
vm.runInContext(html.slice(html.indexOf('    let isUserDraggingBrightness'), html.indexOf('    let isMuted')), context);
for (const input of [0, 5, -10, 20, 50, 100, 999, 'invalid']) {
    context.input = input;
    vm.runInContext('setBrightness(input)', context);
    const expected = Number.isNaN(parseInt(input)) ? 90 : Math.max(20, Math.min(100, parseInt(input)));
    assert.equal(writes[writes.length - 1], expected);
    assert.equal(+elements.get('brightness-slider').value, expected);
    assert.equal(+elements.get('brightness-overlay').style.opacity, 0, 'hardware brightness must not be dimmed twice');
}
for (let i = 0; i < 20; i++) vm.runInContext('adjustBrightness(-10)', context);
assert.equal(writes[writes.length - 1], 20);
canWrite = false;
vm.runInContext('setBrightness(0)', context);
assert(+elements.get('brightness-overlay').style.opacity <= 0.36 + 1e-9);
assert(/id="brightness-slider"[^>]*min="20"/.test(html));
assert(native.includes('private val minBrightnessPercent = 20'));
assert(native.includes('percent.coerceIn(minBrightnessPercent, 100)'));
assert(native.includes('.coerceIn(51, 255)'));
assert(native.includes('.coerceIn(minBrightnessPercent / 100f, 1.0f)'));
console.log('Brightness OK: minimum 20%, repeated minus, invalid input, no hardware double dimming, bounded software overlay, native guards.');
