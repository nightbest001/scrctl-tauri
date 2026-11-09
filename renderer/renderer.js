// =============================================================================
// SCRCTL — halaman utama (Beranda / Setup / Setelan / Log)
// =============================================================================
const $ = (id) => document.getElementById(id);

// Helper bikin elemen tanpa innerHTML (nama device dari HP tidak dipercaya).
function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === false || v == null) continue;
    if (k === 'class') el.className = v;
    else if (k === 'html') el.innerHTML = v; // hanya untuk SVG statis di bawah
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    el.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return el;
}

const ICON = {
  phone: '<svg viewBox="0 0 24 24"><rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>',
  wifi: '<svg viewBox="0 0 24 24"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>',
  key: '<svg viewBox="0 0 24 24"><circle cx="8" cy="15" r="4"/><path d="m10.8 12.2 9.2-9.2M16 7l3 3M14 9l2 2"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
};

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
let savedDevices = [];          // { serial, ip, port, name }
let scan = { devices: [], services: [], adbOk: true };
let selectedSerial = null;
let mirroringSerial = null;
let busySerial = null;          // device yang sedang proses connect/mulai
let searchTerm = '';
let settings = null;
let settingsDefaults = null;
let scanning = false;
let currentPage = 'home';

// ---------------------------------------------------------------------------
// Util UI
// ---------------------------------------------------------------------------
let toastTimer = null;
function toast(text, isErr) {
  const el = $('toast');
  el.textContent = text;
  el.className = 'toast show' + (isErr ? ' err' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3200);
}

function addLog(text, isErr) {
  const logEl = $('log');
  const t = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const line = h('div', { class: isErr ? 'err' : '' }, `[${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}] ${text}`);
  logEl.appendChild(line);
  while (logEl.childElementCount > 500) logEl.firstChild.remove();
  logEl.scrollTop = logEl.scrollHeight;
  if (isErr && currentPage !== 'log') $('logDot').classList.remove('hidden');
}

window.api.onLog(({ text, isErr }) => addLog(text, isErr));
window.api.onMirrorStopped(() => {
  addLog('mirror dihentikan');
  mirroringSerial = null;
  render();
});

$('clearLog').addEventListener('click', () => { $('log').innerHTML = ''; });

// Window controls
$('winMin').addEventListener('click', () => window.api.windowMinimize());
$('winMax').addEventListener('click', () => window.api.windowMaximize());
$('winClose').addEventListener('click', () => window.api.windowClose());

// ---------------------------------------------------------------------------
// Navigasi
// ---------------------------------------------------------------------------
const PAGE_TITLES = { home: 'Beranda', notif: 'Notifikasi', setup: 'Setup', settings: 'Setelan', log: 'Log' };

function showPage(name) {
  currentPage = name;
  document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.page === name));
  document.querySelectorAll('.page').forEach((p) => p.classList.add('hidden'));
  $('page-' + name).classList.remove('hidden');
  $('pageTitle').textContent = PAGE_TITLES[name];
  $('searchWrap').style.visibility = name === 'home' ? 'visible' : 'hidden';
  if (name === 'log') $('logDot').classList.add('hidden');
  if (name === 'settings') renderSettings();
  if (name === 'notif') { unreadNotif = 0; renderNotifs(); }
  updateNotifBadge();
}
document.querySelectorAll('.nav-item').forEach((b) => b.addEventListener('click', () => showPage(b.dataset.page)));

$('search').addEventListener('input', (e) => { searchTerm = e.target.value.trim().toLowerCase(); renderGrid(); });
$('wdBannerBtn').addEventListener('click', () => showPage('setup'));
$('pillStop').addEventListener('click', async () => {
  await window.api.stopMirror();
  mirroringSerial = null;
  render();
});

// ---------------------------------------------------------------------------
// Data device
// ---------------------------------------------------------------------------
function parseIpPort(raw, defaultPort) {
  const trimmed = raw.trim();
  const idx = trimmed.lastIndexOf(':');
  if (idx === -1) return { ip: trimmed, port: defaultPort };
  return { ip: trimmed.slice(0, idx), port: trimmed.slice(idx + 1) || defaultPort };
}

// Data lama bisa berbentuk { ip, label } — normalkan ke { serial, ip, port, name }.
function migrateDevice(d) {
  if (d.serial && d.port) return d;
  const { ip, port } = parseIpPort(String(d.ip || ''), '5555');
  return { serial: `${ip}:${port}`, ip, port: String(port), name: d.name || d.label || null };
}

