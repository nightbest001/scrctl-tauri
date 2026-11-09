// =============================================================================
// Orbit — fitur tambahan di Beranda dan menu: kartu musik, Pesan, Telepon, Foto, Apps, Deck,
// serta tombol Wi-Fi / Bluetooth / Hotspot. Dimuat setelah renderer.js (memakai $, h, ICON, toast, dll).
// Pesan dari HP datang lewat event "link-msg" (lihat src-tauri/src/link.rs dan android/.../Features.kt).
// =============================================================================
(() => {
  'use strict';
  const api = window.api;
  const send = (msg) => api.linkSend(msg);
  const connected = () => !!(typeof linkInfo !== 'undefined' && linkInfo && linkInfo.connected);
  const B64 = (kind, b) => `data:${kind};base64,${b}`;
  const pad = (n) => String(n).padStart(2, '0');
  const mmss = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return `${Math.floor(s / 60)}:${pad(s % 60)}`; };
  const hhmm = (ts) => { const d = new Date(ts); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const dayLabel = (ts) => {
    const d = new Date(ts), n = new Date();
    if (d.toDateString() === n.toDateString()) return hhmm(ts);
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}`;
  };
  let caps = null; // izin yang sudah diberikan di HP (dikirim app Link)

  // ---------------------------------------------------------------------------
  // Wi-Fi / Bluetooth / Hotspot (lewat adb). Status asli datang dari Link; sementara itu pakai tebakan optimis.
  // ---------------------------------------------------------------------------
  const optimistic = {};
  const lastKnown = {}; // tebakan terakhir untuk status yang tidak dilaporkan Link (mis. hotspot di Android baru)
  const KIND_LABEL = { wifi: 'Wi-Fi', bt: 'Bluetooth', hotspot: 'Hotspot' };
  window.toggleState = (kind) => {
    const o = optimistic[kind];
    if (o && Date.now() < o.until) return o.v;
    const v = typeof linkInfo !== 'undefined' && linkInfo ? linkInfo[kind] : null;
    if (v == null) return lastKnown[kind] == null ? null : lastKnown[kind];
    lastKnown[kind] = !!v;
    return !!v;
  };
  window.togglePhone = async (kind) => {
    const dev = dashDevice();
    if (!dev || dev.state !== 'device') { toast(`Butuh koneksi adb ke HP (menu Setup) untuk mengubah ${KIND_LABEL[kind]}`, true); return; }
    const want = !(window.toggleState(kind) === true);
    optimistic[kind] = { v: want, until: Date.now() + 6000 };
    renderDash();
    let r;
    try { r = await api.phoneToggle(dev.serial, kind, want); } catch (e) { r = { ok: false, err: String(e) }; }
    if (!r || !r.ok) {
      delete optimistic[kind];
      toast(`${KIND_LABEL[kind]} gagal: ${(r && r.err) || 'tidak diketahui'}`, true);
    } else {
      lastKnown[kind] = want;
      tlPush(`${KIND_LABEL[kind]} HP ${want ? 'dinyalakan' : 'dimatikan'}`, dev.name || 'adb', 'phone');
      setTimeout(renderDash, 6200);
    }
    renderDash();
  };

  // ---------------------------------------------------------------------------
  // Kartu musik (HP lewat Link, PC lewat SMTC Windows)
  // ---------------------------------------------------------------------------
  const media = { phone: { has: false }, pc: { has: false } };
  let mediaSrc = null;     // pilihan manual: 'phone' | 'pc'
  let phoneArt = {};       // key lagu -> gambar base64
  const mediaAt = { phone: 0, pc: 0 };
  let mediaEls = null;

  function activeMedia() {
    const p = media.phone, c = media.pc;
    if (mediaSrc === 'phone' && p.has) return ['phone', p];
    if (mediaSrc === 'pc' && c.has) return ['pc', c];
    if (c.has && c.playing) return ['pc', c];
    if (p.has && p.playing) return ['phone', p];
    if (c.has) return ['pc', c];
    if (p.has) return ['phone', p];
    return null;
  }
  const mediaCmd = (src, a, pos) => (src === 'phone' ? send({ t: 'mcmd', a, pos }) : api.pcMediaCmd(a, pos));
  const mediaPos = (src, m) => Math.min(m.dur || Infinity, (m.pos || 0) + (m.playing ? Date.now() - mediaAt[src] : 0));

  function renderMedia() {
    const card = $('mediaCard');
    if (!card) return;
    const cur = activeMedia();
    mediaEls = null;
    if (!cur) { card.classList.add('hidden'); card.innerHTML = ''; return; }
    const [src, m] = cur;
    card.classList.remove('hidden');
    card.innerHTML = '';
    const art = src === 'phone' && phoneArt[m.key]
      ? h('img', { class: 'm-art', src: B64('image/jpeg', phoneArt[m.key]), alt: '' })
      : h('div', { class: 'm-art ph', html: ICON.music });
    const btn = (icon, title, fn, cls) => h('button', { class: 'm-btn ' + (cls || ''), title, onclick: fn, html: ICON[icon] });
    const bar = h('div', { class: 'm-bar' }, h('i'));
    const tPos = h('span', {}, '0:00'), tDur = h('span', {}, m.dur ? mmss(m.dur) : '--:--');
    bar.addEventListener('click', (e) => {
      if (!m.dur) return;
      const r = bar.getBoundingClientRect();
      const pos = Math.round(m.dur * Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)));
      m.pos = pos; mediaAt[src] = Date.now();
      mediaCmd(src, 'seek', pos);
      tickMedia();
    });
    const chips = [];
    if (media.phone.has && media.pc.has) {
      chips.push(h('div', { class: 'm-src' },
        h('button', { class: src === 'pc' ? 'on' : '', onclick: () => { mediaSrc = 'pc'; renderMedia(); } }, 'PC'),
        h('button', { class: src === 'phone' ? 'on' : '', onclick: () => { mediaSrc = 'phone'; renderMedia(); } }, 'HP')));
    }
    card.append(
      art,
      h('div', { class: 'm-main' },
        h('div', { class: 'm-top' },
          h('span', { class: 'm-tag' }, h('span', { html: src === 'pc' ? ICON.pcIcon : ICON.phone }), src === 'pc' ? 'PC' : 'HP', m.app ? ` · ${m.app}` : ''),
          ...chips),
        h('div', { class: 'm-title' }, m.title || 'Tanpa judul'),
        h('div', { class: 'm-artist' }, m.artist || ' '),
        h('div', { class: 'm-time' }, tPos, bar, tDur)),
      h('div', { class: 'm-ctl' },
        btn('prev', 'Sebelumnya', () => mediaCmd(src, 'prev')),
        btn('pause', 'Putar/Jeda', () => {
          m.pos = mediaPos(src, m); mediaAt[src] = Date.now(); m.playing = !m.playing;
          mediaCmd(src, 'toggle'); renderMedia();
        }, 'main'),
        btn('next', 'Berikutnya', () => mediaCmd(src, 'next'))));
    // ikon play/pause menyesuaikan status
    const mainBtn = card.querySelector('.m-btn.main');
    if (mainBtn) mainBtn.innerHTML = m.playing ? ICON.pause : ICON.play;
    mediaEls = { src, m, bar: bar.firstChild, tPos, tDur };
  }

  function tickMedia() {
    if (!mediaEls) return;
    const { src, m, bar, tPos } = mediaEls;
    const pos = mediaPos(src, m);
    tPos.textContent = mmss(pos);
    bar.style.width = m.dur ? `${Math.min(100, (pos / m.dur) * 100).toFixed(1)}%` : '0%';
  }
  setInterval(() => { if (currentPage === 'home' && !document.hidden) tickMedia(); }, 500);

  function onPhoneMedia(v) {
    if (!v.has) { media.phone = { has: false }; renderMedia(); return; }
    if (v.art && v.key) phoneArt[v.key] = v.art;
    if (Object.keys(phoneArt).length > 20) { const keep = {}; if (v.key && phoneArt[v.key]) keep[v.key] = phoneArt[v.key]; phoneArt = keep; }
    const prev = media.phone;
    media.phone = { has: true, app: v.app, title: v.title, artist: v.artist, playing: !!v.playing, pos: v.pos || 0, dur: v.dur || 0, key: v.key };
    mediaAt.phone = Date.now();
    // jangan bangun ulang kartu kalau yang berubah hanya posisi
    const same = prev.has && prev.key === media.phone.key && prev.playing === media.phone.playing && prev.dur === media.phone.dur;
    if (same && mediaEls && mediaEls.src === 'phone') { mediaEls.m = media.phone; tickMedia(); return; }
    renderMedia();
  }
  function onPcMedia(v) {
    if (!v || !v.has) media.pc = { has: false };
    else media.pc = { has: true, app: v.app, title: v.title, artist: v.artist, playing: !!v.playing, pos: v.pos || 0, dur: v.dur || 0, key: `${v.app}|${v.title}|${v.artist}` };
    mediaAt.pc = Date.now();
    renderMedia();
  }
  api.onPcMedia(onPcMedia);
  api.pcMediaState().then(onPcMedia).catch(() => {});

  // ---------------------------------------------------------------------------
  // Util halaman: catatan bila HP belum terhubung / izin belum ada
  // ---------------------------------------------------------------------------
  function note(id, text) {
    const el = $(id);
    if (!el) return;
    el.textContent = text || '';
    el.classList.toggle('hidden', !text);
  }
  function pageNote(id, capKey) {
    if (!connected()) return note(id, 'HP belum terhubung lewat Link. Buka menu Link HP untuk menyambungkan.');
    if (caps && capKey && caps[capKey] === false) return note(id, 'Izin belum diberikan di HP. Buka app Orbit Link → bagian Izin → "Pesan, kontak, telepon, foto".');
    note(id, '');
  }
  const empty = (text) => h('div', { class: 'empty-note' }, text);

  // ---------------------------------------------------------------------------
  // Pesan (SMS)
  // ---------------------------------------------------------------------------
  const sms = { threads: [], tid: null, address: '', name: '', msgs: [], canSend: true };

  function renderSmsList() {
    const el = $('smsList');
    el.innerHTML = '';
    if (!sms.threads.length) { el.append(empty(connected() ? 'Belum ada pesan (atau izin SMS belum diberikan).' : 'HP belum terhubung.')); return; }
    for (const t of sms.threads) {
      el.append(h('button', { class: 'li' + (sms.tid === t.tid ? ' on' : ''), onclick: () => openThread(t) },
        h('div', { class: 'li-av' }, (t.name || t.address || '?').trim().charAt(0).toUpperCase()),
        h('div', { class: 'li-main' },
          h('div', { class: 'li-t' }, t.name || t.address || 'Tanpa nomor'),
          h('div', { class: 'li-s' }, (t.out ? 'Kamu: ' : '') + t.snippet)),
        h('div', { class: 'li-r' }, dayLabel(t.date), t.unread > 0 && h('i', { class: 'badge-count' }, t.unread))));
    }
  }
  function renderSmsMsgs() {
    const el = $('smsMsgs');
    el.innerHTML = '';
    $('smsHead').textContent = sms.address ? (sms.name ? `${sms.name} · ${sms.address}` : sms.address) : 'Pilih percakapan';
    const can = !!sms.address && connected();
    $('smsText').disabled = !can; $('smsSend').disabled = !can;
    if (!sms.msgs.length) { el.append(empty(sms.address ? 'Belum ada pesan.' : 'Pilih percakapan di kiri atau buka nomor baru.')); return; }
    for (const m of sms.msgs) {
      el.append(h('div', { class: 'bub ' + (m.out ? 'out' : 'in') + (m.pending ? ' pend' : '') }, h('div', { class: 'bub-t' }, m.body), h('div', { class: 'bub-d' }, hhmm(m.date))));
    }
    el.scrollTop = el.scrollHeight;
  }
  function openThread(t) {
    sms.tid = t.tid; sms.address = t.address; sms.name = t.name || ''; sms.msgs = [];
    renderSmsList(); renderSmsMsgs();
    send({ t: 'sms', a: 'thread', tid: t.tid });
  }
  function refreshSms() {
    if (!connected()) { renderSmsList(); renderSmsMsgs(); return; }
    send({ t: 'sms', a: 'threads' });
    if (sms.tid != null) send({ t: 'sms', a: 'thread', tid: sms.tid });
  }
  async function doSendSms() {
    const text = $('smsText').value.trim();
    if (!text || !sms.address) return;
    $('smsText').value = '';
    sms.msgs.push({ body: text, date: Date.now(), out: true, pending: true });
    renderSmsMsgs();
    const ok = await send({ t: 'sms', a: 'send', to: sms.address, text });
    if (!ok) toast('Gagal mengirim: HP tidak terhubung', true);
    setTimeout(refreshSms, 1500);
  }
  $('smsSend').addEventListener('click', doSendSms);
  $('smsText').addEventListener('keydown', (e) => { if (e.key === 'Enter') doSendSms(); });
  $('smsRefresh').addEventListener('click', refreshSms);
  const newThread = () => {
    const n = $('smsNew').value.replace(/[^\d+]/g, '');
    if (!n) return;
    const known = sms.threads.find((t) => (t.address || '').replace(/[^\d+]/g, '') === n);
    if (known) openThread(known);
    else { sms.tid = null; sms.address = n; sms.name = ''; sms.msgs = []; renderSmsList(); renderSmsMsgs(); }
    $('smsNew').value = '';
  };
  $('smsNewGo').addEventListener('click', newThread);
  $('smsNew').addEventListener('keydown', (e) => { if (e.key === 'Enter') newThread(); });

  // ---------------------------------------------------------------------------
  // Telepon
  // ---------------------------------------------------------------------------
  let callLog = [];
  const KIND_TXT = { in: 'Masuk', out: 'Keluar', missed: 'Tak terjawab', other: 'Lainnya' };
  function renderCalls() {
    const el = $('callList');
    el.innerHTML = '';
    if (!callLog.length) { el.append(empty(connected() ? 'Riwayat kosong (atau izin riwayat panggilan belum diberikan).' : 'HP belum terhubung.')); return; }
    for (const c of callLog) {
      el.append(h('button', { class: 'li', title: 'Telepon', onclick: () => dial(c.number) },
        h('div', { class: 'li-av ' + (c.kind === 'missed' ? 'red' : '') }, h('span', { html: ICON.call })),
        h('div', { class: 'li-main' },
          h('div', { class: 'li-t' }, c.name || c.number || 'Tak dikenal'),
          h('div', { class: 'li-s' }, `${KIND_TXT[c.kind] || ''}${c.dur ? ' · ' + mmss(c.dur * 1000) : ''}${c.name ? ' · ' + c.number : ''}`)),
        h('div', { class: 'li-r' }, dayLabel(c.date))));
    }
  }
  async function dial(number) {
    const n = String(number || '').replace(/[^\d+*#]/g, '');
    if (!n) return;
    const ok = await send({ t: 'calls', a: 'dial', number: n });
    toast(ok ? `Menelepon ${n} dari HP…` : 'HP belum terhubung', !ok);
  }
  $('callGo').addEventListener('click', () => dial($('callNum').value));
  $('callNum').addEventListener('keydown', (e) => { if (e.key === 'Enter') dial($('callNum').value); });
  $('callRefresh').addEventListener('click', () => send({ t: 'calls', a: 'log' }));

  // Panggilan masuk / sedang berlangsung
  let lastCallState = 'idle';
  function onCallState(v) {
    const bar = $('callBar');
    bar.innerHTML = '';
    const who = v.name || v.number || 'Nomor tak dikenal';
    if (v.state === 'ringing') {
      if (lastCallState !== 'ringing') { try { new Notification('Panggilan masuk', { body: who }); } catch (e) { /* abaikan */ } tlPush(`Panggilan masuk: ${who}`, 'Telepon', 'phone'); }
      bar.classList.remove('hidden');
      bar.append(h('div', { class: 'cb-ico ring', html: ICON.call }), h('div', { class: 'cb-txt' }, h('b', {}, 'Panggilan masuk'), h('span', {}, who)),
        h('button', { class: 'btn green sm', onclick: () => send({ t: 'calls', a: 'answer' }) }, 'Jawab'),
        h('button', { class: 'btn danger sm', onclick: () => send({ t: 'calls', a: 'end' }) }, 'Tolak'));
    } else if (v.state === 'offhook') {
      bar.classList.remove('hidden');
      bar.append(h('div', { class: 'cb-ico', html: ICON.call }), h('div', { class: 'cb-txt' }, h('b', {}, 'Dalam panggilan'), h('span', {}, who + ' · suara di HP')),
        h('button', { class: 'btn danger sm', onclick: () => send({ t: 'calls', a: 'end' }) }, 'Akhiri'));
    } else {
      bar.classList.add('hidden');
      if (lastCallState !== 'idle' && currentPage === 'calls') setTimeout(() => send({ t: 'calls', a: 'log' }), 1200);
    }
    lastCallState = v.state;
  }

  // ---------------------------------------------------------------------------
  // Foto
  // ---------------------------------------------------------------------------
  let photos = [];
  let photoLoading = false;
  const pendingPhoto = new Map(); // nama file -> true (menunggu file masuk lalu dibuka)
  function renderPhotos() {
    const el = $('photoGrid');
    el.innerHTML = '';
    if (!photos.length) { el.append(empty(photoLoading ? 'Memuat foto…' : connected() ? 'Belum ada foto (atau izin foto belum diberikan).' : 'HP belum terhubung.')); return; }
    for (const p of photos) {
      el.append(h('button', { class: 'ph', title: p.name, onclick: () => { pendingPhoto.set(p.name, true); send({ t: 'photos', a: 'get', id: p.id }); toast(`Mengunduh ${p.name}…`); } },
        h('img', { src: B64('image/jpeg', p.thumb), alt: p.name, draggable: 'false' })));
    }
  }
  function loadPhotos() {
    if (!connected()) { renderPhotos(); return; }
    photoLoading = true; renderPhotos();
    send({ t: 'photos', a: 'list', limit: 80 });
  }
  $('photoRefresh').addEventListener('click', loadPhotos);
  $('photoFolder').addEventListener('click', () => api.linkOpenReceived(null).catch((e) => toast(String(e), true)));
  api.onLinkFile(({ name, path }) => {
    if (pendingPhoto.has(name) || [...pendingPhoto.keys()].some((k) => path.endsWith(k))) {
      pendingPhoto.clear();
      api.linkOpenReceived(path).catch(() => {});
    }
  });

  // ---------------------------------------------------------------------------
  // Apps
  // ---------------------------------------------------------------------------
  let apps = [];
  let appsLoaded = false;
  function renderApps() {
    const el = $('appGrid');
    el.innerHTML = '';
    const q = $('appSearch').value.trim().toLowerCase();
    const list = q ? apps.filter((a) => a.label.toLowerCase().includes(q)) : apps;
    if (!list.length) { el.append(empty(appsLoaded ? 'Tidak ada app.' : connected() ? 'Memuat daftar app…' : 'HP belum terhubung.')); return; }
    for (const a of list) {
      el.append(h('button', { class: 'ap', title: a.pkg, onclick: () => openApp(a) },
        a.icon ? h('img', { src: B64('image/png', a.icon), alt: '', draggable: 'false' }) : h('div', { class: 'ap-ph', html: ICON.phone }),
        h('span', {}, a.label)));
    }
  }
  async function openApp(a) {
    const dev = dashDevice();
    if (!dev || dev.state !== 'device') { toast('Butuh koneksi adb ke HP (menu Setup) untuk membuka app', true); return; }
    const r = await api.phoneOpenApp(dev.serial, a.pkg);
    if (r && r.ok) { toast(`${a.label} dibuka di HP`); tlPush(`Buka ${a.label}`, dev.name || 'adb', 'phone'); } else toast((r && r.err) || 'Gagal membuka app', true);
  }
  function loadApps() {
    if (!connected()) { renderApps(); return; }
    appsLoaded = false; apps = []; renderApps();
    send({ t: 'apps', a: 'list' });
  }
  $('appRefresh').addEventListener('click', loadApps);
  $('appSearch').addEventListener('input', renderApps);

  // ---------------------------------------------------------------------------
  // Deck
  // ---------------------------------------------------------------------------
  const DEFAULT_DECK = [
    { label: 'Play/Pause', icon: 'play', kind: 'key', value: 'play_pause' },
    { label: 'Berikutnya', icon: 'next', kind: 'key', value: 'next' },
    { label: 'Sebelumnya', icon: 'prev', kind: 'key', value: 'prev' },
    { label: 'Mute PC', icon: 'mute', kind: 'key', value: 'mute' },
    { label: 'Volume PC −', icon: 'volDn', kind: 'key', value: 'vol_down' },
    { label: 'Volume PC +', icon: 'volUp', kind: 'key', value: 'vol_up' },
    { label: 'Senter HP', icon: 'torch', kind: 'link', value: 'flashlight' },
    { label: 'DND HP', icon: 'moon', kind: 'link', value: 'dnd' },
    { label: 'Cari HP', icon: 'bell', kind: 'link', value: 'find' },
    { label: 'Mute HP', icon: 'mute', kind: 'link', value: 'mute' },
  ];
  const PRESETS = {
    key: [['play_pause', 'Play/Pause', 'play'], ['next', 'Berikutnya', 'next'], ['prev', 'Sebelumnya', 'prev'], ['stop', 'Stop', 'pause'], ['mute', 'Mute PC', 'mute'], ['vol_down', 'Volume PC −', 'volDn'], ['vol_up', 'Volume PC +', 'volUp']],
    link: [['flashlight', 'Senter HP', 'torch'], ['dnd', 'DND HP', 'moon'], ['mute', 'Mute HP', 'mute'], ['vol_down', 'Volume HP −', 'volDn'], ['vol_up', 'Volume HP +', 'volUp'], ['find', 'Cari HP', 'bell']],
  };
  let deck = DEFAULT_DECK.slice();
  let deckEdit = false;
  let findOn = false;
  const saveDeck = () => api.deckSave({ tiles: deck }).catch(() => {});

  function renderDeck() {
    const el = $('deckGrid');
    el.innerHTML = '';
    if (!deck.length) el.append(empty('Belum ada tombol. Tekan Edit untuk menambah.'));
    deck.forEach((t, i) => {
      const disabled = t.kind === 'link' && !connected();
      el.append(h('div', { class: 'dk-wrap' },
        h('button', { class: 'dk', disabled, onclick: () => runTile(t) },
          h('span', { class: 'dk-ico', html: ICON[t.icon] || (t.kind === 'url' ? ICON.link : t.kind === 'open' ? ICON.send : ICON.key) }),
          h('span', { class: 'dk-l' }, t.label)),
        deckEdit && h('button', { class: 'dk-x', title: 'Hapus', onclick: () => { deck.splice(i, 1); saveDeck(); renderDeck(); }, html: ICON.close })));
    });
    $('deckAdd').classList.toggle('hidden', !deckEdit);
    $('deckEdit').textContent = deckEdit ? 'Selesai' : 'Edit';
  }
  async function runTile(t) {
    if (t.kind === 'link') {
      if (t.value === 'find') { findOn = !findOn; await api.linkCmd('find', findOn); toast(findOn ? 'HP berbunyi' : 'Cari HP dihentikan'); return; }
      await api.linkCmd(t.value);
      return;
    }
    const r = await api.deckRun(t.kind, t.value);
    if (r && !r.ok) toast(r.err || 'Gagal', true);
  }
  function fillPresets() {
    const kind = $('deckKind').value;
    const sel = $('deckPreset');
    const custom = kind === 'url' || kind === 'open';
    sel.classList.toggle('hidden', custom);
    $('deckValue').classList.toggle('hidden', !custom);
    $('deckValue').placeholder = kind === 'url' ? 'https://…' : 'C:\\path\\app.exe atau folder';
    sel.innerHTML = '';
    for (const [v, l] of PRESETS[kind] || []) sel.append(h('option', { value: v }, l));
  }
  $('deckKind').addEventListener('change', fillPresets);
  $('deckEdit').addEventListener('click', () => { deckEdit = !deckEdit; renderDeck(); });
  $('deckReset').addEventListener('click', () => { deck = DEFAULT_DECK.slice(); saveDeck(); renderDeck(); });
  $('deckAddBtn').addEventListener('click', () => {
    const kind = $('deckKind').value;
    let label = $('deckLabel').value.trim();
    let value, icon;
    if (kind === 'url' || kind === 'open') {
      value = $('deckValue').value.trim();
      if (!value) { toast('Isi URL atau path dulu', true); return; }
      icon = kind === 'url' ? 'link' : 'send';
      label = label || (kind === 'url' ? value.replace(/^https?:\/\//, '').slice(0, 20) : value.split(/[\\/]/).pop().slice(0, 20));
    } else {
      value = $('deckPreset').value;
      const p = (PRESETS[kind] || []).find((x) => x[0] === value);
      label = label || (p ? p[1] : value);
      icon = p ? p[2] : 'key';
    }
    deck.push({ label, icon, kind, value });
    $('deckLabel').value = ''; $('deckValue').value = '';
    saveDeck(); renderDeck();
  });
  api.deckGet().then((d) => { if (d && Array.isArray(d.tiles)) { deck = d.tiles; renderDeck(); } }).catch(() => {});
  fillPresets();

  // ---------------------------------------------------------------------------
  // Kamera HP (frame JPEG lewat Link, digambar ke canvas)
  // ---------------------------------------------------------------------------
  const cam = { on: false, facing: 'back', drawing: false };
  function camUi() {
    $('camToggle').textContent = cam.on ? 'Matikan kamera' : 'Buka kamera';
    $('camBack').classList.toggle('active', cam.facing === 'back');
    $('camFront').classList.toggle('active', cam.facing === 'front');
    document.querySelector('.cam-stage').classList.toggle('live', cam.on);
    $('camEmpty').textContent = connected() ? 'Kamera mati' : 'HP belum terhubung';
  }
  const camStart = (facing) => { cam.facing = facing; camUi(); send({ t: 'cam', a: 'start', facing }); };
  const camStop = () => { send({ t: 'cam', a: 'stop' }); cam.on = false; camUi(); };
  $('camToggle').addEventListener('click', () => (cam.on ? camStop() : camStart(cam.facing)));
  $('camSwitch').addEventListener('click', () => camStart(cam.facing === 'back' ? 'front' : 'back'));
  $('camBack').addEventListener('click', () => { if (cam.on) camStart('back'); else { cam.facing = 'back'; camUi(); } });
  $('camFront').addEventListener('click', () => { if (cam.on) camStart('front'); else { cam.facing = 'front'; camUi(); } });
  $('camShot').addEventListener('click', () => {
    const cv = $('camCanvas');
    if (!cam.on) { toast('Kamera belum menyala', true); return; }
    cv.toBlob(async (blob) => {
      try {
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
        toast('Foto disalin ke clipboard PC');
      } catch (e) { toast('Gagal menyalin foto', true); }
    }, 'image/png');
  });
  api.onLinkCam((v) => {
    if (currentPage !== 'camera' || cam.drawing) return;
    cam.drawing = true;
    const img = new Image();
    img.onload = () => {
      const cv = $('camCanvas');
      const rot = v.rot % 4, w = img.width, hh = img.height, swap = rot % 2 === 1;
      cv.width = swap ? hh : w; cv.height = swap ? w : hh;
      const c = cv.getContext('2d');
      c.save();
      if (v.mirror) { c.translate(cv.width, 0); c.scale(-1, 1); }
      c.translate(cv.width / 2, cv.height / 2);
      c.rotate(rot * Math.PI / 2);
      c.drawImage(img, -w / 2, -hh / 2);
      c.restore();
      if (!cam.on) { cam.on = true; camUi(); }
      cam.drawing = false;
    };
    img.onerror = () => { cam.drawing = false; };
    img.src = 'data:image/jpeg;base64,' + v.jpg;
  });
  function camState(v) {
    cam.on = !!v.on;
    if (v.facing) cam.facing = v.facing;
    let msg = '';
    if (v.err === 'perm') msg = 'Izin kamera belum diberikan di HP. Buka app Link di HP → bagian Izin → Kamera.';
    else if (v.err === 'none') msg = 'HP tidak punya kamera itu.';
    else if (v.err) msg = 'Kamera gagal: ' + v.err;
    note('camNote', msg);
    camUi();
  }
  function camPage() {
    if (!connected()) note('camNote', 'HP belum terhubung lewat Link. Buka menu Link HP untuk menyambungkan.');
    else if (caps && caps.camera === false) note('camNote', 'Izin kamera belum diberikan di HP. Buka app Link di HP → bagian Izin → Kamera.');
    else note('camNote', '');
    camUi();
  }

  // ---------------------------------------------------------------------------
  // Pesan dari HP
  // ---------------------------------------------------------------------------
  api.onLinkMsg((v) => {
    switch (v.t) {
      case 'caps':
        caps = v;
        if (currentPage === 'sms') pageNote('smsNote', 'sms');
        if (currentPage === 'calls') pageNote('callNote', 'callLog');
        if (currentPage === 'photos') pageNote('photoNote', 'photos');
        if (currentPage === 'camera') camPage();
        break;
      case 'media': onPhoneMedia(v); break;
      case 'cam-state': camState(v); break;
      case 'sms-threads':
        sms.threads = v.list || []; sms.canSend = v.canSend !== false;
        renderSmsList();
        if (v.canSend === false) note('smsNote', 'Izin kirim SMS belum diberikan di HP — pesan masuk tetap terbaca.');
        break;
      case 'sms-thread':
        if (v.tid === sms.tid) { sms.msgs = v.msgs || []; sms.address = v.address || sms.address; sms.name = v.name || sms.name; renderSmsMsgs(); }
        break;
      case 'sms-changed': if (currentPage === 'sms') refreshSms(); break;
      case 'sms-sent': break;
      case 'call-log': callLog = v.list || []; renderCalls(); break;
      case 'call-state': onCallState(v); break;
      case 'photos':
        if (v.first) photos = [];
        photos = photos.concat(v.list || []);
        if (v.last) photoLoading = false;
        if (currentPage === 'photos') renderPhotos();
        break;
      case 'apps':
        if (v.first) apps = [];
        apps = apps.concat(v.list || []);
        if (v.last) appsLoaded = true;
        if (currentPage === 'apps') renderApps();
        break;
      default: break;
    }
  });

  let wasConnected = false;
  api.onLinkState((s) => {
    if (s.connected && !wasConnected) {
      send({ t: 'caps' });
      if (currentPage === 'sms') refreshSms();
      if (currentPage === 'calls') send({ t: 'calls', a: 'log' });
      if (currentPage === 'photos') loadPhotos();
      if (currentPage === 'apps') loadApps();
    }
    if (!s.connected && wasConnected) {
      media.phone = { has: false };
      cam.on = false; camUi();
      photos = []; apps = []; appsLoaded = false; callLog = []; sms.threads = []; sms.msgs = []; caps = null;
      document.getElementById('callBar').classList.add('hidden');
      renderMedia(); renderSmsList(); renderSmsMsgs(); renderCalls(); renderPhotos(); renderApps(); renderDeck();
    }
    wasConnected = !!s.connected;
    for (const k of Object.keys(optimistic)) if (s[k] != null && !!s[k] === optimistic[k].v) delete optimistic[k];
    if (currentPage === 'deck') renderDeck();
  });
  if (connected()) { wasConnected = true; send({ t: 'caps' }); }

  // ---------------------------------------------------------------------------
  // Dipanggil showPage() di renderer.js
  // ---------------------------------------------------------------------------
  window.featurePage = (name) => {
    if (name !== 'camera' && cam.on) camStop(); // hemat baterai HP: kamera mati saat halaman ditinggalkan
    if (name === 'camera') { camPage(); send({ t: 'caps' }); send({ t: 'cam', a: 'state' }); }
    if (name === 'sms') { pageNote('smsNote', 'sms'); renderSmsList(); renderSmsMsgs(); refreshSms(); send({ t: 'caps' }); }
    else if (name === 'calls') { pageNote('callNote', 'callLog'); renderCalls(); send({ t: 'calls', a: 'log' }); send({ t: 'caps' }); }
    else if (name === 'photos') { pageNote('photoNote', 'photos'); renderPhotos(); if (!photos.length) loadPhotos(); send({ t: 'caps' }); }
    else if (name === 'apps') { renderApps(); if (!apps.length) loadApps(); }
    else if (name === 'deck') { renderDeck(); }
    else if (name === 'home') { renderMedia(); }
  };
  renderSmsList(); renderSmsMsgs(); renderCalls(); renderPhotos(); renderApps(); renderDeck(); renderMedia();
})();
