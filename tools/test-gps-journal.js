const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const section = (start, end) => html.slice(html.indexOf(start), html.indexOf(end));
const prelude = section('    // Checkpoint dan ACK GPS', '    // Escape semua data');
const sessions = section('    // 4. GPS REAL-TIME', '    // =========================================================================\n    // PAIRING AMAN');
const gps = section('    function haversineKm(', '    function currentMonthKey(');
const tripStart = new Date('2026-11-15T12:00:00').getTime();
let checkpoint = '';
let queue = [];
let commitFails = false;
let virtualNow = tripStart + 30 * 60000;
let id = 0;
function addFixes(count, wall, elapsed, lat, boot = 1) {
    for (let i = 0; i < count; i++) queue.push({
        id: ++id, wall: wall + i * 2000, elapsed: elapsed + i * 2000,
        lat: lat + i * 0.00018, lng: 106.8, accuracy: 5,
        speed: 36, hasSpeed: true, bearing: 0, boot
    });
}
function createPage() {
    const storage = new Map([['gc_odo_km_v6', '50000'], ['gc_odo_calibrated_v1', '1']]);
    const elements = new Map();
    class TestDate extends Date {
        constructor(...args) { super(...(args.length ? args : [virtualNow])); }
        static now() { return virtualNow; }
    }
    const bridge = {
        readGpsCheckpoint: () => checkpoint,
        readGpsBatch: () => JSON.stringify(queue.slice(0, 120)),
        commitGpsBatch: (through, snapshot) => {
            if (commitFails) return false;
            checkpoint = snapshot;
            queue = queue.filter(fix => fix.id > through);
            return true;
        }
    };
    const context = vm.createContext({
        console: { log() {}, warn() {}, error() {} }, Date: TestDate,
        window: { AndroidBridge: bridge, addEventListener() {} },
        localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) },
        document: { getElementById: key => {
            if (!elements.has(key)) elements.set(key, { style: {}, innerText: '' });
            return elements.get(key);
        } },
        localDateIso: (date = new TestDate()) => date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'),
        currentMonthKey: () => '2026-11',
        mapInstance: null, carMarker: null, routePolyline: null, routeSegments: [],
        currentLat: 0, currentLng: 0, hasFirstGpsFix: false,
        setInterval() {}, setTimeout() {},
        renderTotalKm() {}, syncOdometerInput() {}, showToast() {},
        flushTripRouteBacklog() {}, publishServiceSnapshot() {},
        startupServiceAlertDismissedInSession: false,
        queueAutoSyncPayload() {}
    });
    vm.runInContext(prelude + sessions + `
        let serviceComponents = JSON.parse(localStorage.getItem('gc_service_components_v13') || '[{"remainingKm":1000}]');
        function deductServiceComponentsKm(km) { serviceComponents[0].remainingKm -= km; }
    ` + gps + '\nrestoreNativeGpsSession();', context);
    return {
        run: code => vm.runInContext(code, context),
        stats: () => vm.runInContext(`({km:sessionDistKm,seconds:sessionSeconds,odo:odometerKm,total:totalMonthlyKm,
            start:sessionStartClock,end:lastMovingTimestamp,route:currentSessionRouteEncoded(),
            trips:tripHistoryArr.length,service:serviceComponents[0].remainingKm,ready:gpsJournalReady})`, context)
    };
}
addFixes(60, tripStart, 100000, -6.2);
let page = createPage();
page.run('drainNativeGpsJournal()');
const first = page.stats();
assert(first.km > 1 && first.km < 1.3, 'camera-hidden fixes counted with real distance');
assert.equal(first.seconds, 118, 'duration from original elapsed timestamps, not close-camera time');
assert.equal(first.start, '12.00');
assert.equal(first.end, tripStart + 118000);
assert(first.route.length > 8);
assert.equal(queue.length, 0, 'ACK after checkpoint');
page = createPage();
page.run('drainNativeGpsJournal()');
assert.equal(page.stats().km, first.km, 'active session restored after page recreation');
assert.equal(page.stats().odo, first.odo, 'odometer not double-counted on recreation');

// Commit gagal: titik tetap native; reload memulihkan checkpoint lama dan replay tepat sekali.
addFixes(10, tripStart + 130000, 230000, -6.1892);
commitFails = true;
page.run('drainNativeGpsJournal()');
assert.equal(page.stats().ready, false);
assert.equal(queue.length, 10);
commitFails = false;
page = createPage();
page.run('drainNativeGpsJournal()');
const recovered = page.stats();
assert(recovered.total > first.total);
assert(Math.abs((recovered.odo - 50000) - recovered.total) < 0.000001);
assert(Math.abs((1000 - recovered.service) - recovered.total) < 0.000001, 'service deduction exactly once');
assert.equal(queue.length, 0);

// Jeda singgah singkat tetap satu trip, jeda >5 menit memulai trip baru.
const oldTrips = recovered.trips;
virtualNow += 6000;
addFixes(4, tripStart + 240000, 340000, -6.1874);
page.run('drainNativeGpsJournal()');
assert.equal(page.stats().trips, oldTrips);
virtualNow += 6000;
addFixes(8, tripStart + 600000, 5000, -6.18, 2);
page.run('drainNativeGpsJournal()');
assert.equal(page.stats().trips, oldTrips + 1, '5-minute rule and new boot reset elapsed anchor');
assert.equal(page.stats().start, '12.10');
assert.equal(queue.length, 0);
console.log('GPS journal OK: hidden-camera replay, real times, route, restart, atomic ACK failure, odometer/service dedup, stops, reboot.');
