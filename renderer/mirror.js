const stage = document.getElementById('stage');
const noSignal = document.getElementById('noSignal');
const noSignalHint = document.getElementById('noSignalHint');
const canvas = document.getElementById('screenCanvas');
const ctx = canvas.getContext('2d');
const dropOverlay = document.getElementById('dropOverlay');
const toastEl = document.getElementById('toast');
const tbTitle = document.getElementById('tbTitle');

let sessionInfo = null; // { deviceName, width, height }
let decoder = null;
let pendingConfig = null;
let toastTimer = null;
let controlEnabled = true;
let keyboardUhid = false;
let mouseUhid = false;
let screenOn = true;

function setTitle(text) {
  const t = text || 'SCRCTL Mirror';
  document.title = t;
  tbTitle.textContent = t;
}

function showToast(text, isErr) {
  toastEl.textContent = text;
  toastEl.className = 'toast show' + (isErr ? ' err' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.classList.remove('show'); }, 3000);
}

// ---- Window controls (frameless window — custom title bar) ----
document.getElementById('btnMin').addEventListener('click', () => window.api.windowMinimize());
document.getElementById('btnMax').addEventListener('click', () => window.api.windowMaximize());
document.getElementById('btnClose').addEventListener('click', () => window.api.windowClose());

// ---- Init: main process tells us which device this window belongs to ----
window.api.onMirrorInit(({ serial, deviceName, control, audio, keyboardUhid: kb, mouseUhid: ms }) => {
  setTitle(deviceName || serial);
  noSignalHint.textContent = 'menghubungkan…';
  noSignal.classList.remove('stopped');
  controlEnabled = control !== false;
  keyboardUhid = !!kb;
  mouseUhid = !!ms;
  hidReleaseAll();
  if (document.pointerLockElement) document.exitPointerLock();
  document.body.classList.toggle('viewonly', !controlEnabled);
  document.getElementById('viewOnlyBadge').classList.toggle('hidden', controlEnabled);
  screenOn = true;
  document.getElementById('btnScreenOff').classList.remove('active');
  if (!audio) closeAudio();
});

window.api.onLog(({ text, isErr }) => {
  // surface only meaningful backend errors as toasts here; full log lives in the main window
  if (isErr) showToast(text, true);
});

window.api.onMirrorStopped(() => {
  noSignal.style.display = 'flex';
  noSignal.classList.add('stopped');
  noSignalHint.textContent = 'mirror dihentikan';
  canvas.style.display = 'none';
  closeAudio();
});

// =============================================================================
// Video: receive raw H264 packets from main, decode with WebCodecs, draw to canvas
// =============================================================================
window.api.onMirrorSession(({ deviceName, width, height }) => {
  const isFirst = !sessionInfo;
  const dimsChanged = sessionInfo && (sessionInfo.width !== width || sessionInfo.height !== height);
  sessionInfo = { deviceName, width, height };

  if (isFirst || dimsChanged) {
    canvas.width = width;
    canvas.height = height;
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch (e) { /* ignore */ }
    }
    decoder = null;
    pendingConfig = null;
  }

  canvas.style.display = 'block';
  noSignal.style.display = 'none';
  if (deviceName) setTitle(deviceName);
});

window.api.onMirrorPacket(({ config, keyFrame, data }) => {
  const bytes = new Uint8Array(data);

  if (config) {
    pendingConfig = bytes;
    return;
  }

  let chunkData = bytes;
  if (keyFrame && pendingConfig) {
    const merged = new Uint8Array(pendingConfig.length + bytes.length);
    merged.set(pendingConfig, 0);
    merged.set(bytes, pendingConfig.length);
    chunkData = merged;
    if (!decoder || decoder.state !== 'configured') {
      configureDecoder(pendingConfig);
    }
    pendingConfig = null;
  }

  if (!decoder || decoder.state !== 'configured') return;

  try {
    decoder.decode(new EncodedVideoChunk({
      type: keyFrame ? 'key' : 'delta',
      timestamp: performance.now() * 1000,
      data: chunkData,
    }));
  } catch (e) {
    showToast('decode error: ' + e.message, true);
  }
});