async function persistDevices() { await window.api.saveDevices(savedDevices); }

function connectService(ip) { return scan.services.find((s) => s.kind === 'connect' && s.ip === ip); }
function adbState(serial) { const d = scan.devices.find((x) => x.serial === serial); return d ? d.state : null; }

// Daftar yang ditampilkan: device tersimpan + device USB / wireless yang
// dikenal adb tapi belum disimpan.
function allDevices() {
  const list = savedDevices.map((d) => {
    const svc = connectService(d.ip);
    return { ...d, saved: true, usb: false, wdOn: !!svc, livePort: svc ? svc.port : null, state: adbState(d.serial) };
  });
  const knownNames = new Set(savedDevices.map((d) => d.name).filter(Boolean));
  for (const ad of scan.devices) {
    if (list.some((d) => d.serial === ad.serial)) continue;
    if (ad.state !== 'device') continue;
    const isMdnsAuto = ad.serial.startsWith('adb-');
    if (isMdnsAuto && ad.model && knownNames.has(ad.model)) continue; // duplikat dari device tersimpan
    list.push({
      serial: ad.serial, ip: '', port: '', name: ad.model || ad.serial,
      saved: false, usb: !isMdnsAuto && !ad.serial.includes(':'), wdOn: isMdnsAuto, livePort: null, state: ad.state,
    });
  }
  return list;
}

// ---------------------------------------------------------------------------
// Render Beranda
// ---------------------------------------------------------------------------
function render() {
  renderBanner();
  renderDetected();
  renderGrid();
  renderSetupDetected();

  const mirroring = !!mirroringSerial;
  const dev = allDevices().find((d) => d.serial === mirroringSerial);
  $('mirrorPill').classList.toggle('on', mirroring);
  $('mirrorPillText').textContent = mirroring ? `Mirror: ${dev ? dev.name || dev.serial : mirroringSerial}` : 'Tidak ada mirror';
  $('pillStop').classList.toggle('hidden', !mirroring);
}

function renderBanner() {
  const connectSvcs = scan.services.filter((s) => s.kind === 'connect');
  const banner = $('wdBanner');
  const hasAuto = scan.devices.some((d) => d.state === 'device' && d.serial.startsWith('adb-'));
  if (!scan.adbOk) {
    banner.classList.remove('ok');
    $('wdBanner').querySelector('.banner-title').textContent = 'adb tidak ditemukan';
    $('wdBannerSub').textContent = 'Pasang Android Platform Tools dan pastikan perintah "adb" bisa dipanggil dari terminal.';
    $('wdBannerBtn').classList.add('hidden');
    return;
  }
  $('wdBannerBtn').classList.remove('hidden');
  if (connectSvcs.length || hasAuto) {
    banner.classList.add('ok');
    banner.querySelector('.banner-title').textContent = `Wireless debugging aktif di ${Math.max(connectSvcs.length, 1)} device`;
    $('wdBannerSub').textContent = 'Klik device di bagian "Terdeteksi di jaringan" untuk menambahkan, atau langsung Mirror dari daftar device kamu.';
    $('wdBannerBtn').textContent = 'Tambah manual';
  } else {
    banner.classList.remove('ok');
    banner.querySelector('.banner-title').textContent = 'Wireless debugging belum terdeteksi';
    $('wdBannerSub').textContent = 'Nyalakan Developer options → Wireless debugging di HP dan pastikan satu WiFi dengan komputer ini.';
    $('wdBannerBtn').textContent = 'Buka Setup';
  }
}

function renderDetected() {
  const row = $('detectedRow');
  row.innerHTML = '';
  const items = [];
  for (const s of scan.services) {
    if (s.kind === 'connect') {
      const saved = savedDevices.find((d) => d.ip === s.ip);
      if (saved) continue; // sudah ada di "Device kamu"
      items.push({ s, label: s.ip, sub: `:${s.port}`, kind: 'connect' });
    } else {
      items.push({ s, label: s.ip, sub: 'mode pairing', kind: 'pairing' });
    }
  }
  if (items.length === 0) {
    row.append(h('div', { class: 'empty-note' }, 'Belum ada device baru yang terdeteksi. Buka Wireless debugging di HP (satu WiFi dengan komputer ini).'));
    return;
  }
  for (const it of items) {
    row.append(h('button', {
      class: 'avatar' + (it.kind === 'pairing' ? ' pairing' : ''),
      title: it.kind === 'pairing' ? 'Isi form pairing' : 'Tambah & hubungkan',
      onclick: () => (it.kind === 'pairing' ? goPair(it.s) : addDetected(it.s)),
    },
    h('div', { class: 'circle', html: it.kind === 'pairing' ? ICON.key : ICON.phone }),
    h('div', { class: 'a-name' }, it.label),
    h('div', { class: 'a-sub' }, it.sub)));
  }
}

