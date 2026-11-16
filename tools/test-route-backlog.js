const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const source = html.slice(html.indexOf('    const publishedRouteSignatures ='), html.indexOf('    function rememberNonce('));

async function run() {
    const sent = [];
    const listeners = {};
    let fail = false;
    const context = vm.createContext({
        Map, JSON, Date,
        currentSessionId: 'active-trip',
        AUTO_STATE_PUSH_INTERVAL_MS: 300000,
        tripHistoryArr: Array.from({ length: 7 }, (_, i) => ({ tripId: 'trip-' + i, route: 'encoded-route-' + i })),
        navigator: { onLine: false },
        syncSession: { stateTopic: 'test-state' },
        syncQuotaBlockedUntil: 0,
        PDTransport: { ready: () => true, maxPayloadBytes: () => 3800 },
        PDSync: { seal: async (_session, _purpose, payload) => JSON.stringify(payload) },
        postSyncMessage: async (_topic, body) => {
            if (fail) throw new Error('offline');
            sent.push(JSON.parse(body));
        },
        markSyncQuotaExhausted: () => {},
        setInterval: () => {},
        window: { addEventListener: (name, handler) => { listeners[name] = handler; } },
        mapRecoveryAttempts: 0,
        recoverLiveMap: () => {}
    });
    vm.runInContext(source, context);
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(sent.length, 0, 'offline must not mark a route sent');
    context.navigator.onLine = true;
    fail = true;
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(sent.length, 0);
    fail = false;
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(sent.length, 3, 'bounded batch after network recovers');
    await vm.runInContext('flushTripRouteBacklog()', context);
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(new Set(sent.map(item => item.tripId)).size, 7, 'all stored routes, not only latest');
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(sent.length, 7, 'unchanged routes not repeated in this session');
    context.tripHistoryArr[0].route += '-updated';
    await Promise.all([
        vm.runInContext('flushTripRouteBacklog()', context),
        vm.runInContext('flushTripRouteBacklog()', context)
    ]);
    assert.equal(sent.length, 8, 'changed route retried without concurrent duplicate batches');
    vm.runInContext('publishedRouteSignatures.clear()', context);
    await vm.runInContext('flushTripRouteBacklog()', context);
    assert.equal(sent.length, 11, 'restart can replay routes from persisted history');
    console.log('Route backlog OK: offline, failure/retry, batch limit, history, dedup, update, restart.');
}
run().catch(error => { console.error(error); process.exitCode = 1; });