function configureDecoder(spsPpsBytes) {
  if (!sessionInfo) return;
  const codecStr = buildCodecString(spsPpsBytes) || 'avc1.640028';
  try {
    decoder = new VideoDecoder({
      output: (frame) => {
        ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
        frame.close();
      },
      error: (e) => showToast('VideoDecoder error: ' + e.message, true),
    });
    decoder.configure({
      codec: codecStr,
      codedWidth: sessionInfo.width,
      codedHeight: sessionInfo.height,
      optimizeForLatency: true,
      hardwareAcceleration: 'prefer-hardware',
      avc: { format: 'annexb' },
    });
  } catch (e) {
    showToast('gagal configure decoder: ' + e.message, true);
  }
}

function buildCodecString(bytes) {
  let i = 0;
  while (i < bytes.length - 4) {
    const isStart4 = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 0 && bytes[i + 3] === 1;
    const isStart3 = bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1;
    if (isStart4 || isStart3) {
      const nalStart = i + (isStart4 ? 4 : 3);
      const nalType = bytes[nalStart] & 0x1f;
      if (nalType === 7 && nalStart + 3 < bytes.length) {
        const hex = (n) => n.toString(16).padStart(2, '0');
        return `avc1.${hex(bytes[nalStart + 1])}${hex(bytes[nalStart + 2])}${hex(bytes[nalStart + 3])}`;
      }
      i = nalStart;
    } else {
      i++;
    }
  }
  return null;
}

// =============================================================================
// Audio: paket Opus dari main -> AudioDecoder -> WebAudio (dijadwalkan berurutan)
// =============================================================================
let audioDecoder = null;
let audioCtx = null;
let audioNextTime = 0;
let audioTs = 0;
const OPUS_FRAME_US = 20000; // scrcpy mengirim frame Opus 20 ms

function closeAudio() {
  try { if (audioDecoder && audioDecoder.state !== 'closed') audioDecoder.close(); } catch (e) { /* ignore */ }
  try { if (audioCtx && audioCtx.state !== 'closed') audioCtx.close(); } catch (e) { /* ignore */ }
  audioDecoder = null; audioCtx = null; audioNextTime = 0; audioTs = 0;
}

function initAudio(opusHead) {
  if (typeof AudioDecoder === 'undefined') { showToast('AudioDecoder tidak tersedia', true); return; }
  closeAudio();
  try {
    audioCtx = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
    audioDecoder = new AudioDecoder({
      output: playAudioData,
      error: (e) => showToast('AudioDecoder error: ' + e.message, true),
    });
    audioDecoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2, description: opusHead });
  } catch (e) {
    showToast('gagal init audio: ' + e.message, true);
    closeAudio();
  }
}

function playAudioData(ad) {
  if (!audioCtx) { ad.close(); return; }
  const channels = ad.numberOfChannels;
  const buffer = audioCtx.createBuffer(channels, ad.numberOfFrames, ad.sampleRate);
  for (let ch = 0; ch < channels; ch++) {
    const arr = new Float32Array(ad.numberOfFrames);
    ad.copyTo(arr, { planeIndex: ch, format: 'f32-planar' });
    buffer.copyToChannel(arr, ch);
  }
  ad.close();

  const now = audioCtx.currentTime;
  // Jaga latensi: kalau antrean menumpuk > 250 ms, lompat ke "sekarang".
  if (audioNextTime < now + 0.02 || audioNextTime > now + 0.25) audioNextTime = now + 0.04;
  const src = audioCtx.createBufferSource();
  src.buffer = buffer;
  src.connect(audioCtx.destination);
  src.start(audioNextTime);
  audioNextTime += buffer.duration;
}

window.api.onMirrorAudio(({ config, data }) => {
  const bytes = new Uint8Array(data);
  if (config) { initAudio(bytes); return; }
  if (!audioDecoder || audioDecoder.state !== 'configured') return;
  try {
    audioDecoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: audioTs, data: bytes }));
    audioTs += OPUS_FRAME_US;
  } catch (e) { /* frame rusak, lewati */ }
});

// =============================================================================
// Input: forward mouse/keyboard on the canvas back to the device
// =============================================================================
const BUTTON_MAP = { 0: 1, 1: 4, 2: 2 };

function devicePoint(e) {
  const rect = canvas.getBoundingClientRect();
  const relX = (e.clientX - rect.left) / rect.width;
  const relY = (e.clientY - rect.top) / rect.height;
  return {
    x: Math.min(Math.max(relX, 0), 1) * sessionInfo.width,
    y: Math.min(Math.max(relY, 0), 1) * sessionInfo.height,
  };
}