function renderGrid() {
  const grid = $('deviceGrid');
  grid.innerHTML = '';
  const list = allDevices().filter((d) => {
    if (!searchTerm) return true;
    return `${d.name || ''} ${d.ip} ${d.serial}`.toLowerCase().includes(searchTerm);
  });

  if (list.length === 0) {
    grid.style.display = 'block';
    grid.append(h('div', { class: 'empty-note' }, savedDevices.length === 0
      ? 'Belum ada device. Tambahkan lewat menu Setup, atau klik device yang terdeteksi di atas.'
      : 'Tidak ada device yang cocok dengan pencarian.'));
    return;
  }
  grid.style.display = '';

  for (const d of list) {
    const online = d.state === 'device';
    const mirroring = d.serial === mirroringSerial;
    const busy = d.serial === busySerial;
    const sub = d.usb ? 'USB' : (d.ip ? `${d.ip}:${d.wdOn && d.livePort ? d.livePort : d.port}` : d.serial);
    const status = mirroring ? 'Sedang mirror' : online ? 'Terhubung' : d.wdOn ? 'Wireless debugging ON' : 'Offline';

    const tile = h('div', { class: 'tile' + (d.serial === selectedSerial ? ' selected' : ''), onclick: () => { selectedSerial = d.serial; renderGrid(); } },
      h('div', { class: 'thumb' + (online || d.wdOn ? '' : ' offline'), html: ICON.phone },
        h('div', { class: 'thumb-badges' },
          mirroring && h('span', { class: 'tag green' }, 'LIVE'),
          d.usb && h('span', { class: 'tag blue' }, 'USB'),
          !mirroring && d.wdOn && h('span', { class: 'tag amber' }, 'WD ON')),
        d.saved && h('button', { class: 'del', title: 'Hapus dari daftar', html: ICON.close, onclick: (e) => { e.stopPropagation(); removeDevice(d.serial); } })),
      h('div', { class: 'tile-title' }, d.name || d.ip || d.serial),
      h('div', { class: 'tile-sub' }, h('span', { class: 'dot' + (online ? ' on' : d.wdOn ? ' warn' : '') }), `${status} • ${sub}`),
      h('div', { class: 'tile-actions' },
        mirroring
          ? h('button', { class: 'btn danger', onclick: async (e) => { e.stopPropagation(); await window.api.stopMirror(); mirroringSerial = null; render(); } }, 'Stop')
          : h('button', { class: 'btn green', disabled: busy || !(online || d.wdOn || d.saved), onclick: (e) => { e.stopPropagation(); startMirrorFor(d); } }, busy ? 'Menghubungkan…' : 'Mirror'),
        (online && !d.usb && d.ip) && h('button', { class: 'btn sq', title: 'Putuskan adb', onclick: async (e) => { e.stopPropagation(); await disconnectDevice(d); } }, 'Putus')));
    grid.append(tile);
  }
}

function renderSetupDetected() {
  const box = $('setupDetected');
  box.innerHTML = '';
  if (scan.services.length === 0) {
    box.append(h('span', { class: 'muted' }, 'Belum ada. Nyalakan Wireless debugging di HP.'));
    return;
  }
  for (const s of scan.services) {
    box.append(h('button', {
      class: 'chip' + (s.kind === 'pairing' ? ' pair' : ''),
      onclick: () => (s.kind === 'pairing' ? goPair(s) : (($('ip').value = `${s.ip}:${s.port}`), $('ip').focus())),
    }, h('span', { class: 'dot' }), `${s.ip}:${s.port}`, h('span', { class: 'muted', style: 'font-size:11px' }, s.kind === 'pairing' ? 'pairing' : 'connect')));
  }
}

function goPair(s) {
  showPage('setup');
  $('pairIp').value = `${s.ip}:${s.port}`;
  $('pairCode').focus();
}

