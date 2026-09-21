'use strict';

// Tests the complete installed-plugin -> local proxy -> internal player path.
module.exports.getStreams = async function(request, mediaType) {
    if (mediaType !== 'resolver' || request !== 'https://nuvio-resolver.invalid/test.m3u8') return [];
    return [{
        title: 'Big Buck Bunny — resolver test',
        url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
        type: 'hls'
    }];
};