function sendTouchFromEvent(e, action) {
  if (!sessionInfo || !controlEnabled) return;
  const { x, y } = devicePoint(e);
  window.api.sendTouch({
    action,
    x, y,
    screenWidth: sessionInfo.width,
    screenHeight: sessionInfo.height,
    actionButton: action === 2 ? 0 : (BUTTON_MAP[e.button] ?? 1),
    buttons: e.buttons,
  });
}

// Konvensi scrcpy: klik kiri = sentuh, klik kanan = Back, klik tengah = Home,
// tombol mouse "back" (4) = Back, "forward" (5) = Recent apps.
let touchActive = false;

canvas.addEventListener('pointerdown', (e) => {
  canvas.focus();
  if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
  if (!controlEnabled) return;
  e.preventDefault();
  if (mouseUhid) { hidMouseDown(e); return; }
  if (e.button === 2) { pressBack(); return; }
  if (e.button === 1) { pressKey(ANDROID_KEYCODE.HOME); return; }
  if (e.button === 3) { pressBack(); return; }
  if (e.button === 4) { pressKey(ANDROID_KEYCODE.APP_SWITCH); return; }
  if (e.button !== 0) return;
  touchActive = true;
  canvas.setPointerCapture(e.pointerId);
  sendTouchFromEvent(e, 0);
});
canvas.addEventListener('pointermove', (e) => {
  if (mouseUhid) { hidMouseMove(e); return; }
  if (!touchActive) return;
  sendTouchFromEvent(e, 2);
});
canvas.addEventListener('pointerup', (e) => {
  if (mouseUhid) { hidMouseButtons(e); return; }
  if (!touchActive || e.button !== 0) return;
  touchActive = false;
  sendTouchFromEvent(e, 1);
});
canvas.addEventListener('pointercancel', (e) => {
  if (mouseUhid) { hidMouseButtons(e); return; }
  if (!touchActive) return;
  touchActive = false;
  sendTouchFromEvent(e, 1);
});
canvas.addEventListener('auxclick', (e) => e.preventDefault());
canvas.addEventListener('contextmenu', (e) => e.preventDefault());

// Scroll mouse / touchpad -> event scroll scrcpy. Shift + scroll = scroll horizontal.
canvas.addEventListener('wheel', (e) => {
  if (!sessionInfo || !controlEnabled) return;
  e.preventDefault();
  if (mouseUhid) { hidMouseWheel(e); return; }
  const { x, y } = devicePoint(e);
  let dx = e.deltaX;
  let dy = e.deltaY;
  if (e.deltaMode === 1) { dx *= 33; dy *= 33; } // mode baris -> piksel
  if (e.shiftKey && dx === 0) { dx = dy; dy = 0; }
  window.api.sendScroll({
    x, y,
    screenWidth: sessionInfo.width,
    screenHeight: sessionInfo.height,
    h: dx / 60,
    v: -dy / 60,
  });
}, { passive: false });

// ---- Keyboard ----
// KeyboardEvent.code (posisi fisik tombol) -> Android KEYCODE_*
const CODE_TO_ANDROID = {
  Enter: 66, NumpadEnter: 160, Backspace: 67, Tab: 61, Escape: 111, Space: 62,
  ArrowLeft: 21, ArrowRight: 22, ArrowUp: 19, ArrowDown: 20,
  Home: 122, End: 123, PageUp: 92, PageDown: 93, Insert: 124, Delete: 112,
  Backquote: 68, Minus: 69, Equal: 70, BracketLeft: 71, BracketRight: 72, Backslash: 73,
  Semicolon: 74, Quote: 75, Slash: 76, Comma: 55, Period: 56, ContextMenu: 82,
  NumpadDivide: 154, NumpadMultiply: 155, NumpadSubtract: 156, NumpadAdd: 157, NumpadDecimal: 158,
  CapsLock: 115,
};
for (let i = 0; i < 26; i++) CODE_TO_ANDROID['Key' + String.fromCharCode(65 + i)] = 29 + i;
for (let i = 0; i < 10; i++) { CODE_TO_ANDROID['Digit' + i] = 7 + i; CODE_TO_ANDROID['Numpad' + i] = 144 + i; }
for (let i = 1; i <= 12; i++) CODE_TO_ANDROID['F' + i] = 130 + i;

const MODIFIER_CODES = new Set(['ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight']);