// ---------------------------------------------------------------------------
// Notifikasi HP (dibaca main process lewat adb dumpsys, lihat main.js)
// ---------------------------------------------------------------------------
let notifs = [];
let unreadNotif = 0;
try { notifs = JSON.parse(localStorage.getItem('scrctl.notifs') || '[]'); } catch (e) { notifs = []; }

function saveNotifs() {
  try { localStorage.setItem('scrctl.notifs', JSON.stringify(notifs.slice(0, 200))); } catch (e) { /* ignore */ }
}

function updateNotifBadge() {
  const b = $('notifBadge');
  b.textContent = unreadNotif > 99 ? '99+' : String(unreadNotif);
  b.classList.toggle('hidden', unreadNotif === 0);
}

function timeAgo(ts) {
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (s < 60) return 'baru saja';
  if (s < 3600) return `${Math.floor(s / 60)} mnt lalu`;
  if (s < 86400) return `${Math.floor(s / 3600)} jam lalu`;
  return new Date(ts).toLocaleDateString('id-ID', { day: 'numeric', month: 'short' });
}

function renderNotifs() {
  const list = $('notifList');
  list.innerHTML = '';
  const on = !settings || settings.notifWatch;
  $('notifHint').textContent = on
    ? 'Notifikasi baru dari HP yang terhubung muncul di sini (dicek tiap ±4 detik).'
    : 'Pemantauan notifikasi dimatikan — aktifkan di Setelan → Notifikasi.';
  if (notifs.length === 0) {
    list.append(h('div', { class: 'empty-note' }, on ? 'Belum ada notifikasi. Hubungkan HP, lalu notifikasi yang masuk akan tampil di sini.' : 'Tidak ada notifikasi.'));
    return;
  }
  for (const n of notifs) {
    list.append(h('div', { class: 'notif' + (n.fresh ? ' fresh' : '') },
      h('div', { class: 'n-ico' }, (n.app || '?').charAt(0).toUpperCase()),
      h('div', { class: 'n-main' },
        h('div', { class: 'n-meta' }, h('b', {}, n.app), h('span', {}, '•'), h('span', {}, n.device), h('span', {}, '•'), h('span', {}, timeAgo(n.ts))),
        n.title && h('div', { class: 'n-title' }, n.title),
        n.text && h('div', { class: 'n-text' }, n.text)),
      h('button', { class: 'n-x', title: 'Hapus', html: ICON.close, onclick: () => { notifs = notifs.filter((x) => x !== n); saveNotifs(); renderNotifs(); } })));
  }
}

window.api.onNotification((n) => {
  n.fresh = true;
  notifs.unshift(n);
  notifs = notifs.slice(0, 200);
  if (currentPage !== 'notif') { unreadNotif++; updateNotifBadge(); } else { renderNotifs(); }
  saveNotifs();
  toast(`${n.app}: ${n.title || n.text}`.slice(0, 90));
  if (settings && settings.notifDesktop && (!document.hasFocus() || currentPage !== 'notif')) {
    try { new Notification(`${n.app} • ${n.device}`, { body: [n.title, n.text].filter(Boolean).join('\n') }); } catch (e) { /* ignore */ }
  }
});

$('clearNotif').addEventListener('click', () => { notifs = []; unreadNotif = 0; saveNotifs(); updateNotifBadge(); renderNotifs(); });
setInterval(() => { if (currentPage === 'notif') renderNotifs(); }, 30000);

// Beri tahu main device mana yang online supaya notifikasinya dipantau.
function syncNotifWatch() {
  const online = allDevices().filter((d) => d.state === 'device').map((d) => ({ serial: d.serial, name: d.name }));
  window.api.watchNotifications(online);
}

// ---------------------------------------------------------------------------
// Aksi device
// ---------------------------------------------------------------------------
async function connectAndVerify(ip, port) {
  addLog(`connecting to ${ip}:${port}…`);
  const res = await window.api.adbConnect(ip, port);
  if (!res.success) {
    addLog(`gagal connect: ${res.raw}`, true);
    toast(res.unreachable ? `${ip}:${port} tidak terjangkau (beda WiFi / HP mati?)` : `Gagal connect ke ${ip}:${port}`, true);
    return null;
  }
  if (res.deviceName) addLog(`terverifikasi: ${res.deviceName} (${res.serial})`);
  else addLog(`connected ke ${res.serial}, tapi nama device tidak terbaca`, true);
  return res;
}

