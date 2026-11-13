/*
 * PangDash Sync Transport v1
 * --------------------------
 * Lapisan transport untuk PDSync. Isi pesan tetap dienkripsi oleh
 * sync-secure.js; modul ini hanya mengirim dan menerima amplop terenkripsi.
 *
 * Provider:
 *  - ably : REST publish + SSE subscribe + REST history (rekomendasi)
 *  - ntfy : ntfy.sh publish + SSE + JSON poll (cadangan/kompatibilitas)
 *
 * Konfigurasi diambil dari AndroidBridge.getSyncConfigJson() bila tersedia,
 * lalu dapat ditimpa window.PD_SYNC_CONFIG untuk pengujian browser.
 * Kunci Ably TIDAK boleh ditulis di repo; isi lewat env ABLY_API_KEY.
 */
(function (global) {
    'use strict';

    var config = {
        provider: 'ntfy',
        ntfyBase: 'https://ntfy.sh',
        ably: {
            key: '',
            clientId: '',
            restBase: 'https://rest.ably.io',
            realtimeBase: 'https://main.realtime.ably.net',
            namespace: 'pangdash'
        }
    };

    function merge(target, source) {
        if (!source || typeof source !== 'object') return target;
        Object.keys(source).forEach(function (key) {
            var value = source[key];
            if (value && typeof value === 'object' && !Array.isArray(value)) {
                if (!target[key] || typeof target[key] !== 'object') target[key] = {};
                merge(target[key], value);
            } else if (value !== undefined) {
                target[key] = value;
            }
        });
        return target;
    }

    // Basic auth boleh mengklaim clientId. Nilai ini membuat koneksi dianggap
    // "identified" bila ada rule lama yang masih mewajibkannya, tanpa perlu
    // menurunkan keamanan rule tersebut.
    function makeClientId() {
        var bytes = new Uint8Array(9);
        try {
            if (global.crypto && typeof global.crypto.getRandomValues === 'function') {
                global.crypto.getRandomValues(bytes);
            } else {
                for (var i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
            }
        } catch (_e) {
            for (var j = 0; j < bytes.length; j++) bytes[j] = Math.floor(Math.random() * 256);
        }
        var out = '';
        for (var k = 0; k < bytes.length; k++) {
            out += (bytes[k] < 16 ? '0' : '') + bytes[k].toString(16);
        }
        return 'pd-' + out;
    }

    function readNativeConfig() {
        try {
            if (global.AndroidBridge && typeof global.AndroidBridge.getSyncConfigJson === 'function') {
                var raw = global.AndroidBridge.getSyncConfigJson();
                if (raw) return JSON.parse(raw);
            }
        } catch (_e) {}
        return null;
    }

    (function initConfig() {
        var nativeConfig = readNativeConfig();
        if (nativeConfig) merge(config, nativeConfig);
        if (global.PD_SYNC_CONFIG) merge(config, global.PD_SYNC_CONFIG);
        config.provider = String(config.provider || 'ntfy').toLowerCase() === 'ably' ? 'ably' : 'ntfy';
        config.ably.key = String(config.ably.key || '').trim();
        config.ably.clientId = String(config.ably.clientId || '').trim();
        if (!config.ably.clientId) config.ably.clientId = makeClientId();
    })();

    function provider() {
        return config.provider;
    }

    function ready() {
        return config.provider !== 'ably' || !!config.ably.key;
    }

    // Batas ukuran satu amplop terenkripsi. Ably mengizinkan 64 KiB per pesan,
    // ntfy hanya nyaman sampai sekitar 4 KB, jadi payload servis tetap dipecah.
    function maxPayloadBytes() {
        return config.provider === 'ably' ? 60000 : 3800;
    }

    function ablyChannel(topic) {
        var value = String(topic || '');
        if (value.indexOf('pd1_state_') === 0) {
            return config.ably.namespace + ':state:' + value.slice('pd1_state_'.length);
        }
        if (value.indexOf('pd1_service_') === 0) {
            return config.ably.namespace + ':service:' + value.slice('pd1_service_'.length);
        }
        return config.ably.namespace + ':' + value;
    }

    function basicAuth(key) {
        return 'Basic ' + global.btoa(key);
    }

    function ablyHeaders() {
        var headers = { 'Authorization': basicAuth(config.ably.key) };
        if (config.ably.clientId) {
            try {
                headers['X-Ably-ClientId'] = global.btoa(config.ably.clientId);
            } catch (_e) {}
        }
        return headers;
    }

    async function makeHttpError(res) {
        var err = new Error('HTTP ' + res.status);
        err.status = res.status;
        err.retryAfter = parseInt(res.headers.get('retry-after') || '0', 10) || 0;
        try {
            var body = await res.json();
            if (body && body.error) {
                err.code = body.error.code;
                err.message = body.error.message || err.message;
            }
        } catch (_e) {}
        return err;
    }

    async function publishNtfy(topic, body) {
        var res = await global.fetch(config.ntfyBase + '/' + encodeURIComponent(topic), {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
            body: body,
            keepalive: true
        });
        if (!res.ok) throw await makeHttpError(res);
        return res;
    }

    async function publishAbly(topic, body) {
        var res = await global.fetch(
            config.ably.restBase + '/channels/' + encodeURIComponent(ablyChannel(topic)) + '/messages',
            {
                method: 'POST',
                headers: (function () {
                    var headers = ablyHeaders();
                    headers['Content-Type'] = 'application/json';
                    return headers;
                })(),
                body: JSON.stringify({ name: 'pd', data: body }),
                keepalive: true
            }
        );
        if (!res.ok) throw await makeHttpError(res);
        return res;
    }

    async function publish(topic, body) {
        if (!ready()) throw new Error('Sync backend belum dikonfigurasi');
        return config.provider === 'ably'
            ? publishAbly(topic, body)
            : publishNtfy(topic, body);
    }

    async function fetchRecentNtfy(topic, limit) {
        var res = await global.fetch(
            config.ntfyBase + '/' + encodeURIComponent(topic) + '/json?poll=1&since=all'
        );
        if (!res.ok) return [];
        var text = await res.text();
        var lines = text.split('\n').filter(Boolean).slice(-Math.max(1, limit || 100));
        var messages = [];
        for (var i = 0; i < lines.length; i++) {
            try {
                var evt = JSON.parse(lines[i]);
                if (evt && evt.event === 'message' && evt.message) messages.push(evt.message);
            } catch (_e) {}
        }
        return messages;
    }

    async function fetchRecentAbly(topic, limit) {
        var count = Math.max(1, Math.min(1000, limit || 100));
        var res = await global.fetch(
            config.ably.restBase + '/channels/' + encodeURIComponent(ablyChannel(topic)) +
            '/messages?limit=' + count + '&direction=backwards',
            { headers: ablyHeaders() }
        );
        if (!res.ok) return [];
        var json = await res.json();
        var items = Array.isArray(json) ? json : (json && Array.isArray(json.items) ? json.items : []);
        var messages = [];
        // Ably mengembalikan yang terbaru lebih dulu; balik agar urutan lama -> baru.
        for (var i = items.length - 1; i >= 0; i--) {
            var data = items[i] && items[i].data;
            if (typeof data === 'string' && data) messages.push(data);
        }
        return messages;
    }

    async function fetchRecent(topic, limit) {
        if (!ready()) return [];
        try {
            return config.provider === 'ably'
                ? await fetchRecentAbly(topic, limit)
                : await fetchRecentNtfy(topic, limit);
        } catch (_e) {
            return [];
        }
    }

    function subscribeNtfy(topic, onMessage, onError) {
        var es;
        try {
            es = new global.EventSource(config.ntfyBase + '/' + encodeURIComponent(topic) + '/sse');
        } catch (e) {
            if (onError) onError(e);
            return null;
        }
        es.onmessage = function (e) {
            try {
                var evt = JSON.parse(e.data);
                if (evt && evt.message) onMessage(evt.message);
            } catch (_e) {}
        };
        if (onError) es.onerror = onError;
        return {
            close: function () { try { es.close(); } catch (_e) {} },
            source: es
        };
    }

    function subscribeAbly(topic, onMessage, onError) {
        if (!ready()) return null;
        var url = config.ably.realtimeBase + '/sse?v=1.2' +
            '&key=' + encodeURIComponent(config.ably.key) +
            '&channels=' + encodeURIComponent(ablyChannel(topic)) +
            '&rewind=1' +
            (config.ably.clientId ? '&clientId=' + encodeURIComponent(config.ably.clientId) : '');
        var es;
        try {
            es = new global.EventSource(url);
        } catch (e) {
            if (onError) onError(e);
            return null;
        }
        es.onmessage = function (e) {
            try {
                var msg = JSON.parse(e.data);
                if (msg && typeof msg.data === 'string' && msg.data) onMessage(msg.data);
            } catch (_e) {}
        };
        es.onerror = function (e) {
            // EventSource akan reconnect otomatis. Tutup hanya jika kredensial ditolak
            // agar tidak terjadi loop error tanpa henti.
            try {
                if (e && e.data) {
                    var err = JSON.parse(e.data);
                    if (err && err.code >= 40100 && err.code < 40200) {
                        try { es.close(); } catch (_e2) {}
                    }
                }
            } catch (_e3) {}
            if (onError) onError(e);
        };
        return {
            close: function () { try { es.close(); } catch (_e) {} },
            source: es
        };
    }

    function subscribe(topic, onMessage, onError) {
        if (!ready()) return null;
        return config.provider === 'ably'
            ? subscribeAbly(topic, onMessage, onError)
            : subscribeNtfy(topic, onMessage, onError);
    }

    global.PDTransport = {
        provider: provider,
        ready: ready,
        publish: publish,
        fetchRecent: fetchRecent,
        subscribe: subscribe,
        maxPayloadBytes: maxPayloadBytes,
        ablyChannel: ablyChannel,
        getConfig: function () {
            return {
                provider: config.provider,
                ntfyBase: config.ntfyBase,
                ably: {
                    hasKey: !!config.ably.key,
                    hasClientId: !!config.ably.clientId,
                    clientId: config.ably.clientId,
                    restBase: config.ably.restBase,
                    realtimeBase: config.ably.realtimeBase,
                    namespace: config.ably.namespace
                }
            };
        }
    };
})(window);
