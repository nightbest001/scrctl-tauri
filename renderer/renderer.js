// =============================================================================
// Orbit — halaman utama (Beranda / Setup / Setelan / Log)
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

const ICON = {  // Lucide
  phone: '<svg viewBox="0 0 24 24"><rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/></svg>',
  wifi: '<svg viewBox="0 0 24 24"><path d="M12 20h.01"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M5 12.859a10 10 0 0 1 14 0"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/></svg>',
  key: '<svg viewBox="0 0 24 24"><path d="M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z"/><circle cx="16.5" cy="7.5" r=".5" fill="currentColor"/></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>',
  mirror: '<svg viewBox="0 0 24 24"><rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/></svg>',
  send: '<svg viewBox="0 0 24 24"><path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z"/><path d="m21.854 2.147-10.94 10.939"/></svg>',
  bell: '<svg viewBox="0 0 24 24"><path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M22 8c0-2.3-.8-4.3-2-6"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/><path d="M4 2C2.8 3.7 2 5.7 2 8"/></svg>',
  link: '<svg viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>',
  moon: '<svg viewBox="0 0 24 24"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>',
  torch: '<svg viewBox="0 0 24 24"><path d="M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12z"/><line x1="6" x2="18" y1="6" y2="6"/><line x1="12" x2="12" y1="12" y2="12"/></svg>',
  mute: '<svg viewBox="0 0 24 24"><path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><line x1="22" x2="16" y1="9" y2="15"/><line x1="16" x2="22" y1="9" y2="15"/></svg>',
  volDn: '<svg viewBox="0 0 24 24"><path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/></svg>',
  volUp: '<svg viewBox="0 0 24 24"><path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"/><path d="M16 9a5 5 0 0 1 0 6"/><path d="M19.364 18.364a9 9 0 0 0 0-12.728"/></svg>',
  clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>',
  zap: '<svg viewBox="0 0 24 24"><path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/></svg>',
};

Object.assign(ICON, {
  bt: '<svg viewBox="0 0 24 24"><path d="m7 7 10 10-5 5V2l5 5L7 17"/></svg>',
  hotspot: '<svg viewBox="0 0 24 24"><path d="M12 12a2 2 0 1 0 0 .01"/><path d="M16.24 7.76a6 6 0 0 1 0 8.49m-8.48-.01a6 6 0 0 1 0-8.49"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 19.07a10 10 0 0 1 0-14.14"/></svg>',
  play: '<svg viewBox="0 0 24 24"><polygon points="6 3 20 12 6 21 6 3"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><rect x="14" y="4" width="4" height="16" rx="1"/><rect x="6" y="4" width="4" height="16" rx="1"/></svg>',
  next: '<svg viewBox="0 0 24 24"><polygon points="5 4 15 12 5 20 5 4"/><line x1="19" x2="19" y1="5" y2="19"/></svg>',
  prev: '<svg viewBox="0 0 24 24"><polygon points="19 20 9 12 19 4 19 20"/><line x1="5" x2="5" y1="19" y2="5"/></svg>',
  music: '<svg viewBox="0 0 24 24"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
  pcIcon: '<svg viewBox="0 0 24 24"><rect width="20" height="14" x="2" y="3" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
  call: '<svg viewBox="0 0 24 24"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/></svg>',
  callEnd: '<svg viewBox="0 0 24 24"><path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91"/><line x1="22" x2="2" y1="2" y2="22"/></svg>',
});

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
const PAGE_TITLES = { home: 'Beranda', notif: 'Notifikasi', sms: 'Pesan', calls: 'Telepon', photos: 'Foto', camera: 'Kamera', apps: 'Apps', deck: 'Deck', link: 'Link HP', setup: 'Setup', settings: 'Setelan', log: 'Log' };

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
  if (name === 'link') refreshLink();
  if (window.featurePage) window.featurePage(name); // halaman tambahan: features.js
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
  if (mirroringSerial !== lastMirrorSerial) {
    const dv = allDevices().find((d) => d.serial === (mirroringSerial || lastMirrorSerial));
    tlPush(mirroringSerial ? 'Mirror layar dimulai' : 'Mirror dihentikan', dv ? (dv.name || dv.serial) : '', 'mirror');
    lastMirrorSerial = mirroringSerial;
  }
  renderBanner();
  renderDash();
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
  banner.classList.toggle('hidden', !!scan.adbOk); // banner cuma tampil kalau adb tidak ketemu
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