// Port Wireless debugging berubah tiap dinyalakan ulang — ganti entri lama.
function upsertDevice({ serial, ip, port, name }, replaceSerial) {
  const idx = savedDevices.findIndex((d) => d.serial === (replaceSerial || serial));
  const entry = { serial, ip, port: String(port), name: name || (idx >= 0 ? savedDevices[idx].name : null) };
  if (idx >= 0) savedDevices[idx] = entry; else savedDevices.push(entry);
  persistDevices();
}

async function addDetected(svc) {
  const res = await connectAndVerify(svc.ip, svc.port);
  if (!res) return;
  upsertDevice({ serial: res.serial, ip: svc.ip, port: svc.port, name: res.deviceName });
  selectedSerial = res.serial;
  toast(`${res.deviceName || res.serial} ditambahkan`);
  await refreshScan();
}

async function startMirrorFor(d) {
  busySerial = d.serial;
  render();
  try {
    let serial = d.serial;
    let name = d.name;

    if (!d.usb && d.ip) {
      const port = d.livePort || d.port; // pakai port terbaru dari mDNS kalau ada
      if (d.state !== 'device' || String(port) !== String(d.port)) {
        const res = await connectAndVerify(d.ip, port);
        if (!res) return;
        serial = res.serial;
        name = res.deviceName || name;
        upsertDevice({ serial, ip: d.ip, port, name }, d.serial);
        if (selectedSerial === d.serial) selectedSerial = serial;
      }
    }

    addLog('membuka window mirror…');
    const res2 = await window.api.startMirror(serial, name);
    if (res2.success) {
      mirroringSerial = serial;
    } else {
      toast('Gagal memulai mirror — cek tab Log', true);
      addLog('gagal membuka mirror', true);
    }
  } finally {
    busySerial = null;
    await refreshScan();
  }
}

async function disconnectDevice(d) {
  if (mirroringSerial === d.serial) { await window.api.stopMirror(); mirroringSerial = null; }
  await window.api.adbDisconnect(d.serial);
  addLog(`disconnected: ${d.serial}`);
  await refreshScan();
}

async function removeDevice(serial) {
  const d = savedDevices.find((x) => x.serial === serial);
  const ok = await window.api.confirmDialog({
    title: 'Hapus device',
    message: `Hapus ${d && d.name ? d.name : serial} dari daftar?`,
    detail: 'Device bisa ditambahkan lagi kapan saja lewat Setup.',
  });
  if (!ok) return;
  savedDevices = savedDevices.filter((x) => x.serial !== serial);
  await persistDevices();
  if (selectedSerial === serial) selectedSerial = null;
  if (mirroringSerial === serial) { await window.api.stopMirror(); mirroringSerial = null; }
  await window.api.adbDisconnect(serial);
  addLog(`device ${serial} dihapus dari daftar`);
  await refreshScan();
}

// ---------------------------------------------------------------------------
// Setup: pair & tambah device
// ---------------------------------------------------------------------------
$('addBtn').addEventListener('click', async () => {
  const raw = $('ip').value.trim();
  if (!raw) { toast('Isi IP:Port dulu', true); return; }
  const { ip, port } = parseIpPort(raw, '5555');
  $('addBtn').disabled = true;
  const res = await connectAndVerify(ip, port);
  $('addBtn').disabled = false;
  if (!res) return;
  upsertDevice({ serial: res.serial, ip, port, name: res.deviceName });
  selectedSerial = res.serial;
  $('ip').value = '';
  toast(`${res.deviceName || res.serial} ditambahkan`);
  await refreshScan();
  showPage('home');
});

$('pairBtn').addEventListener('click', async () => {
  const raw = $('pairIp').value.trim();
  const code = $('pairCode').value.trim();
  if (!raw.includes(':') || !code) { toast('Isi IP:PORT pairing dan kode 6 digit', true); return; }
  const { ip, port } = parseIpPort(raw, null);
  $('pairBtn').disabled = true;
  addLog(`pairing ke ${ip}:${port}…`);
  const res = await window.api.pairDevice(ip, port, code);
  $('pairBtn').disabled = false;
  if (res.success) {
    addLog(`pairing berhasil dengan ${ip}`);
    toast('Pairing berhasil — sekarang tambahkan device (langkah 2)');
    $('pairIp').value = '';
    $('pairCode').value = '';
    const svc = connectService(ip);
    $('ip').value = svc ? `${ip}:${svc.port}` : ip + ':';
    $('ip').focus();
    refreshScan();
  } else {
    addLog(`pairing gagal: ${res.raw}`, true);
    toast('Pairing gagal — cek kode & port', true);
  }
});