function metaState(e) {
  let m = 0;
  if (e.shiftKey) m |= 0x1 | 0x40;
  if (e.altKey) m |= 0x2 | 0x10;
  if (e.ctrlKey) m |= 0x1000 | 0x2000;
  if (e.metaKey) m |= 0x10000 | 0x20000;
  return m;
}

// Shortcut window mirror (Alt + tombol), mirip scrcpy.
function handleShortcut(e) {
  if (!e.altKey || e.ctrlKey || e.metaKey) return false;
  switch (e.code) {
    case 'KeyH': pressKey(ANDROID_KEYCODE.HOME); break;
    case 'KeyB': case 'Backspace': pressBack(); break;
    case 'KeyS': pressKey(ANDROID_KEYCODE.APP_SWITCH); break;
    case 'KeyP': pressKey(ANDROID_KEYCODE.POWER); break;
    case 'KeyN': window.api.sendSimple(5); break;
    case 'KeyR': window.api.sendSimple(11); break;
    case 'KeyO': document.getElementById('btnScreenOff').click(); break;
    case 'KeyF': window.api.toggleFullscreen(); break;
    case 'ArrowUp': pressKey(ANDROID_KEYCODE.VOLUME_UP); break;
    case 'ArrowDown': pressKey(ANDROID_KEYCODE.VOLUME_DOWN); break;
    default: return false;
  }
  return true;
}

canvas.addEventListener('keydown', (e) => {
  e.preventDefault();
  if (!controlEnabled) return;

  if (keyboardUhid) {
    // Mode UHID: semua tombol (termasuk modifier) dikirim sebagai report HID.
    if (!MODIFIER_CODES.has(e.code) && handleShortcut(e)) return;
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
      const k = e.key.toLowerCase();
      if (k === 'v') { window.api.pasteToDevice(); showToast('menempel dari clipboard…'); return; }
      if (k === 'c' || k === 'x') { window.api.copyFromDevice(k === 'x'); showToast(k === 'x' ? 'cut dari HP' : 'copy dari HP'); return; }
    }
    hidKey(e.code, true);
    return;
  }

  if (MODIFIER_CODES.has(e.code)) return; // modifier dikirim lewat metaState

  if (handleShortcut(e)) return;

  // Clipboard: Ctrl+V tempel dari komputer, Ctrl+C / Ctrl+X ambil dari HP
  if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
    const k = e.key.toLowerCase();
    if (k === 'v') { window.api.pasteToDevice(); showToast('menempel dari clipboard…'); return; }
    if (k === 'c' || k === 'x') { window.api.copyFromDevice(k === 'x'); showToast(k === 'x' ? 'cut dari HP' : 'copy dari HP'); return; }
  }

  const code = CODE_TO_ANDROID[e.code];
  const hasCtrlAlt = e.ctrlKey || e.altKey || e.metaKey;

  // Karakter biasa (huruf, angka, simbol, termasuk Shift & layout non-US) -> inject teks,
  // supaya hasilnya sama dengan yang tampil di keyboard komputer.
  if (e.key.length === 1 && !hasCtrlAlt) {
    if (e.code === 'Space' || code === undefined || e.code.startsWith('Numpad') === false) {
      window.api.sendText(e.key);
      return;
    }
  }

  // Sisanya (Enter, panah, F1-F12, Ctrl+A/Z/…, Shift+Arrow, dll) -> keycode + metaState
  if (code !== undefined) {
    window.api.sendKey({ action: 0, keycode: code, meta: metaState(e), repeat: e.repeat ? 1 : 0 });
  } else if (e.key.length === 1) {
    window.api.sendText(e.key);
  }
});
canvas.addEventListener('keyup', (e) => {
  if (!controlEnabled) return;
  e.preventDefault();
  if (keyboardUhid) { hidKey(e.code, false); return; }
  if (MODIFIER_CODES.has(e.code)) return;
  const code = CODE_TO_ANDROID[e.code];
  const printable = e.key.length === 1 && !(e.ctrlKey || e.altKey || e.metaKey);
  if (code !== undefined && !printable) {
    window.api.sendKey({ action: 1, keycode: code, meta: metaState(e) });
  }
});

