/* Standalone Nuvio playback resolver. No provider code is bundled in the APK. */
'use strict';

function originalSource(request) {
    try {
        const wrapper = new URL(request);
        if (wrapper.protocol !== 'https:' || wrapper.hostname !== 'nuvio-resolver.invalid') return null;
        if (wrapper.pathname !== '/proxy/hls/manifest.m3u8' && wrapper.pathname !== '/extractor/video') return null;
        if (wrapper.pathname === '/extractor/video' && (wrapper.searchParams.get('host') || '').toLowerCase() !== 'vavoo') return null;
        const value = wrapper.searchParams.get('d');
        if (!value) return null;
        const source = new URL(value);
        if (source.protocol !== 'https:' || source.host !== 'vavoo.to' || !/^\/vavoo-iptv\/play\/[a-zA-Z0-9_-]+\/?$/.test(source.pathname)) return null;
        // Discard query credentials and fragments; acquire fresh credentials on this device.
        return 'https://vavoo.to' + source.pathname;
    } catch (_) {
        return null;
    }
}

async function requestJson(url, body, headers) {
    const response = await fetch(url, {
        method: 'POST',
        headers: Object.assign({ Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' }, headers),
        body: JSON.stringify(body),
        redirect: 'manual'
    });
    if (!response.ok) {
        const error = new Error('Provider request failed (HTTP ' + response.status + ')');
        error.status = response.status;
        throw error;
    }
    return response.json();
}

async function freshSignature() {
    const now = Date.now();
    const bytes = new Uint8Array(8);
    crypto.getRandomValues(bytes);
    const uniqueId = Array.from(bytes, b => ('0' + b.toString(16)).slice(-2)).join('');
    const payload = {
        token: '', reason: 'app-focus', locale: 'de', theme: 'dark',
        metadata: {
            device: { type: 'phone', uniqueId: uniqueId },
            os: { name: 'android', version: '14', abis: ['arm64-v8a'], host: 'android' },
            app: { platform: 'android' },
            version: { package: 'net.vypn.app', binary: '1.4.1', js: '1.4.1' }
        },
        package: 'net.vypn.app', version: '1.4.1', process: 'app',
        firstAppStart: now - 86400000, lastAppStart: now,
        appFocusTime: 0, playerActive: false, playDuration: 0,
        devMode: false, hasAddon: true, castConnected: false,
        ipLocation: null, adblockEnabled: true,
        migrationApplied: false, migrationTargetInstalled: false,
        proxy: { supported: ['ss'], engine: 'Mu', ssVersion: '2022', enabled: false, autoServer: true, id: '' },
        iap: { supported: false, error: '' }
    };
    const result = await requestJson('https://www.vypn.net/api/app/ping', payload, {
        'User-Agent': 'electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)',
        'Accept-Language': 'de'
    });
    if (!result || typeof result.addonSig !== 'string' || !result.addonSig) {
        throw new Error('Provider did not issue a temporary signature');
    }
    // Do not decode, alter, log or persist the provider-issued signature.
    return result.addonSig;
}

async function getStreams(request, mediaType) {
    if (mediaType !== 'resolver') return [];
    const source = originalSource(request);
    if (!source) return [];
    for (let attempt = 0; attempt < 2; attempt++) {
        try {
            const signature = await freshSignature();
            const result = await requestJson('https://vavoo.to/mediahubmx-resolve.json', {
                language: 'de', region: 'AT', url: source, clientVersion: '3.1.0'
            }, { 'User-Agent': 'MediaHubMX/2', 'mediahubmx-signature': signature });
            const item = Array.isArray(result) ? result[0] : result && (result.data || result);
            if (!item || typeof item.url !== 'string') throw new Error('Provider returned no stream');
            const url = new URL(item.url);
            if (!/^https?:$/.test(url.protocol)) throw new Error('Provider returned an unsupported stream');
            return [{
                name: 'Vavoo local resolver', title: 'Vavoo', url: item.url,
                // Opaque URLs are sniffed by the proxy; do not assume every result is HLS.
                type: /\.m3u8$/i.test(url.pathname) ? 'hls' : 'http',
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36',
                    Referer: 'https://vavoo.to/'
                }
            }];
        } catch (error) {
            // One fresh handshake for rejected credentials; no unbounded retry loop.
            if (attempt === 0 && (error.status === 401 || error.status === 403)) continue;
            // Deliberately omit response bodies, signatures and stream URLs from errors.
            throw new Error('Vavoo resolution failed; retry the source or update the plugin');
        }
    }
    return [];
}

module.exports = { getStreams: getStreams };