// ---------------------------------------------------------------------------
// Setelan (mengikuti opsi scrcpy)
// ---------------------------------------------------------------------------
const MBPS = (n) => ({ value: n, label: `${n} Mbps` });
const SETTINGS_SCHEMA = [
  {
    title: 'Video',
    items: [
      { key: 'maxSize', type: 'select', flag: '--max-size', label: 'Resolusi maksimum', desc: 'Sisi terpanjang layar. Kecil = lebih ringan dan minim lag.',
        options: [{ value: 0, label: 'Asli' }, 480, 720, 1024, 1280, 1600, 1920].map((o) => (typeof o === 'number' ? { value: o, label: `${o} px` } : o)) },
      { key: 'maxFps', type: 'select', flag: '--max-fps', label: 'Batas FPS', desc: 'Batasi frame per detik dari HP.',
        options: [{ value: 0, label: 'Tanpa batas' }, ...[15, 24, 30, 60, 90, 120].map((n) => ({ value: n, label: `${n} fps` }))] },
      { key: 'videoBitRate', type: 'select', flag: '--video-bit-rate', label: 'Bitrate video', desc: 'Naikkan kalau gambar pecah, turunkan kalau WiFi lemot.',
        options: [2, 4, 8, 12, 16, 24, 32].map(MBPS) },
      { key: 'captureOrientation', type: 'select', flag: '--capture-orientation', label: 'Orientasi capture', desc: 'Kunci atau putar hasil capture (@ = kunci ke orientasi awal).',
        options: [{ value: '', label: 'Otomatis' }, { value: '@', label: 'Kunci awal' }, { value: '0', label: '0°' }, { value: '90', label: '90°' }, { value: '180', label: '180°' }, { value: '270', label: '270°' }, { value: '@90', label: 'Kunci 90°' }] },
      { key: 'crop', type: 'text', flag: '--crop', label: 'Crop', desc: 'Format lebar:tinggi:x:y, kosongkan untuk layar penuh.', placeholder: '1080:1920:0:0' },
      { key: 'displayId', type: 'number', flag: '--display-id', label: 'Display ID', desc: '0 = layar utama. Isi lain untuk layar sekunder.' },
    ],
  },
  {
    title: 'Audio & kontrol',
    items: [
      { key: 'audio', type: 'toggle', invert: true, flag: '--no-audio', label: 'Matikan audio', desc: 'Aktif = HP tidak mengirim suara (default). Matikan toggle ini untuk mendengar audio HP — butuh Android 11+.' },
      { key: 'audioSource', type: 'select', flag: '--audio-source', label: 'Sumber audio', desc: 'output = suara yang diputar HP, mic = mikrofon HP.', needs: (s) => s.audio,
        options: [{ value: 'output', label: 'Output (suara HP)' }, { value: 'playback', label: 'Playback (Android 13+)' }, { value: 'mic', label: 'Mikrofon' }] },
      { key: 'control', type: 'toggle', invert: true, flag: '--no-control', label: 'Hanya lihat (no-control)', desc: 'Aktif = mirror view-only. Sentuh, keyboard, clipboard, dan drag-drop dinonaktifkan.' },
      { key: 'clipboardSync', type: 'toggle', flag: '--no-clipboard-autosync', invertFlag: true, label: 'Sinkron clipboard', desc: 'Salin di komputer atau HP, otomatis tersedia di sisi lain.', needs: (s) => s.control },
      { key: 'keyboardUhid', type: 'toggle', flag: '--keyboard=uhid', label: 'Keyboard asli (UHID)', desc: 'HP mengenali keyboard fisik sungguhan: semua tombol, Ctrl/Alt/Shift, dan layout bekerja seperti keyboard USB. Butuh kontrol aktif.', needs: (s) => s.control },
      { key: 'mouseUhid', type: 'toggle', flag: '--mouse=uhid', label: 'Mouse asli (UHID)', desc: 'Kursor mouse muncul di layar HP. Klik di jendela mirror untuk menangkap mouse, tekan Esc untuk melepas. Butuh kontrol aktif.', needs: (s) => s.control },
    ],
  },
  {
    title: 'Notifikasi',
    items: [
      { key: 'notifWatch', type: 'toggle', flag: 'adb dumpsys', label: 'Pantau notifikasi HP', desc: 'Baca notifikasi baru dari HP yang online dan tampilkan di tab Notifikasi. Jalan tanpa perlu mirror.' },
      { key: 'notifDesktop', type: 'toggle', flag: 'desktop', label: 'Popup di desktop', desc: 'Munculkan notifikasi Windows saat ada notifikasi baru dari HP.', needs: (s) => s.notifWatch },
    ],
  },
  {
    title: 'Device',
    items: [
      { key: 'showTouches', type: 'toggle', flag: '--show-touches', label: 'Tampilkan sentuhan', desc: 'Tampilkan titik sentuhan di layar HP selama mirror.' },
      { key: 'stayAwake', type: 'toggle', flag: '--stay-awake', label: 'Jaga layar tetap menyala', desc: 'Layar HP tidak mati selama kabel/ADB terhubung.' },
      { key: 'turnScreenOff', type: 'toggle', flag: '--turn-screen-off', label: 'Matikan layar HP saat mirror', desc: 'Mirror tetap jalan, layar fisik HP gelap. Butuh kontrol aktif.', needs: (s) => s.control },
      { key: 'powerOffOnClose', type: 'toggle', flag: '--power-off-on-close', label: 'Matikan layar saat selesai', desc: 'Layar HP dikunci saat mirror ditutup.' },
      { key: 'powerOn', type: 'toggle', flag: '--no-power-on', invertFlag: true, label: 'Nyalakan layar saat mulai', desc: 'Bangunkan HP otomatis ketika mirror dimulai.' },
      { key: 'downsizeOnError', type: 'toggle', flag: '--no-downsize-on-error', invertFlag: true, label: 'Kecilkan otomatis kalau encoder gagal', desc: 'Coba resolusi lebih kecil kalau HP menolak resolusi yang diminta.' },
    ],
  },
];

