const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const code = readFileSync(require.resolve('../vavoo.js'), 'utf8');
const source = 'https://vavoo.to/vavoo-iptv/play/test-channel';
const wrap = (url = source, path = '/proxy/hls/manifest.m3u8') =>
    'https://nuvio-resolver.invalid' + path + '?d=' + encodeURIComponent(url) + '&host=Vavoo&api_password=nuvio';

function plugin(replies) {
    const calls = [];
    const context = {
        module: { exports: {} }, URL, Uint8Array, crypto: webcrypto,
        fetch: async (url, options) => {
            calls.push({ url, ...options, body: JSON.parse(options.body) });
            assert.ok(replies.length, 'unexpected provider request');
            const reply = replies.shift();
            if (reply instanceof Error) throw reply;
            return { ok: (reply.status || 200) < 300, status: reply.status || 200, json: async () => reply.data };
        }
    };
    vm.runInNewContext(code, context);
    return { run: request => context.module.exports.getStreams(request, 'resolver'), calls };
}

test('TVvoo EasyProxy and Mediaflow wrappers resolve locally with unchanged fresh signatures', async () => {
    for (const path of ['/proxy/hls/manifest.m3u8', '/extractor/video']) {
        const p = plugin([{ data: { addonSig: 'opaque-provider-signature' } }, { data: [{ url: 'https://cdn.example/live.m3u8' }] }]);
        const result = await p.run(wrap(source + '?stale=credential', path));
        assert.equal(result[0].type, 'hls');
        assert.equal(result[0].url, 'https://cdn.example/live.m3u8');
        assert.equal(p.calls.length, 2);
        assert.equal(p.calls[1].body.url, source);
        assert.equal(p.calls[1].headers['mediahubmx-signature'], 'opaque-provider-signature');
        assert.equal(p.calls[0].body.ipLocation, null);
        assert.equal(p.calls[0].headers['x-forwarded-for'], undefined);
        assert.equal(p.calls[1].redirect, 'manual');
        assert.equal(result[0].headers['mediahubmx-signature'], undefined);
    }
});

test('unrelated hosts, unsupported paths and malformed requests never obtain credentials', async () => {
    const p = plugin([]);
    for (const url of ['bad', wrap('https://vavoo.to.attacker.example/vavoo-iptv/play/a'), wrap('http://vavoo.to/vavoo-iptv/play/a'), wrap('https://127.0.0.1/a'), wrap(source, '/wrong'), wrap().replace('nuvio-resolver.invalid', 'other.example')]) {
        assert.equal((await p.run(url)).length, 0);
    }
    assert.equal(p.calls.length, 0);
});

test('401/403 performs one new handshake, without reusing rejected signature', async () => {
    for (const status of [401, 403]) {
        const p = plugin([{ data: { addonSig: 'old' } }, { status }, { data: { addonSig: 'new' } }, { data: { data: { url: 'https://cdn.example/opaque' } } }]);
        const result = await p.run(wrap());
        assert.equal(p.calls.length, 4);
        assert.equal(p.calls[3].headers['mediahubmx-signature'], 'new');
        assert.equal(result[0].type, 'http');
    }
});

test('repeated rejection stops and errors contain no credentials or response bodies', async () => {
    const p = plugin([{ data: { addonSig: 'secret' } }, { status: 403 }, { data: { addonSig: 'secret2' } }, { status: 403 }]);
    await assert.rejects(p.run(wrap()), e => !e.message.includes('secret') && e.message.includes('resolution failed'));
    assert.equal(p.calls.length, 4);
});

test('every playback obtains new credentials; nothing is cached across calls', async () => {
    const p = plugin([{ data: { addonSig: 'one' } }, { data: { url: 'https://cdn.example/one' } }, { data: { addonSig: 'two' } }, { data: { url: 'https://cdn.example/two' } }]);
    await p.run(wrap());
    await p.run(wrap());
    assert.equal(p.calls[1].headers['mediahubmx-signature'], 'one');
    assert.equal(p.calls[3].headers['mediahubmx-signature'], 'two');
});

test('missing signature, empty resolve, unsafe scheme and network errors fail closed', async () => {
    for (const replies of [
        [{ data: {} }],
        [{ data: { addonSig: 'secret' } }, { data: [] }],
        [{ data: { addonSig: 'secret' } }, { data: { url: 'file:///tmp/test' } }],
        [new Error('network error with secret')]
    ]) {
        const p = plugin(replies);
        await assert.rejects(p.run(wrap()), e => !e.message.includes('secret'));
    }
});
