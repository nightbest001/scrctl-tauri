// Shim: UI lama (Electron) memanggil window.api.*. File ini menerjemahkannya ke
// invoke/listen Tauri v2 supaya renderer.js & mirror.js tidak perlu diubah.
(function () {
  'use strict';
  var T = window.__TAURI__;
  if (!T) { console.error('window.__TAURI__ tidak ada — cek withGlobalTauri di tauri.conf.json'); return; }

  var invoke = function (cmd, args) { return T.core.invoke(cmd, args); };
  var fire = function (cmd, args) { invoke(cmd, args).catch(function () {}); };
  var listen = function (name, cb) {
    T.event.listen(name, function (e) { cb(e.payload); }).catch(function () {});
  };

  // ---- handler yang didaftarkan UI ----
  var h = { init: [], session: [], packet: [], audio: [] };
  var emit = function (list, payload) { for (var i = 0; i < list.length; i++) { try { list[i](payload); } catch (e) { console.error(e); } } };

  window.api = {
    listSavedDevices: function () { return invoke('devices_list'); },
    saveDevices: function (list) { return invoke('devices_save', { list: list }); },
    getSettings: function () { return invoke('settings_get'); },
    setSettings: function (s) { return invoke('settings_set', { next: s }); },
    getDefaultSettings: function () { return invoke('settings_defaults'); },
    confirmDialog: function (opts) { return invoke('dialog_confirm', opts || {}); },

    adbConnect: function (ip, port) { return invoke('adb_connect', { ip: ip, port: port }); },
    adbDisconnect: function (serial) { return invoke('adb_disconnect', { serial: serial }); },
    adbDevices: function () { return invoke('adb_devices'); },
    adbScan: function () { return invoke('adb_scan'); },
    pairDevice: function (ip, port, code) { return invoke('adb_pair', { ip: ip, port: port, code: code }); },

    startMirror: function (serial, deviceName) { return invoke('mirror_start', { serial: serial, deviceName: deviceName }); },
    stopMirror: function () { return invoke('mirror_stop'); },
    sendTouch: function (msg) { fire('mirror_touch', { msg: msg }); },
    sendScroll: function (msg) { fire('mirror_scroll', { msg: msg }); },
    sendKey: function (msg) { fire('mirror_key', { msg: msg }); },
    sendText: function (text) { fire('mirror_text', { text: String(text) }); },
    sendHidKeyboard: function (report) { fire('mirror_hid_keyboard', { report: Array.from(report) }); },
    sendHidMouse: function (report) { fire('mirror_hid_mouse', { report: Array.from(report) }); },
    sendBackOrScreenOn: function (action) { fire('mirror_back_or_screen_on', { action: action }); },
    pasteToDevice: function () { fire('mirror_paste'); },
    copyFromDevice: function (cut) { fire('mirror_copy', { cut: !!cut }); },
    setDisplayPower: function (on) { fire('mirror_display_power', { on: !!on }); },
    sendSimple: function (type) { fire('mirror_simple', { kind: type }); },
    toggleFullscreen: function () { return invoke('mirror_toggle_fullscreen'); },
    focusMirrorWindow: function () { return invoke('mirror_focus'); },

    windowMinimize: function () { fire('window_minimize'); },
    windowMaximize: function () { fire('window_maximize'); },
    windowClose: function () { fire('window_close'); },
    // Drag & drop file ditangani di sisi Rust (event window), jadi path tidak dipakai di sini.
    getPathForFile: function () { return ''; },
    getAppVersion: function () { return T.app.getVersion(); },
    openUrl: function (url) { return invoke('open_url', { url: url }); },
    downloadUpdate: function (url, total) { return invoke('update_download', { url: url, total: total }); },
    installUpdate: function () { return invoke('update_install'); },
    onUpdateProgress: function (cb) { listen('update-progress', cb); },
    installFile: function (filePath) { return invoke('mirror_install_file', { filePath: filePath }); },

    watchNotifications: function (list) { return invoke('notif_watch', { list: list }); },
    onNotification: function (cb) { listen('notif-new', cb); },
    onLog: function (cb) { listen('log', cb); },
    onMirrorInit: function (cb) { h.init.push(cb); },
    onMirrorSession: function (cb) { h.session.push(cb); },
    onMirrorPacket: function (cb) { h.packet.push(cb); },
    onMirrorAudio: function (cb) { h.audio.push(cb); },
    linkInfo: function () { return invoke('link_info'); },
    linkRegen: function () { return invoke('link_regen'); },
    linkCmd: function (cmd, on) { return invoke('link_cmd', { cmd: cmd, on: on === undefined ? null : on }); },
    linkSendFile: function () { return invoke('link_send_file'); },
    linkSend: function (msg) { return invoke('link_send', { msg: msg }); },
    linkOpenReceived: function (path) { return invoke('link_open_received', { path: path || null }); },
    onLinkMsg: function (cb) { listen('link-msg', cb); },
    onLinkFile: function (cb) { listen('link-file', cb); },
    phoneToggle: function (serial, kind, on) { return invoke('phone_toggle', { serial: serial, kind: kind, on: !!on }); },
    phoneOpenApp: function (serial, pkg) { return invoke('phone_open_app', { serial: serial, pkg: pkg }); },
    deckGet: function () { return invoke('deck_get'); },
    deckSave: function (deck) { return invoke('deck_save', { deck: deck }); },
    deckRun: function (kind, value) { return invoke('deck_run', { kind: kind, value: value }); },
    pcMediaState: function () { return invoke('pc_media_state'); },
    pcMediaCmd: function (a, pos) { return invoke('pc_media_cmd', { a: a, pos: pos === undefined ? null : pos }); },
    onPcMedia: function (cb) { listen('pc-media', cb); },
    onLinkState: function (cb) { listen('link-state', cb); },
    onLinkEvent: function (cb) { listen('link-event', cb); },
    onMirrorBattery: function (cb) { listen('mirror-battery', cb); },
    onMirrorStopped: function (cb) { listen('mirror-stopped', function () { cb(); }); }
  };

  // ---- Notification web -> notifikasi native Windows ----
  function NativeNotification(title, opts) {
    fire('notify_desktop', { title: String(title || ''), body: String((opts && opts.body) || '') });
  }
  NativeNotification.requestPermission = function () { return Promise.resolve('granted'); };
  Object.defineProperty(NativeNotification, 'permission', { get: function () { return 'granted'; } });
  window.Notification = NativeNotification;

  // ---- Title bar frameless: drag window + dobel klik maximize ----
  document.addEventListener('mousedown', function (e) {
    if (e.button !== 0) return;
    var t = e.target;
    if (!t || !t.closest) return;
    if (!t.closest('.titlebar, .toolbar, .batbar')) return;
    if (t.closest('button, input, select, textarea, a, .tb-ctl, .tb-winctl, .tb-group')) return;
    if (e.detail === 2) fire('window_maximize'); else fire('window_drag');
  });

  // ---- Window mirror: terima stream video/audio lewat Channel ----
  var dec = new TextDecoder();
  function toBuffer(raw) {
    if (raw instanceof ArrayBuffer) return raw;
    if (raw && raw.buffer instanceof ArrayBuffer) return raw.buffer.slice(raw.byteOffset, raw.byteOffset + raw.byteLength);
    if (Array.isArray(raw)) return Uint8Array.from(raw).buffer;
    return new ArrayBuffer(0);
  }
  function dispatch(raw) {
    var buf = toBuffer(raw);
    if (buf.byteLength < 1) return;
    var kind = new Uint8Array(buf, 0, 1)[0];
    var body = buf.slice(1);
    switch (kind) {
      case 0: emit(h.init, JSON.parse(dec.decode(body))); break;
      case 1: emit(h.session, JSON.parse(dec.decode(body))); break;
      case 2: emit(h.packet, { config: true, keyFrame: false, data: body }); break;
      case 3: emit(h.packet, { config: false, keyFrame: true, data: body }); break;
      case 4: emit(h.packet, { config: false, keyFrame: false, data: body }); break;
      case 5: emit(h.audio, { config: true, data: body }); break;
      case 6: emit(h.audio, { config: false, data: body }); break;
      default: break;
    }
  }

  window.addEventListener('load', function () {
    if (!document.getElementById('screenCanvas')) return; // bukan window mirror
    var ch = new T.core.Channel();
    ch.onmessage = dispatch;
    invoke('mirror_attach', { channel: ch }).catch(function (e) { console.error('mirror_attach gagal', e); });

    var overlay = document.getElementById('dropOverlay');
    listen('mirror-drag', function (state) {
      if (!overlay) return;
      if (state === 'enter') overlay.classList.add('active'); else overlay.classList.remove('active');
    });
    listen('mirror-toast', function (p) {
      if (typeof window.showToast === 'function') window.showToast(p.text, !!p.err);
    });
  });
})();