const CONTROL_HELP = [
  ['Klik kiri / drag', 'Tap / swipe'],
  ['Scroll mouse / touchpad', 'Scroll di HP (Shift + scroll = horizontal)'],
  ['Klik kanan', 'Back'],
  ['Klik tengah', 'Home'],
  ['Tombol mouse samping', 'Back / Recent apps'],
  ['Ketik di keyboard', 'Teks langsung ke HP (Enter, Backspace, panah, F1–F12, Ctrl+A/Z dll ikut)'],
  ['Ctrl+V', 'Tempel clipboard komputer ke HP'],
  ['Ctrl+C / Ctrl+X', 'Copy / cut dari HP ke clipboard komputer'],
  ['Alt+H / Alt+B / Alt+S', 'Home / Back / Recent apps'],
  ['Alt+↑ / Alt+↓', 'Volume + / −'],
  ['Alt+N / Alt+R / Alt+O', 'Panel notifikasi / Putar layar / Matikan layar HP'],
  ['Alt+P / Alt+F', 'Power / Fullscreen'],
];

let saveTimer = null;
function saveSettingsSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    settings = await window.api.setSettings(settings);
    toast('Setelan tersimpan');
  }, 350);
}

function renderSettings() {
  const root = $('settingsRoot');
  root.innerHTML = '';
  if (!settings) return;

  for (const group of SETTINGS_SCHEMA) {
    const box = h('div', { class: 'group' }, h('div', { class: 'group-title' }, group.title));
    for (const it of group.items) {
      const enabled = it.needs ? it.needs(settings) : true;
      let ctl;
      if (it.type === 'toggle') {
        const checked = it.invert ? !settings[it.key] : !!settings[it.key];
        const input = h('input', { type: 'checkbox' });
        input.checked = checked;
        input.addEventListener('change', () => {
          settings[it.key] = it.invert ? !input.checked : input.checked;
          saveSettingsSoon();
          renderSettings();
        });
        ctl = h('label', { class: 'switch' }, input, h('span', { class: 'track' }));
      } else if (it.type === 'select') {
        const sel = h('select', {}, it.options.map((o) => h('option', { value: String(o.value) }, o.label)));
        sel.value = String(settings[it.key]);
        if (sel.value !== String(settings[it.key])) sel.value = String(it.options[0].value);
        sel.addEventListener('change', () => {
          const numeric = typeof it.options[0].value === 'number';
          settings[it.key] = numeric ? Number(sel.value) : sel.value;
          saveSettingsSoon();
        });
        ctl = sel;
      } else {
        const input = h('input', { type: it.type, placeholder: it.placeholder || '', spellcheck: 'false' });
        input.value = settings[it.key] ?? '';
        input.addEventListener('input', () => {
          settings[it.key] = it.type === 'number' ? Math.max(0, parseInt(input.value, 10) || 0) : input.value;
          saveSettingsSoon();
        });
        ctl = input;
      }
      box.append(h('div', { class: 'row' + (enabled ? '' : ' disabled') },
        h('div', { class: 'r-text' },
          h('div', { class: 'r-label' }, it.label, h('span', { class: 'flag' }, it.flag)),
          h('div', { class: 'r-desc' }, it.desc)),
        h('div', { class: 'r-ctl' }, ctl)));
    }
    root.append(box);
  }

  const help = h('div', { class: 'group' }, h('div', { class: 'group-title' }, 'Kontrol mouse & keyboard (di window mirror)'));
  for (const [k, v] of CONTROL_HELP) {
    help.append(h('div', { class: 'row' }, h('div', { class: 'r-text' }, h('div', { class: 'r-label' }, k)), h('div', { class: 'r-ctl', style: 'width:auto;max-width:60%;text-align:right;color:var(--text-2);font-size:13px' }, v)));
  }
  root.append(help);
}

