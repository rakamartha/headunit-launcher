const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const source = html.slice(html.indexOf('    let mapInstance ='), html.indexOf('    function toggleMapTheme('));
let layers = 0;
let redraws = 0;
let retries = [];
let tileError;
const map = { setView() { return this; }, invalidateSize() {} };
const layer = {
    options: {},
    on(name, handler) { if (name === 'tileerror') tileError = handler; return this; },
    addTo() { return this; },
    redraw() { redraws++; }
};
const context = vm.createContext({
    console,
    navigator: { onLine: true },
    setTimeout(handler) { retries.push(handler); return retries.length; },
    L: {
        map: () => map,
        tileLayer(_url, options) { layers++; layer.options = options; return layer; },
        polyline: () => ({ addTo() { return this; } }),
        divIcon: options => options,
        marker: () => ({ addTo() { return this; } })
    }
});
vm.runInContext(source + '\ninitLiveMap();', context);
assert.equal(layers, 1);
const tile = { dataset: {} };
tileError({ tile, coords: { z: 16, x: 1, y: 2 } });
assert.equal(tile.src, 'https://tile.openstreetmap.org/16/1/2.png');
assert.equal(layers, 1, 'failed tile must not create an overlay layer');
tileError({ tile, coords: { z: 16, x: 1, y: 2 } });
assert.equal(retries.length, 1, 'tile failures share a recovery timer');
for (let i = 0; i < 3; i++) {
    retries[i]();
    tileError({ tile, coords: { z: 16, x: 1, y: 2 } });
}
assert.equal(redraws, 3);
assert.equal(retries.length, 3, 'retry loop bounded');
const zoom20Tile = { dataset: {} };
tileError({ tile: zoom20Tile, coords: { z: 20, x: 1, y: 2 } });
assert.equal(zoom20Tile.src, undefined, 'do not ask OSM for unsupported zoom');
context.navigator.onLine = false;
vm.runInContext('recoverLiveMap()', context);
assert.equal(redraws, 3, 'do not redraw while offline');
console.log('Map recovery OK: per-tile fallback, no overlay, bounded retries, zoom limit, offline.');