// =============================================================================
// UHID: keyboard & mouse HID asli (--keyboard=uhid, --mouse=uhid)
// =============================================================================
// KeyboardEvent.code -> HID usage (Keyboard/Keypad page 0x07)
const CODE_TO_HID = {
  Enter: 0x28, Escape: 0x29, Backspace: 0x2A, Tab: 0x2B, Space: 0x2C,
  Minus: 0x2D, Equal: 0x2E, BracketLeft: 0x2F, BracketRight: 0x30, Backslash: 0x31,
  Semicolon: 0x33, Quote: 0x34, Backquote: 0x35, Comma: 0x36, Period: 0x37, Slash: 0x38,
  CapsLock: 0x39, PrintScreen: 0x46, ScrollLock: 0x47, Pause: 0x48,
  Insert: 0x49, Home: 0x4A, PageUp: 0x4B, Delete: 0x4C, End: 0x4D, PageDown: 0x4E,
  ArrowRight: 0x4F, ArrowLeft: 0x50, ArrowDown: 0x51, ArrowUp: 0x52,
  NumLock: 0x53, NumpadDivide: 0x54, NumpadMultiply: 0x55, NumpadSubtract: 0x56,
  NumpadAdd: 0x57, NumpadEnter: 0x58, NumpadDecimal: 0x63, ContextMenu: 0x65,
};
for (let i = 0; i < 26; i++) CODE_TO_HID['Key' + String.fromCharCode(65 + i)] = 0x04 + i;
for (let i = 1; i <= 9; i++) { CODE_TO_HID['Digit' + i] = 0x1D + i; CODE_TO_HID['Numpad' + i] = 0x58 + i; }
CODE_TO_HID.Digit0 = 0x27; CODE_TO_HID.Numpad0 = 0x62;
for (let i = 1; i <= 12; i++) CODE_TO_HID['F' + i] = 0x39 + i;

// Bit modifier di byte pertama report keyboard
const HID_MODIFIER_BITS = {
  ControlLeft: 0x01, ShiftLeft: 0x02, AltLeft: 0x04, MetaLeft: 0x08,
  ControlRight: 0x10, ShiftRight: 0x20, AltRight: 0x40, MetaRight: 0x80,
};

let hidModifiers = 0;
let hidKeys = []; // maksimal 6 tombol ditekan bersamaan

function sendHidKeyboardReport() {
  const report = new Uint8Array(8);
  report[0] = hidModifiers;
  for (let i = 0; i < 6; i++) report[2 + i] = hidKeys[i] || 0;
  window.api.sendHidKeyboard(report);
}

function hidKey(code, down) {
  const mod = HID_MODIFIER_BITS[code];
  if (mod) {
    hidModifiers = down ? (hidModifiers | mod) : (hidModifiers & ~mod);
  } else {
    const usage = CODE_TO_HID[code];
    if (usage === undefined) return;
    const idx = hidKeys.indexOf(usage);
    if (down) { if (idx === -1 && hidKeys.length < 6) hidKeys.push(usage); else return; }
    else if (idx !== -1) hidKeys.splice(idx, 1);
    else return;
  }
  sendHidKeyboardReport();
}

// ---- Mouse (relatif, pakai pointer lock) ----
let hidButtons = 0;

function sendHidMouseReport(dx, dy, wheel, hwheel) {
  const report = new Int8Array(5);
  report[0] = hidButtons;
  report[1] = dx; report[2] = dy; report[3] = wheel; report[4] = hwheel;
  window.api.sendHidMouse(new Uint8Array(report.buffer));
}

const clampI8 = (n) => Math.max(-127, Math.min(127, Math.round(n)));

function hidMouseDown(e) {
  if (document.pointerLockElement !== canvas) {
    canvas.requestPointerLock();
    showToast('mouse ditangkap — tekan Esc untuk melepas');
    return;
  }
  hidMouseButtons(e);
}

function hidMouseButtons(e) {
  if (document.pointerLockElement !== canvas) return;
  // bit e.buttons (kiri 1, kanan 2, tengah 4, back 8, forward 16) sama dengan bit HID
  hidButtons = e.buttons & 0x1F;
  sendHidMouseReport(0, 0, 0, 0);
}

function hidMouseMove(e) {
  if (document.pointerLockElement !== canvas) return;
  let dx = e.movementX || 0;
  let dy = e.movementY || 0;
  // Pecah gerakan besar jadi beberapa report (int8 per report).
  while (dx !== 0 || dy !== 0) {
    const sx = clampI8(dx), sy = clampI8(dy);
    sendHidMouseReport(sx, sy, 0, 0);
    dx -= sx; dy -= sy;
  }
}