$('resetSettings').addEventListener('click', async () => {
  settings = await window.api.setSettings(settingsDefaults);
  renderSettings();
  toast('Setelan dikembalikan ke default');
});

// ---------------------------------------------------------------------------
// Scan berkala: device yang dikenal adb + Wireless debugging yang ON (mDNS)
// ---------------------------------------------------------------------------
async function refreshScan() {
  if (scanning) return;
  scanning = true;
  try {
    scan = await window.api.adbScan();
  } catch (e) {
    addLog(`scan gagal: ${e.message}`, true);
  }
  scanning = false;
  render();
  syncNotifWatch();
}

// ---------------------------------------------------------------------------
// Cek update (GitHub Releases) — jalan sekali tiap app dibuka
// ---------------------------------------------------------------------------
const UPDATE_REPO = 'nightbest001/scrctl-tauri';
function verParts(v) { return String(v).replace(/^v/i, '').split('-')[0].split('.').map((n) => parseInt(n, 10) || 0); }
function isNewerVer(a, b) {
  const x = verParts(a), y = verParts(b);
  for (let i = 0; i < 3; i++) {
    if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  }
  return false;
}
async function checkUpdate() {
  try {
    const cur = await window.api.getAppVersion();
    $('navVer').textContent = 'v' + cur;
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 8000);
    const r = await fetch(`https://api.github.com/repos/${UPDATE_REPO}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }, signal: ctl.signal
    });
    clearTimeout(t);
    if (!r.ok) return; // repo private / offline / belum ada release -> diam saja
    const rel = await r.json();
    if (!rel || !rel.tag_name || !isNewerVer(rel.tag_name, cur)) return;
    try { if (localStorage.getItem('scrctl.skipUpdate') === rel.tag_name) return; } catch (e) {}
    $('updateTitle').textContent = `Versi baru tersedia: ${rel.tag_name}`;
    $('updateSub').textContent = `Kamu pakai v${cur}. Download versi terbaru di GitHub Releases.`;
    $('updateGo').onclick = () => { window.api.openUrl(rel.html_url).catch(() => toast('Gagal buka browser', true)); };
    $('updateLater').onclick = () => {
      try { localStorage.setItem('scrctl.skipUpdate', rel.tag_name); } catch (e) {}
      $('updateBanner').classList.add('hidden');
    };
    $('updateBanner').classList.remove('hidden');
  } catch (e) { /* offline: abaikan */ }
}

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------
(async () => {
  const raw = await window.api.listSavedDevices();
  savedDevices = raw.map(migrateDevice);
  if (JSON.stringify(raw) !== JSON.stringify(savedDevices)) await persistDevices();
  settings = await window.api.getSettings();
  settingsDefaults = await window.api.getDefaultSettings();
  showPage('home');
  updateNotifBadge();
  render();
  checkUpdate();
  await refreshScan();
  setInterval(() => { if (!document.hidden) refreshScan(); }, 5000);
})();
