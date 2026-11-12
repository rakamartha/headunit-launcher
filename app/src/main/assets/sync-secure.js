/*
 * PangDash Secure Sync v1
 * -----------------------
 * Modul ini dipakai oleh PangDash (headunit) dan PangLink (HP).
 *
 * Prinsip:
 *  - Kode pairing 12 karakter (60 bit) dari CSPRNG, bukan 4 digit.
 *  - Nama topik ntfy diturunkan dari SHA-256(kode pairing + purpose),
 *    sehingga tidak bisa ditebak dari kode yang dipajang.
 *  - Isi pesan dienkripsi AES-GCM 256-bit. Kunci diturunkan dari kode
 *    pairing. Tanpa kode pairing, isi pesan tidak bisa dibaca/dipalsukan.
 *  - Setiap amplop punya timestamp + nonce untuk deteksi replay.
 *
 * Catatan: Web Crypto membutuhkan secure context. Di WebView, halaman
 * file:///android_asset dianggap secure context pada Chromium modern.
 * Jika tidak tersedia, sync aman dinonaktifkan (tidak pernah fallback
 * ke plaintext).
 */
(function (global) {
    'use strict';

    var ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'; // Crockford Base32 (tanpa I, L, O, U)
    var SECRET_LEN = 12;
    var PREFIX = 'PD1.';
    var AAD_PREFIX = 'PangDash/Envelope/v1|';
    var textEncoder = new TextEncoder();
    var textDecoder = new TextDecoder();
    var subtle = (global.crypto && global.crypto.subtle) ? global.crypto.subtle : null;
    var sessionCache = new Map();

    function isSupported() {
        return !!(subtle && global.crypto && typeof global.crypto.getRandomValues === 'function' &&
            typeof global.TextEncoder === 'function' && typeof global.btoa === 'function');
    }

    function normalizeSecret(input) {
        var s = String(input == null ? '' : input).toUpperCase().trim();
        // Hanya buang awalan "PD-" / "PD " jika memang ada pemisah,
        // supaya kode yang kebetulan diawali huruf P,D tidak rusak.
        s = s.replace(/^PD[\s\-_]+/, '');
        s = s.replace(/[^0-9A-Z]/g, '');
        s = s.replace(/[IL]/g, '1').replace(/O/g, '0').replace(/U/g, 'V');
        return s;
    }

    function isValidSecret(secret) {
        var raw = normalizeSecret(secret);
        if (raw.length !== SECRET_LEN) return false;
        for (var i = 0; i < raw.length; i++) {
            if (ALPHABET.indexOf(raw.charAt(i)) < 0) return false;
        }
        return true;
    }

    function generateSecret() {
        if (!global.crypto || typeof global.crypto.getRandomValues !== 'function') {
            throw new Error('CSPRNG tidak tersedia');
        }
        var bytes = new Uint8Array(SECRET_LEN);
        global.crypto.getRandomValues(bytes);
        var out = '';
        for (var i = 0; i < bytes.length; i++) {
            // 256 habis dibagi 32, jadi modulo tidak bias.
            out += ALPHABET.charAt(bytes[i] % ALPHABET.length);
        }
        return out;
    }

    function formatSecret(secret) {
        var raw = normalizeSecret(secret);
        return 'PD-' + raw.replace(/(.{4})(?=.)/g, '$1-');
    }

    function formatCarId(carId) {
        var raw = String(carId || '').toUpperCase();
        return raw ? ('PD-' + raw) : '';
    }

    function toHex(bytes) {
        var out = '';
        for (var i = 0; i < bytes.length; i++) {
            out += bytes[i].toString(16).padStart(2, '0');
        }
        return out;
    }

    function randomHex(byteLength) {
        var bytes = new Uint8Array(byteLength);
        global.crypto.getRandomValues(bytes);
        return toHex(bytes);
    }

    function bytesToBase64(bytes) {
        var binary = '';
        for (var i = 0; i < bytes.length; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return global.btoa(binary);
    }

    function base64ToBytes(value) {
        var binary = global.atob(value);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
    }

    function sha256(text) {
        return subtle.digest('SHA-256', textEncoder.encode(text)).then(function (buf) {
            return new Uint8Array(buf);
        });
    }

    function aadFor(purpose) {
        return AAD_PREFIX + String(purpose || '');
    }

    async function createSession(secret) {
        if (!isSupported()) {
            throw new Error('Web Crypto tidak tersedia di WebView ini');
        }
        var raw = normalizeSecret(secret);
        if (!isValidSecret(raw)) {
            throw new Error('Kode pairing harus 12 karakter (huruf/angka, tanpa I/L/O/U)');
        }
        if (sessionCache.has(raw)) {
            return sessionCache.get(raw);
        }
        var promise = (async function () {
            var carIdHash = await sha256('PangDash/CarId/v1|' + raw);
            var stateHash = await sha256('PangDash/Topic/v1|state|' + raw);
            var serviceHash = await sha256('PangDash/Topic/v1|service|' + raw);
            var keyHash = await sha256('PangDash/Key/v1|' + raw);
            var key = await subtle.importKey(
                'raw', keyHash, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']
            );
            return {
                secret: raw,
                carId: toHex(carIdHash.slice(0, 4)).toUpperCase(),
                key: key,
                stateTopic: 'pd1_state_' + toHex(stateHash.slice(0, 16)),
                serviceTopic: 'pd1_service_' + toHex(serviceHash.slice(0, 16))
            };
        })();
        sessionCache.set(raw, promise);
        return promise;
    }

    async function seal(session, purpose, payload, extra) {
        if (!session || !session.key) throw new Error('Sesi sync belum siap');
        var envelope = {
            v: 1,
            carId: session.carId,
            ts: Date.now(),
            nonce: randomHex(16),
            payload: payload
        };
        if (extra && typeof extra === 'object') {
            Object.keys(extra).forEach(function (k) {
                if (envelope[k] === undefined) envelope[k] = extra[k];
            });
        }
        var iv = global.crypto.getRandomValues(new Uint8Array(12));
        var ciphertext = await subtle.encrypt(
            {
                name: 'AES-GCM',
                iv: iv,
                additionalData: textEncoder.encode(aadFor(purpose)),
                tagLength: 128
            },
            session.key,
            textEncoder.encode(JSON.stringify(envelope))
        );
        return PREFIX + bytesToBase64(iv) + '.' + bytesToBase64(new Uint8Array(ciphertext));
    }

    async function open(session, purpose, text) {
        if (!session || !session.key) return null;
        if (typeof text !== 'string' || text.indexOf(PREFIX) !== 0) return null;
        var parts = text.split('.');
        if (parts.length !== 3) return null;
        try {
            var iv = base64ToBytes(parts[1]);
            var ciphertext = base64ToBytes(parts[2]);
            var plaintext = await subtle.decrypt(
                {
                    name: 'AES-GCM',
                    iv: iv,
                    additionalData: textEncoder.encode(aadFor(purpose)),
                    tagLength: 128
                },
                session.key,
                ciphertext
            );
            var envelope = JSON.parse(textDecoder.decode(plaintext));
            if (!envelope || envelope.v !== 1) return null;
            if (String(envelope.carId || '').toUpperCase() !== session.carId) return null;
            return envelope;
        } catch (_e) {
            return null;
        }
    }

    global.PDSync = {
        SECRET_LEN: SECRET_LEN,
        isSupported: isSupported,
        normalizeSecret: normalizeSecret,
        isValidSecret: isValidSecret,
        generateSecret: generateSecret,
        formatSecret: formatSecret,
        formatCarId: formatCarId,
        createSession: createSession,
        seal: seal,
        open: open
    };
})(window);