// Ikon app untuk notifikasi: cocokkan lewat package atau nama app, jatuh ke huruf awal.
const APP_ICONS = [
  ['whatsapp', /whatsapp/i], ['telegram', /telegram|challegram/i], ['discord', /discord/i],
  ['roblox', /roblox/i], ['tiktok', /tiktok|musically|ugc\.trill/i], ['youtube', /youtube/i],
  ['claude', /anthropic|\bclaude\b/i],
];
function appIcon(n) {
  const key = `${n.pkg || ''} ${n.app || ''}`;
  const hit = APP_ICONS.find(([, re]) => re.test(key));
  if (hit) return h('div', { class: 'n-ico has-img' }, h('img', { src: `icons/${hit[0]}.png`, alt: n.app || '', draggable: 'false' }));
  return h('div', { class: 'n-ico' }, (n.app || '?').charAt(0).toUpperCase());
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
      appIcon(n),
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
  renderDash();
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
const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
const LC_DOTS = 72;
let lcDotIdx = -1, lcHooked = false, lcLast = { t: 0, b: 0, spd: 0 };

function lcState(s, title, sub) {
  $('launch').dataset.s = s;
  if (title != null) $('lcTitle').textContent = title;
  if (sub != null) $('lcSub').textContent = sub;
}
function lcClose() {
  const L = $('launch');
  L.classList.add('out');
  setTimeout(() => L.classList.add('hidden'), 280);
}
function lcBuildDots() {
  const box = $('lcDots');
  box.innerHTML = '';
  for (let i = 0; i < LC_DOTS; i++) box.appendChild(document.createElement('i'));
  box.lastChild.classList.add('end');
  lcDotIdx = -1;
}
function lcStat(done, total, mbps) {
  const mb = (n) => (n / 1048576).toFixed(1);
  $('lcStat').innerHTML = `${mb(done)} MB / ${mb(total)} MB <span class="at">@</span> <span class="spd">${mbps.toFixed(1)} Mbps</span>`;
}
function lcProgress(done, total, mbps) {
  lcStat(done, total, mbps);
  const idx = Math.min(LC_DOTS - 1, Math.floor((done / (total || 1)) * LC_DOTS));
  if (idx === lcDotIdx) return;
  lcDotIdx = idx;
  const dots = $('lcDots').children;
  for (let i = 0; i < dots.length; i++) {
    dots[i].classList.toggle('done', i < idx);
    dots[i].classList.toggle('cur', i === idx && i < LC_DOTS - 1);
  }
}

async function lcStartDownload(rel, asset) {
  lcState('dl', 'Mengunduh dan memasang', `Orbit untuk Windows | ${rel.tag_name}`);
  lcBuildDots();
  lcStat(0, asset.size, 0);
  lcProgress(0, asset.size, 0);
  lcLast = { t: Date.now(), b: 0, spd: 0 };
  if (!lcHooked) {
    lcHooked = true;
    window.api.onUpdateProgress((p) => {
      if (!p || !p.total) return;
      const now = Date.now(), dt = (now - lcLast.t) / 1000;
      if (dt >= 0.25) {
        const inst = ((p.done - lcLast.b) * 8) / 1e6 / dt;
        lcLast.spd = lcLast.spd ? lcLast.spd * 0.6 + inst * 0.4 : inst;
        lcLast.t = now; lcLast.b = p.done;
      }
      lcProgress(p.done, p.total, lcLast.spd);
    });
  }
  try {
    await window.api.downloadUpdate(asset.browser_download_url, asset.size);
    lcProgress(asset.size, asset.size, lcLast.spd);
    $('lcStat').textContent = 'Memasang update, app buka lagi sendiri…';
    await sleep(700);
    await window.api.installUpdate();
  } catch (e) {
    lcState('error', 'Update gagal', `Orbit untuk Windows | ${rel.tag_name}`);
    $('lcFoundH').textContent = 'Download gagal';
    $('lcFoundP').textContent = 'Cek koneksi internet, lalu coba lagi.';
    $('lcGo').textContent = 'Coba lagi';
    $('lcGo').onclick = () => lcStartDownload(rel, asset);
  }
}

// Layar pembuka: cek GitHub Releases. Ada versi baru -> tombol download. Kalau tidak -> masuk app.
async function launchFlow() {
  $('lcClose').onclick = lcClose;
  $('lcSkip').onclick = lcClose;
  let cur = '';
  try { cur = await window.api.getAppVersion(); $('navVer').textContent = 'v' + cur; } catch (e) {}
  lcState('check', 'Membuka Orbit', `Orbit untuk Windows | v${cur}`);
  const t0 = Date.now();
  let rel = null;
  try {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 6000);
    const r = await fetch(`https://api.github.com/repos/${UPDATE_REPO}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }, signal: ctl.signal
    });
    clearTimeout(t);
    if (r.ok) rel = await r.json();
  } catch (e) { /* offline / repo private: lanjut tanpa update */ }
  const wait = 1000 - (Date.now() - t0);
  if (wait > 0) await sleep(wait);
  if (!rel || !rel.tag_name || !cur || !isNewerVer(rel.tag_name, cur)) { lcClose(); return; }

  const asset = (rel.assets || []).find((a) => /-setup\.exe$/i.test(a.name));
  lcState('found', 'Update tersedia', `v${cur}  →  ${rel.tag_name}`);
  $('lcFoundH').textContent = `Versi ${rel.tag_name} siap diunduh`;
  $('lcFoundP').textContent = asset
    ? `${(asset.size / 1048576).toFixed(1)} MB. Terpasang otomatis, app akan dibuka ulang.`
    : 'Installer otomatis tidak ada di release ini. Buka halaman download.';
  $('lcGo').textContent = asset ? 'Download update baru' : 'Buka halaman download';
  $('lcGo').onclick = asset
    ? () => lcStartDownload(rel, asset)
    : () => window.api.openUrl(rel.html_url).catch(() => toast('Gagal buka browser', true));
}

// ---------------------------------------------------------------------------
// Link HP (app Android Orbit Link): baterai, kontrol cepat, kirim file
// ---------------------------------------------------------------------------
let linkInfo = { connected: false };
let linkFinding = false;

function applyLink(next) {
  linkInfo = Object.assign({}, linkInfo, next);
  const i = linkInfo;
  if (i.ip) $('linkAddr').textContent = `${i.ip}:${i.port}`;
  if (i.token) $('linkToken').textContent = (i.token.match(/.{1,4}/g) || [i.token]).join('-');
  const st = $('linkStatus');
  st.textContent = i.connected ? `Terhubung: ${i.name}` : 'menunggu HP…';
  st.classList.toggle('link-ok', !!i.connected);
  let bat = 'Baterai: -';
  if (i.connected && i.battery != null) bat = `Baterai HP: ${i.battery}%${i.charging ? ', mengisi' : ''}`;
  $('linkBattery').textContent = bat;
  document.querySelectorAll('[data-link]').forEach((b) => { b.disabled = !i.connected; });
  $('linkTorch').classList.toggle('on', !!i.torch);
  $('linkDnd').classList.toggle('on', !!i.dnd);
  $('linkFind').classList.toggle('on', linkFinding && !!i.connected);
  const ct = $('pcCtlToggle');
  if (ct) ct.checked = !!i.control;
  const cs = $('pcCtlStatus');
  if (cs) {
    cs.textContent = !i.control ? 'Dimatikan'
      : i.screen ? 'Aktif. HP sedang melihat layar PC.'
      : i.connected ? 'Aktif. HP siap mengontrol PC.'
      : 'Aktif. Menunggu HP terhubung.';
    cs.classList.toggle('link-ok', !!i.control);
  }
  $('ctlLive').classList.toggle('hidden', !(i.control && i.screen && i.connected));
  if (!i.connected) linkFinding = false;
  renderDash();
}

async function refreshLink() {
  try { applyLink(await window.api.linkInfo()); } catch (e) { /* belum siap */ }
}

window.api.onLinkState((s) => applyLink(s));
window.api.onLinkEvent(({ text, err }) => { addLog('Link: ' + text, err); toast(text, err); tlPush(text, 'Link', /file|kirim|terkirim|diterima/i.test(text) ? 'send' : 'link'); });

const linkBtn = (id, cmd, on) => $(id).addEventListener('click', () => window.api.linkCmd(cmd, on));
linkBtn('linkTorch', 'flashlight');
linkBtn('linkDnd', 'dnd');
linkBtn('linkVolDown', 'vol_down');
linkBtn('linkVolUp', 'vol_up');
linkBtn('linkMute', 'mute');
$('linkFind').addEventListener('click', () => {
  linkFinding = !linkFinding;
  window.api.linkCmd('find', linkFinding);
  applyLink({});
});
$('pcCtlToggle').addEventListener('change', async (e) => {
  const on = e.target.checked;
  if (on) {
    const ok = await window.api.confirmDialog({
      title: 'Kontrol PC dari HP',
      message: 'HP yang terhubung akan bisa menggerakkan mouse, mengetik, dan melihat layar PC ini.',
      detail: 'Siapa pun yang tahu kode Link bisa melakukannya. Matikan saat tidak dipakai.',
      ok: 'Nyalakan'
    });
    if (!ok) { e.target.checked = false; return; }
  }
  try { applyLink(await window.api.linkSetControl(on)); }
  catch (err) { e.target.checked = !on; toast('Gagal mengubah pengaturan', true); }
});
let ng = { running: false };
function applyNgrok(next) {
  ng = Object.assign({}, ng, next);
  const busy = ng.running && !ng.url && !ng.err;
  $('ngToggle').checked = !!ng.running;
  $('ngStatus').textContent = ng.url ? 'Aktif. HP bisa terhubung dari mana saja.'
    : busy ? 'Menyambung ke ngrok…' : ng.running ? 'Menunggu alamat…' : 'Dimatikan';
  $('ngStatus').classList.toggle('link-ok', !!ng.url);
  $('ngAddrWrap').classList.toggle('hidden', !ng.url);
  if (ng.url) $('ngAddr').textContent = ng.url;
  $('ngInstall').classList.toggle('hidden', ng.installed !== false);
  $('ngInstallBtn').disabled = !!ng.installing;
  $('ngInstallBtn').textContent = ng.installing ? 'Memasang…' : 'Pasang ngrok';
  if (ng.installing) $('ngStatus').textContent = 'Memasang ngrok…';
  $('ngErr').classList.toggle('hidden', !ng.err);
  $('ngErr').textContent = ng.err || '';
  $('ngForget').disabled = !ng.has_token;
  $('ngToken').placeholder = ng.has_token ? 'Token tersimpan. Isi untuk menggantinya' : 'Kosongkan jika sudah diatur di ngrok';
}
window.api.onNgrokState((s) => applyNgrok(s));
window.api.ngrokStatus().then(applyNgrok).catch(() => {});
async function ngInstall() {
  const ok = await window.api.confirmDialog({
    title: 'Pasang ngrok',
    message: 'Orbit akan mengunduh dan memasang ngrok di komputer ini.',
    detail: 'Memakai winget (Ngrok.Ngrok). Jika winget tidak ada, paket resmi diunduh dari bin.equinox.io ke folder data Orbit.',
    ok: 'Pasang'
  });
  if (!ok) return false;
  applyNgrok({ installing: true, err: '' });
  try {
    const r = await window.api.ngrokInstall();
    applyNgrok(r);
    if (r.installed) toast('ngrok terpasang');
    return !!r.installed;
  } catch (err) { applyNgrok({ installing: false }); toast('Gagal memasang ngrok', true); return false; }
}
$('ngInstallBtn').addEventListener('click', ngInstall);
$('ngToggle').addEventListener('change', async (e) => {
  const on = e.target.checked;
  try {
    if (on && ng.installed === false) {
      e.target.checked = false;
      await ngInstall();
      return;
    }
    if (on) {
      const ok = await window.api.confirmDialog({
        title: 'Akses jarak jauh',
        message: 'PC ini akan bisa dijangkau dari internet lewat tunnel ngrok.',
        detail: 'Data Link dienkripsi, tapi siapa pun yang tahu kode bisa mencoba terhubung. Pakai kode yang kuat dan matikan saat tidak dipakai.',
        ok: 'Nyalakan'
      });
      if (!ok) { e.target.checked = false; return; }
      applyNgrok({ running: true, url: null, err: '' });
      const tok = $('ngToken').value.trim();
      const r = await window.api.ngrokStart(tok);
      if (tok) $('ngToken').value = '';
      applyNgrok(r);
    } else {
      applyNgrok(await window.api.ngrokStop());
    }
  } catch (err) { e.target.checked = !on; toast('Gagal mengubah ngrok', true); }
});
$('ngCopy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('ngAddr').textContent); toast('Alamat disalin'); } catch (e) { toast('Gagal menyalin', true); }
});
$('ngDash').addEventListener('click', () => window.api.openUrl('https://dashboard.ngrok.com/get-started/your-authtoken'));
$('ngForget').addEventListener('click', async () => { applyNgrok(await window.api.ngrokForgetToken()); toast('Token dihapus'); });
$('linkSendFile').addEventListener('click', async () => {
  const r = await window.api.linkSendFile();
  if (r && r.err) toast(r.err, true);
});
$('linkCopy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('linkAddr').textContent); toast('Alamat disalin'); } catch (e) { toast('Gagal menyalin', true); }
});
$('linkRegen').addEventListener('click', async () => {
  const ok = await window.api.confirmDialog({ title: 'Kode baru', message: 'HP yang sedang terhubung akan terputus dan harus pakai kode baru.', detail: '', ok: 'Buat kode baru' });
  if (!ok) return;
  applyLink(await window.api.linkRegen());
  toast('Kode baru dibuat');
});


// ---------------------------------------------------------------------------
// Dashboard Beranda (hero, aksi cepat, notifikasi, kontrol, timeline)
// ---------------------------------------------------------------------------
let timeline = [];
try { timeline = JSON.parse(localStorage.getItem('scrctl.timeline') || '[]'); } catch (e) { timeline = []; }
let lastMirrorSerial = null;

function tlPush(title, sub, icon) {
  timeline.unshift({ title, sub: sub || '', icon: icon || 'clock', ts: Date.now() });
  timeline = timeline.slice(0, 30);
  try { localStorage.setItem('scrctl.timeline', JSON.stringify(timeline)); } catch (e) { /* ignore */ }
  renderDash();
}

function dashDevice() {
  const list = allDevices();
  return list.find((d) => d.serial === selectedSerial)
    || list.find((d) => d.serial === mirroringSerial)
    || list.find((d) => d.state === 'device')
    || list[0] || null;
}

// Dial baterai: busur 320° (celah di bawah), titik di ujung, angka mengalir halus.
// Gaya meniru CometDial React Bits, ditulis ulang SVG polos tanpa React.
const BAT = (() => {
  const R = 42, SW = 5, SWEEP = 320, START = 110, NS = 'http://www.w3.org/2000/svg';
  const pt = (d) => { const a = (d * Math.PI) / 180; return [50 + Math.cos(a) * R, 50 + Math.sin(a) * R]; };
  const arc = (a0, a1) => {
    const [x0, y0] = pt(a0), [x1, y1] = pt(a1);
    return `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${R} ${R} 0 ${a1 - a0 > 180 ? 1 : 0} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
  };
  const mk = (t, a) => { const e = document.createElementNS(NS, t); for (const k in a) e.setAttribute(k, a[k]); return e; };
  const svg = mk('svg', { viewBox: '0 0 100 100' });
  const lit = mk('path', { class: 'bat-lit', 'stroke-width': SW });
  const head = mk('circle', { class: 'bat-head', r: SW * 0.9 });
  svg.append(mk('path', { class: 'bat-track', d: arc(START, START + SWEEP), 'stroke-width': SW }), lit, head);
  const num = h('span', { class: 'bat-num' }, '--');
  const unit = h('span', { class: 'bat-unit' }, '%');
  const el = h('div', { class: 'bat' }, svg, h('div', { class: 'bat-txt' }, num, unit));
  const still = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  let shown = null, target = null, raf = 0, last = 0;

  function draw() {
    if (target == null || shown == null) {
      lit.setAttribute('d', ''); head.style.display = 'none'; num.textContent = '--'; unit.style.display = 'none';
      return;
    }
    const ang = START + Math.max(0, Math.min(1, shown / 100)) * SWEEP;
    lit.setAttribute('d', shown > 0.05 ? arc(START, ang) : '');
    const [cx, cy] = pt(ang);
    head.setAttribute('cx', cx.toFixed(2)); head.setAttribute('cy', cy.toFixed(2)); head.style.display = '';
    num.textContent = String(Math.round(shown)); unit.style.display = '';
  }
  function step(now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now;
    shown += (target - shown) * (1 - Math.exp(-dt / 0.22));
    if (Math.abs(target - shown) < 0.05) { shown = target; raf = 0; } else raf = requestAnimationFrame(step);
    draw();
  }
  function set(pct, charging) {
    el.className = 'bat ' + (pct == null ? '' : charging ? 'chg' : pct < 20 ? 'low' : '');
    target = pct;
    if (pct == null) { shown = null; draw(); return el; }
    if (shown == null || still) { shown = pct; draw(); return el; }
    if (!raf) { last = performance.now(); raf = requestAnimationFrame(step); }
    return el;
  }
  return { set };
})();