function hidMouseWheel(e) {
  if (document.pointerLockElement !== canvas) return;
  let dx = e.deltaX, dy = e.deltaY;
  if (e.deltaMode === 0) { dx /= 100; dy /= 100; }
  if (e.shiftKey && dx === 0) { dx = dy; dy = 0; }
  // Browser: deltaY positif = scroll ke bawah. HID: wheel positif = ke atas.
  let v = clampI8(-dy), h = clampI8(dx);
  if (v === 0 && dy !== 0) v = dy > 0 ? -1 : 1;
  if (h === 0 && dx !== 0) h = dx > 0 ? 1 : -1;
  sendHidMouseReport(0, 0, v, h);
}

function hidReleaseAll() {
  if (hidModifiers !== 0 || hidKeys.length) {
    hidModifiers = 0; hidKeys = [];
    if (keyboardUhid) sendHidKeyboardReport();
  }
  if (hidButtons !== 0) {
    hidButtons = 0;
    if (mouseUhid) sendHidMouseReport(0, 0, 0, 0);
  }
}

document.addEventListener('pointerlockchange', () => {
  if (document.pointerLockElement !== canvas) hidReleaseAll();
});
// Lepas semua tombol kalau window kehilangan fokus (hindari tombol "nyangkut" di HP).
window.addEventListener('blur', hidReleaseAll);
canvas.addEventListener('blur', hidReleaseAll);

// =============================================================================
// Toolbar: Back / Home / Recent / Notif / Rotate / Layar / Volume / Power / Fullscreen
// =============================================================================
const ANDROID_KEYCODE = { HOME: 3, VOLUME_UP: 24, VOLUME_DOWN: 25, POWER: 26, APP_SWITCH: 187 };

// TYPE_BACK_OR_SCREEN_ON: membangunkan layar kalau sedang mati, selain itu Back.
function pressBack() {
  window.api.sendBackOrScreenOn(0);
  window.api.sendBackOrScreenOn(1);
}

function pressKey(keycode) {
  window.api.sendKey({ action: 0, keycode });
  window.api.sendKey({ action: 1, keycode });
}

document.getElementById('btnBack').addEventListener('click', () => pressBack());
document.getElementById('btnHome').addEventListener('click', () => pressKey(ANDROID_KEYCODE.HOME));
document.getElementById('btnRecent').addEventListener('click', () => pressKey(ANDROID_KEYCODE.APP_SWITCH));
document.getElementById('btnVolDown').addEventListener('click', () => pressKey(ANDROID_KEYCODE.VOLUME_DOWN));
document.getElementById('btnVolUp').addEventListener('click', () => pressKey(ANDROID_KEYCODE.VOLUME_UP));
document.getElementById('btnPower').addEventListener('click', () => pressKey(ANDROID_KEYCODE.POWER));
document.getElementById('btnNotif').addEventListener('click', () => window.api.sendSimple(5)); // EXPAND_NOTIFICATION_PANEL
document.getElementById('btnRotate').addEventListener('click', () => window.api.sendSimple(11)); // ROTATE_DEVICE
document.getElementById('btnScreenOff').addEventListener('click', (e) => {
  screenOn = !screenOn;
  window.api.setDisplayPower(screenOn);
  e.currentTarget.classList.toggle('active', !screenOn);
  showToast(screenOn ? 'layar HP dinyalakan' : 'layar HP dimatikan (mirror tetap jalan)');
});
document.getElementById('btnFullscreen').addEventListener('click', () => window.api.toggleFullscreen());

// =============================================================================
// Drag & drop: .apk -> install, anything else -> push to /sdcard/Download
// =============================================================================
let dragCounter = 0;

stage.addEventListener('dragover', (e) => { e.preventDefault(); });
stage.addEventListener('dragenter', (e) => {
  e.preventDefault();
  dragCounter++;
  dropOverlay.classList.add('active');
});
stage.addEventListener('dragleave', () => {
  dragCounter = Math.max(0, dragCounter - 1);
  if (dragCounter === 0) dropOverlay.classList.remove('active');
});
stage.addEventListener('drop', async (e) => {
  e.preventDefault();
  dragCounter = 0;
  dropOverlay.classList.remove('active');
  const files = Array.from(e.dataTransfer.files || []);
  if (files.length === 0) return;
  for (const file of files) {
    const filePath = window.api.getPathForFile(file); // file.path sudah dihapus di Electron baru
    if (!filePath) continue;
    showToast(`mengirim ${file.name}…`);
    const res = await window.api.installFile(filePath);
    showToast(res.message, !res.success);
  }
});
