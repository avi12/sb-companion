// Fake YouTube "screen" (LOUNGE_SCREEN) that captures the videoId the phone/emulator casts to it.
// Uses "Link with TV code" (cloud Lounge pairing) so it works even from the NAT'd emulator.
import crypto from 'node:crypto';
import YouTubeCastReceiver, { Player } from 'yt-cast-receiver';

const CATEGORIES = ['sponsor', 'selfpromo', 'intro', 'outro', 'interaction', 'music_offtopic'];

async function fetchSegments(videoId) {
  const prefix = crypto.createHash('sha256').update(videoId).digest('hex').slice(0, 5);
  const url = `https://sponsor.ajay.app/api/skipSegments/${prefix}`
    + `?categories=${encodeURIComponent(JSON.stringify(CATEGORIES))}`;
  try {
    const r = await fetch(url, { headers: { 'X-CLIENT-NAME': 'sb-android-companion/0.1' } });
    if (r.status === 404) return console.log(`   SponsorBlock: no segments for ${videoId}`);
    const data = await r.json();
    const segs = data.find((v) => v.videoID === videoId)?.segments ?? [];
    console.log(`   SponsorBlock: ${segs.length} segment(s) for ${videoId}`);
    for (const s of segs) {
      console.log(`     ${s.category.padEnd(15)} ${s.segment[0].toFixed(1)}s -> ${s.segment[1].toFixed(1)}s  [${s.actionType}]`);
    }
  } catch (e) {
    console.log('   SB fetch error:', e.message);
  }
}

// A player that accepts the cast but plays nothing — we only want the videoId.
class CapturePlayer extends Player {
  async doPlay(video, position) {
    console.log(`\n🎯 CAPTURED videoId = ${video.id}  (pos ${position}s)`);
    if (video.context?.playlistId) console.log(`   playlist: ${video.context.playlistId} index ${video.context.index}`);
    await fetchSegments(video.id);
    console.log('   (in the real app: fetch -> feed SkipEngine -> seekTo on the local session)\n');
    return true;
  }
  async doPause() { return true; }
  async doResume() { return true; }
  async doStop() { return true; }
  async doSeek() { return true; }
  async doSetVolume() { return true; }
  async doGetVolume() { return { level: 100, muted: false }; }
  async doGetPosition() { return 0; }
  async doGetDuration() { return 0; }
}

const receiver = new YouTubeCastReceiver(new CapturePlayer(), {
  device: { name: 'SB-Test-Screen', screenName: 'SB-Test-Screen' },
  logLevel: 'warn',
});

receiver.on('error', (e) => console.error('receiver error:', e?.message || e));

await receiver.start();
console.log('✅ Fake YouTube screen running. Requesting a TV code…');

const pairing = receiver.getPairingCodeRequestService();
pairing.on('response', (code) => {
  const pretty = String(code).replace(/(\d{3})(?=\d)/g, '$1 ');
  console.log('\n📺  TV CODE:  ' + pretty);
  console.log('    In the emulator: YouTube → Cast icon → "Link with TV code" → enter it.\n');
});
pairing.on('error', (e) => console.error('pairing error:', e?.message || e));
pairing.start();