function renderDash() {
  if (!$('hero')) return;
  const L = linkInfo || {};
  const list = allDevices();
  const dev = dashDevice();
  const online = !!dev && dev.state === 'device';
  const mirroring = !!dev && dev.serial === mirroringSerial;
  const pct = L.connected && L.battery != null ? L.battery : null;

  // Hero
  const hero = $('hero');
  hero.innerHTML = '';
  hero.append(
    h('div', { class: 'phone' }, BAT.set(pct, !!(L.connected && L.charging))),
    h('div', { class: 'hero-info' },
      h('div', { class: 'hero-name' }, dev ? (dev.name || dev.ip || dev.serial) : 'Belum ada HP'),
      h('div', { class: 'hero-sub' }, dev ? (dev.usb ? 'USB' : (dev.ip ? `${dev.ip}:${dev.wdOn && dev.livePort ? dev.livePort : dev.port}` : dev.serial)) : 'Tambahkan lewat Setup atau klik HP yang terdeteksi di bawah.'),
      h('div', { class: 'chips2' },
        h('span', { class: 'chip2' }, h('span', { class: 'dot' + (online ? ' on' : '') }), mirroring ? 'Sedang mirror' : online ? 'ADB terhubung' : 'ADB offline'),
        h('span', { class: 'chip2' }, h('span', { class: 'dot' + (L.connected ? ' on' : '') }), L.connected ? `Link: ${L.name}` : 'Link menunggu'),
        L.connected && L.charging && h('span', { class: 'chip2' }, h('span', { class: 'ci', html: ICON.zap }), 'Mengisi')),
      list.length > 1 && h('div', { class: 'hero-pick' }, list.map((d) => h('button', { class: d === dev ? 'on' : '', onclick: () => { selectedSerial = d.serial; renderDash(); renderGrid(); } }, d.name || d.ip || d.serial)))));

  // Aksi cepat
  const q = $('quick');
  q.innerHTML = '';
  const act = (icon, label, onclick, disabled, cls) => h('button', { class: cls || '', disabled: !!disabled, onclick }, h('span', { class: 'ico', html: ICON[icon] }), label);
  q.append(
    act('mirror', mirroring ? 'Stop mirror' : 'Mirror layar', async () => {
      if (mirroring) { await window.api.stopMirror(); mirroringSerial = null; render(); } else if (dev) startMirrorFor(dev);
    }, !dev || (!mirroring && dev.serial === busySerial), mirroring ? 'live' : ''),
    act('send', 'Kirim file', async () => { const r = await window.api.linkSendFile(); if (r && r.err) toast(r.err, true); }, !L.connected),
    act('bell', 'Cari HP', () => { linkFinding = !linkFinding; window.api.linkCmd('find', linkFinding); applyLink({}); }, !L.connected),
    act('link', 'Link HP', () => showPage('link')));

  // Notifikasi
  const dl = $('dnList');
  dl.innerHTML = '';
  $('dnTitle').textContent = `Notifikasi · ${notifs.length}`;
  if (!notifs.length) dl.append(h('div', { class: 'empty-note' }, 'Belum ada notifikasi dari HP.'));
  for (const n of notifs.slice(0, 3)) {
    dl.append(h('div', { class: 'dn' },
      appIcon(n),
      h('div', { style: 'min-width:0;flex:1' },
        h('div', { class: 'dn-t' }, h('b', {}, n.app), ` · ${timeAgo(n.ts)}`),
        n.title && h('div', { class: 'dn-h' }, n.title),
        n.text && h('div', { class: 'dn-p' }, n.text))));
  }

  // Kontrol HP
  $('ctlState').textContent = L.connected ? `Link: ${L.name}` : 'Link belum terhubung';
  $('ctlState').classList.toggle('ok', !!L.connected);
  const ctl = $('ctl');
  ctl.innerHTML = '';
  const cb = (icon, label, cmd, on) => h('button', { class: on ? 'on' : '', disabled: !L.connected, onclick: () => window.api.linkCmd(cmd) }, h('span', { html: ICON[icon] }), label);
  // Wi-Fi / Bluetooth / Hotspot diubah lewat adb (Android melarang app biasa mengubahnya)
  const tg = (icon, label, kind) => {
    const on = window.toggleState ? window.toggleState(kind) : null;
    return h('button', { class: on ? 'on' : '', disabled: !online, title: online ? '' : 'Butuh koneksi adb ke HP (menu Setup)', onclick: () => window.togglePhone && window.togglePhone(kind) }, h('span', { html: ICON[icon] }), label);
  };
  ctl.append(cb('moon', 'DND', 'dnd', L.dnd), cb('torch', 'Senter', 'flashlight', L.torch), cb('mute', 'Mute', 'mute'),
    cb('volDn', 'Volume −', 'vol_down'), cb('volUp', 'Volume +', 'vol_up'),
    tg('wifi', 'Wi-Fi', 'wifi'), tg('bt', 'BT', 'bt'), tg('hotspot', 'Hotspot', 'hotspot'));

  // Timeline
  const tl = $('tl');
  tl.innerHTML = '';
  if (!timeline.length) tl.append(h('div', { class: 'empty-note' }, 'Aktivitas mirror, file, dan clipboard muncul di sini.'));
  for (const t of timeline.slice(0, 5)) {
    tl.append(h('div', { class: 'tl-i' }, h('span', { class: 'ico', html: ICON[t.icon] || ICON.clock }),
      h('div', {}, h('div', { class: 'tl-h' }, t.title), h('div', { class: 'tl-p' }, `${t.sub ? t.sub + ' · ' : ''}${timeAgo(t.ts)}`))));
  }
}
$('dnAll').addEventListener('click', () => showPage('notif'));
$('dnClear').addEventListener('click', () => { notifs = []; unreadNotif = 0; saveNotifs(); updateNotifBadge(); renderNotifs(); renderDash(); });
setInterval(() => { if (currentPage === 'home' && !document.hidden) renderDash(); }, 30000);

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
  refreshLink();
  updateNotifBadge();
  render();
  launchFlow();
  await refreshScan();
  setInterval(() => { if (!document.hidden) refreshScan(); }, 5000);
})();
