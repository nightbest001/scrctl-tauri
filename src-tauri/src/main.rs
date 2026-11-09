// SCRCTL — port Electron -> Tauri v2 (Rust).
// Semua yang dulu ada di main.js: adb, client scrcpy (video/audio/control),
// UHID, clipboard, notifikasi HP, setelan, window.
//
// Stream video/audio dikirim ke window mirror lewat tauri::ipc::Channel (byte mentah):
//   byte pertama = jenis pesan, sisanya = isi. Lihat konstanta K_* dan renderer/tauri-shim.js.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use regex::Regex;
use serde::Deserialize;
use serde_json::{json, Map, Value};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::window::Color;
use tauri::{
    AppHandle, DragDropEvent, Emitter, Manager, State, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_notification::NotificationExt;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::OwnedReadHalf;
use tokio::net::TcpStream;
use tokio::process::Command;
use tokio::sync::{mpsc, oneshot, Mutex as AsyncMutex};

macro_rules! args {
    ($($x:expr),* $(,)?) => {
        vec![$($x.to_string()),*]
    };
}

// ---------------------------------------------------------------------------
// Konstanta
// ---------------------------------------------------------------------------
const SERVER_JAR: &[u8] = include_bytes!("../resources/scrcpy-server.jar");
const DEVICE_SERVER_PATH: &str = "/data/local/tmp/scrcpy-server.jar";
const SERVER_VERSION: &str = "4.1"; // harus sama persis dengan versi scrcpy-server.jar

// Argumen WebView2: default wry + izinkan audio autoplay di window mirror.
#[cfg(windows)]
const BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required";

// Jenis pesan ke renderer (byte pertama di Channel)
const K_INIT: u8 = 0; // + JSON {serial,deviceName,control,audio,keyboardUhid,mouseUhid}
const K_SESSION: u8 = 1; // + JSON {deviceName,width,height}
const K_VCONFIG: u8 = 2;
const K_VKEY: u8 = 3;
const K_VDELTA: u8 = 4;
const K_ACONFIG: u8 = 5;
const K_AUDIO: u8 = 6;

const CONFIG_FLAG: u64 = 0x4000_0000_0000_0000;
const KEY_FLAG: u64 = 0x2000_0000_0000_0000;

const UHID_KEYBOARD_ID: u16 = 1;
const UHID_MOUSE_ID: u16 = 2;

// Boot keyboard: 1 byte modifier, 1 byte reserved, 6 byte keycode. Output: 1 byte LED.
const HID_KEYBOARD_DESC: &[u8] = &[
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
    0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x05, 0x08, 0x19, 0x01,
    0x29, 0x05, 0x95, 0x05, 0x75, 0x01, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x01, 0x05, 0x07,
    0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00, 0xC0,
];

// Mouse relatif: 1 byte tombol (5 bit), dx, dy, wheel, hwheel (int8). Report 5 byte.
const HID_MOUSE_DESC: &[u8] = &[
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x05,
    0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03,
    0x81, 0x06, 0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81,
    0x06, 0xC0, 0xC0,
];

const APP_NAMES: &[(&str, &str)] = &[
    ("com.whatsapp", "WhatsApp"),
    ("com.whatsapp.w4b", "WhatsApp Business"),
    ("org.telegram.messenger", "Telegram"),
    ("org.thunderdog.challegram", "Telegram X"),
    ("com.instagram.android", "Instagram"),
    ("com.facebook.orca", "Messenger"),
    ("com.facebook.katana", "Facebook"),
    ("com.discord", "Discord"),
    ("com.google.android.gm", "Gmail"),
    ("com.google.android.apps.messaging", "Pesan"),
    ("com.android.mms", "Pesan"),
    ("com.twitter.android", "X"),
    ("com.zhiliaoapp.musically", "TikTok"),
    ("com.roblox.client", "Roblox"),
    ("com.google.android.youtube", "YouTube"),
    ("com.tencent.mm", "WeChat"),
    ("jp.naver.line.android", "LINE"),
    ("com.shopee.id", "Shopee"),
    ("com.gojek.app", "Gojek"),
    ("com.tokopedia.tkpd", "Tokopedia"),
    ("com.android.vending", "Play Store"),
];

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
type JoinHandle = tauri::async_runtime::JoinHandle<()>;

struct Session {
    id: u64,
    serial: String,
    port: u16,
    label: String,
    uhid_keyboard: bool,
    uhid_mouse: bool,
    tasks: Vec<JoinHandle>,
}

#[derive(Default)]
struct NotifState {
    devices: Vec<(String, String)>, // (serial, nama)
    seen: HashMap<String, HashMap<String, String>>,
}

struct AppState {
    data_dir: PathBuf,
    adb: PathBuf,
    session: AsyncMutex<Option<Session>>,
    next_id: AtomicU64,
    ctrl: Mutex<Option<mpsc::UnboundedSender<Vec<u8>>>>,
    flags: Mutex<(bool, bool)>, // (keyboardUhid, mouseUhid) sesi aktif
    serial: Mutex<Option<String>>,
    attach: Mutex<Option<oneshot::Sender<Channel<InvokeResponseBody>>>>,
    last_clip: Mutex<Option<String>>,
    notif: Mutex<NotifState>,
}

struct CmdOut {
    ok: bool,
    out: String,
    err: String,
}

// ---------------------------------------------------------------------------
// Helper umum
// ---------------------------------------------------------------------------
fn log(app: &AppHandle, text: impl Into<String>, is_err: bool) {
    let _ = app.emit("log", json!({ "text": text.into(), "isErr": is_err }));
}

fn toast(app: &AppHandle, text: impl Into<String>, is_err: bool) {
    let _ = app.emit("mirror-toast", json!({ "text": text.into(), "err": is_err }));
}

fn pick(out: &str, err: &str) -> String {
    if out.is_empty() {
        err.to_string()
    } else {
        out.to_string()
    }
}

fn read_json(path: &Path, fallback: Value) -> Value {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .unwrap_or(fallback)
}

fn write_json(path: &Path, data: &Value) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(s) = serde_json::to_string_pretty(data) {
        let _ = std::fs::write(path, s);
    }
}

fn vstr(v: &Option<Value>) -> String {
    match v {
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    }
}

// ---------------------------------------------------------------------------
// Setelan (dipetakan ke opsi server scrcpy atau perilaku client)
// ---------------------------------------------------------------------------
fn default_settings() -> Value {
    json!({
        "audio": false,
        "audioSource": "output",
        "control": true,
        "maxSize": 1024,
        "maxFps": 0,
        "videoBitRate": 8,
        "captureOrientation": "",
        "crop": "",
        "displayId": 0,
        "showTouches": false,
        "stayAwake": false,
        "turnScreenOff": false,
        "powerOffOnClose": false,
        "powerOn": true,
        "clipboardSync": true,
        "keyboardUhid": false,
        "mouseUhid": false,
        "downsizeOnError": true,
        "notifWatch": true,
        "notifDesktop": true
    })
}

fn merge_settings(saved: &Value) -> Value {
    let mut base: Map<String, Value> = match default_settings() {
        Value::Object(m) => m,
        _ => Map::new(),
    };
    if let Value::Object(m) = saved {
        for (k, v) in m {
            base.insert(k.clone(), v.clone());
        }
    }
    Value::Object(base)
}

fn load_settings(st: &AppState) -> Value {
    let saved = read_json(&st.data_dir.join("settings.json"), json!({}));
    merge_settings(&saved)
}

fn s_bool(s: &Value, k: &str) -> bool {
    match s.get(k) {
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0) != 0.0,
        Some(Value::String(x)) => !x.is_empty() && x != "false" && x != "0",
        _ => false,
    }
}

fn s_f64(s: &Value, k: &str) -> f64 {
    match s.get(k) {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
        Some(Value::String(x)) => x.trim().parse::<f64>().unwrap_or(0.0),
        _ => 0.0,
    }
}

fn s_str(s: &Value, k: &str) -> String {
    match s.get(k) {
        Some(Value::String(x)) => x.trim().to_string(),
        Some(Value::Number(n)) => {
            if n.as_f64().unwrap_or(0.0) == 0.0 {
                String::new()
            } else {
                n.to_string()
            }
        }
        _ => String::new(),
    }
}

fn valid_crop(c: &str) -> bool {
    let parts: Vec<&str> = c.split(':').collect();
    parts.len() == 4
        && parts
            .iter()
            .all(|p| !p.is_empty() && p.chars().all(|ch| ch.is_ascii_digit()))
}

fn build_server_args(s: &Value) -> Vec<String> {
    let audio = s_bool(s, "audio");
    let control = s_bool(s, "control");
    let max_size = (s_f64(s, "maxSize") as i64).max(0);
    let bit_rate_mbps = {
        let v = s_f64(s, "videoBitRate");
        if v == 0.0 {
            8.0
        } else {
            v
        }
    };
    let mut a: Vec<String> = vec![
        "log_level=info".to_string(),
        format!("audio={}", audio),
        format!("control={}", control),
        "tunnel_forward=true".to_string(),
        "send_device_meta=true".to_string(),
        "send_frame_meta=true".to_string(),
        "send_dummy_byte=true".to_string(),
        "cleanup=true".to_string(),
        format!("max_size={}", max_size),
        format!("video_bit_rate={}", (bit_rate_mbps * 1_000_000.0).round() as i64),
        format!("show_touches={}", s_bool(s, "showTouches")),
        format!("stay_awake={}", s_bool(s, "stayAwake")),
        format!("power_off_on_close={}", s_bool(s, "powerOffOnClose")),
        format!("power_on={}", s_bool(s, "powerOn")),
        format!("clipboard_autosync={}", s_bool(s, "clipboardSync") && control),
        format!("downsize_on_error={}", s_bool(s, "downsizeOnError")),
    ];
    if audio {
        a.push("audio_codec=opus".to_string());
        let src = s_str(s, "audioSource");
        a.push(format!("audio_source={}", if src.is_empty() { "output".to_string() } else { src }));
    }
    let fps = s_f64(s, "maxFps");
    if fps > 0.0 {
        a.push(format!("max_fps={}", fps as i64));
    }
    let orient = s_str(s, "captureOrientation");
    if !orient.is_empty() {
        a.push(format!("capture_orientation={}", orient));
    }
    let crop = s_str(s, "crop");
    if valid_crop(&crop) {
        a.push(format!("crop={}", crop));
    }
    let display = s_f64(s, "displayId");
    if display > 0.0 {
        a.push(format!("display_id={}", display as i64));
    }
    a
}

// ---------------------------------------------------------------------------
// adb
// ---------------------------------------------------------------------------
fn strip_verbatim(p: PathBuf) -> PathBuf {
    let s = p.to_string_lossy().to_string();
    match s.strip_prefix(r"\\?\") {
        Some(rest) => PathBuf::from(rest),
        None => p,
    }
}

// Cari adb bawaan (platform-tools yang ikut ter-bundle); kalau tidak ada pakai adb di PATH.
fn find_adb(app: &AppHandle) -> PathBuf {
    let exe_name = if cfg!(windows) { "adb.exe" } else { "adb" };
    let mut bases: Vec<PathBuf> = Vec::new();
    if let Ok(r) = app.path().resource_dir() {
        bases.push(r.clone());
        bases.push(r.join("resources"));
    }
    if let Ok(e) = std::env::current_exe() {
        if let Some(d) = e.parent() {
            bases.push(d.to_path_buf());
            bases.push(d.join("resources"));
        }
    }
    for b in bases {
        let p = b.join("platform-tools").join(exe_name);
        if p.exists() {
            return strip_verbatim(p);
        }
    }
    PathBuf::from("adb")
}

async fn run_cmd(app: &AppHandle, argv: Vec<String>) -> CmdOut {
    let adb = app.state::<AppState>().adb.clone();
    let mut cmd = Command::new(&adb);
    cmd.args(&argv)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    match cmd.output().await {
        Ok(o) => CmdOut {
            ok: o.status.success(),
            out: String::from_utf8_lossy(&o.stdout).to_string(),
            err: String::from_utf8_lossy(&o.stderr).to_string(),
        },
        Err(e) => {
            log(app, "adb tidak ditemukan — taruh adb di PATH atau pakai build yang membawa platform-tools", true);
            CmdOut { ok: false, out: String::new(), err: e.to_string() }
        }
    }
}

async fn get_device_name(app: &AppHandle, serial: &str) -> Option<String> {
    let model = run_cmd(app, args!["-s", serial, "shell", "getprop", "ro.product.model"]).await;
    let name = model.out.trim().to_string();
    if !name.is_empty() {
        return Some(name);
    }
    let fb = run_cmd(app, args!["-s", serial, "shell", "getprop", "ro.product.name"]).await;
    let name = fb.out.trim().to_string();
    if name.is_empty() {
        None
    } else {
        Some(name)
    }
}

fn parse_adb_devices(out: &str) -> Vec<Value> {
    let model_re = Regex::new(r"model:(\S+)").unwrap();
    out.split('\n')
        .skip(1)
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('*') && !l.starts_with("List of"))
        .map(|l| {
            let parts: Vec<&str> = l.split_whitespace().collect();
            let model = model_re
                .captures(l)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().replace('_', " "));
            json!({
                "serial": parts.get(0).copied().unwrap_or(""),
                "state": parts.get(1).copied().unwrap_or(""),
                "model": model
            })
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Persistence + dialog + window
// ---------------------------------------------------------------------------
#[tauri::command]
fn devices_list(st: State<'_, AppState>) -> Value {
    read_json(&st.data_dir.join("devices.json"), json!([]))
}

#[tauri::command]
fn devices_save(st: State<'_, AppState>, list: Value) -> bool {
    write_json(&st.data_dir.join("devices.json"), &list);
    true
}

#[tauri::command]
fn settings_get(st: State<'_, AppState>) -> Value {
    load_settings(&st)
}

#[tauri::command]
fn settings_set(st: State<'_, AppState>, next: Value) -> Value {
    let merged = merge_settings(&next);
    write_json(&st.data_dir.join("settings.json"), &merged);
    merged
}

#[tauri::command]
fn settings_defaults() -> Value {
    default_settings()
}

#[tauri::command]
async fn dialog_confirm(
    app: AppHandle,
    title: Option<String>,
    message: Option<String>,
    detail: Option<String>,
) -> bool {
    let mut msg = message.unwrap_or_default();
    if let Some(d) = detail {
        if !d.is_empty() {
            msg.push_str("\n\n");
            msg.push_str(&d);
        }
    }
    let (tx, rx) = oneshot::channel::<bool>();
    app.dialog()
        .message(msg)
        .title(title.unwrap_or_else(|| "Konfirmasi".to_string()))
        .kind(MessageDialogKind::Warning)
        .buttons(MessageDialogButtons::OkCancelCustom("Hapus".to_string(), "Batal".to_string()))
        .show(move |ok| {
            let _ = tx.send(ok);
        });
    rx.await.unwrap_or(false)
}

#[tauri::command]
fn notify_desktop(app: AppHandle, title: String, body: String) {
    let _ = app.notification().builder().title(title).body(body).show();
}

#[tauri::command]
fn window_minimize(window: WebviewWindow) {
    let _ = window.minimize();
}

#[tauri::command]
fn window_maximize(window: WebviewWindow) {
    if window.is_maximized().unwrap_or(false) {
        let _ = window.unmaximize();
    } else {
        let _ = window.maximize();
    }
}

#[tauri::command]
fn window_drag(window: WebviewWindow) {
    let _ = window.start_dragging();
}

#[tauri::command]
async fn window_close(app: AppHandle, window: WebviewWindow) {
    if window.label() == "main" {
        stop_session(&app, None, false).await;
        app.exit(0);
    } else {
        let _ = window.close();
    }
}

// ---------------------------------------------------------------------------
// Perintah adb
// ---------------------------------------------------------------------------
#[tauri::command]
async fn adb_connect(app: AppHandle, ip: String, port: Option<Value>) -> Value {
    let mut p = vstr(&port);
    if p.is_empty() || p == "0" {
        p = "5555".to_string();
    }
    let target = format!("{}:{}", ip.trim(), p);
    log(&app, format!("adb connect {}", target), false);
    let res = run_cmd(&app, args!["connect", &target]).await;
    let raw = pick(&res.out, &res.err);

    let re_conn = Regex::new(r"(?i)connected").unwrap();
    let re_bad = Regex::new(r"(?i)failed|cannot connect|unable").unwrap();
    let re_unreach =
        Regex::new(r"(?i)(timed out|no route to host|unreachable|refused|failed to connect)").unwrap();
    let success = re_conn.is_match(&raw) && !re_bad.is_match(&raw);
    let unreachable = !success && re_unreach.is_match(&raw);

    let device_name = if success { get_device_name(&app, &target).await } else { None };
    json!({
        "success": success,
        "raw": raw,
        "unreachable": unreachable,
        "deviceName": device_name,
        "serial": target
    })
}

#[tauri::command]
async fn adb_disconnect(app: AppHandle, serial: String) -> Value {
    log(&app, format!("adb disconnect {}", serial), false);
    let res = run_cmd(&app, args!["disconnect", &serial]).await;
    json!({ "success": res.ok, "raw": pick(&res.out, &res.err) })
}

#[tauri::command]
async fn adb_pair(app: AppHandle, ip: String, port: Option<Value>, code: Option<Value>) -> Value {
    let target = format!("{}:{}", ip.trim(), vstr(&port));
    log(&app, format!("adb pair {}", target), false);
    let res = run_cmd(&app, args!["pair", &target, vstr(&code)]).await;
    let re = Regex::new(r"(?i)(success|paired)").unwrap();
    let success = res.ok && re.is_match(&res.out);
    json!({ "success": success, "raw": pick(&res.out, &res.err) })
}

#[tauri::command]
async fn adb_devices(app: AppHandle) -> Value {
    let res = run_cmd(&app, args!["devices", "-l"]).await;
    Value::Array(parse_adb_devices(&res.out))
}

// Scan: device yang dikenal adb + device yang Wireless debugging-nya ON
// (mDNS: _adb-tls-connect = siap connect, _adb-tls-pairing = layar "Pair with code" terbuka).
#[tauri::command]
async fn adb_scan(app: AppHandle) -> Value {
    let (dev_res, mdns_res) = tokio::join!(
        run_cmd(&app, args!["devices", "-l"]),
        run_cmd(&app, args!["mdns", "services"])
    );
    let devices = parse_adb_devices(&dev_res.out);
    let re = Regex::new(r"^(\S+)\s+(_adb-tls-(?:connect|pairing)\._tcp\.?)\s+(\d{1,3}(?:\.\d{1,3}){3}):(\d+)")
        .unwrap();
    let mut services: Vec<Value> = Vec::new();
    for line in mdns_res.out.split('\n') {
        if let Some(c) = re.captures(line.trim()) {
            let kind = if c[2].contains("pairing") { "pairing" } else { "connect" };
            services.push(json!({
                "name": c[1].to_string(),
                "kind": kind,
                "ip": c[3].to_string(),
                "port": c[4].to_string()
            }));
        }
    }
    let adb_ok = dev_res.ok || !devices.is_empty();
    json!({ "devices": devices, "services": services, "adbOk": adb_ok })
}

// ---------------------------------------------------------------------------
// Notifikasi HP: baca `dumpsys notification --noredact` lewat adb tiap 4 detik,
// kirim yang BARU ke UI. Poll pertama per device hanya jadi baseline.
// ---------------------------------------------------------------------------
struct NotifItem {
    key: String,
    pkg: String,
    title: String,
    text: String,
}

fn grab(re: &Regex, body: &str) -> String {
    match re.captures(body) {
        Some(c) => c.get(1).map(|m| m.as_str().trim().to_string()).unwrap_or_default(),
        None => String::new(),
    }
}

fn parse_notifications(raw: &str) -> Vec<NotifItem> {
    let text = raw.replace('\r', "");
    let start_re = Regex::new(r"^\s{2}NotificationRecord\(").unwrap();
    let cont_re = Regex::new(r"^\s{4,}").unwrap();
    let pkg_re = Regex::new(r"pkg=(\S+)").unwrap();
    let key_re = Regex::new(r"key=(\S+?): Notification").unwrap();
    let id_re = Regex::new(r" id=(-?\d+)").unwrap();
    let tag_re = Regex::new(r" tag=(\S+)").unwrap();
    let flags_re = Regex::new(r"flags=0x([0-9a-fA-F]+)").unwrap();
    let title_re = Regex::new(r"(?m)android\.title=(?:\w+ \()?(.*?)\)?\s*$").unwrap();
    let bigtext_re = Regex::new(r"(?m)android\.bigText=(?:\w+ \()?(.*?)\)?\s*$").unwrap();
    let text_re = Regex::new(r"(?m)android\.text=(?:\w+ \()?(.*?)\)?\s*$").unwrap();

    // 1) kelompokkan baris per NotificationRecord
    let mut records: Vec<Vec<&str>> = Vec::new();
    let mut open = false;
    for line in text.split('\n') {
        if start_re.is_match(line) {
            records.push(vec![line]);
            open = true;
            continue;
        }
        if open {
            if cont_re.is_match(line) || line.trim().is_empty() {
                if let Some(r) = records.last_mut() {
                    r.push(line);
                }
            } else {
                open = false;
            }
        }
    }

    // 2) ambil field tiap record
    let mut out: Vec<NotifItem> = Vec::new();
    for rec in &records {
        let head = rec[0];
        let body = rec.join("\n");
        let pkg = pkg_re
            .captures(head)
            .and_then(|c| c.get(1))
            .map(|m| m.as_str().to_string())
            .unwrap_or_default();
        let key = match key_re.captures(head).and_then(|c| c.get(1)) {
            Some(m) => m.as_str().to_string(),
            None => {
                let id = id_re
                    .captures(head)
                    .and_then(|c| c.get(1))
                    .map(|m| m.as_str().to_string())
                    .unwrap_or_default();
                let tag = tag_re
                    .captures(head)
                    .and_then(|c| c.get(1))
                    .map(|m| m.as_str().to_string())
                    .unwrap_or_default();
                format!("{}|{}|{}", pkg, id, tag)
            }
        };
        let flags = flags_re
            .captures(head)
            .and_then(|c| c.get(1))
            .and_then(|m| u32::from_str_radix(m.as_str(), 16).ok())
            .unwrap_or(0);
        let mut title = grab(&title_re, &body);
        let mut text_msg = grab(&bigtext_re, &body);
        if text_msg.is_empty() {
            text_msg = grab(&text_re, &body);
        }
        // ongoing / foreground service / group summary
        let ongoing = (flags & 0x2) != 0 || (flags & 0x40) != 0 || (flags & 0x200) != 0;
        if !pkg.is_empty()
            && !ongoing
            && pkg != "android"
            && pkg != "com.android.systemui"
            && (!title.is_empty() || !text_msg.is_empty())
        {
            if title == "null" {
                title = String::new();
            }
            if text_msg == "null" {
                text_msg = String::new();
            }
            out.push(NotifItem { key, pkg, title, text: text_msg });
        }
    }
    out
}

fn app_label(pkg: &str) -> String {
    for (p, n) in APP_NAMES {
        if *p == pkg {
            return n.to_string();
        }
    }
    pkg.split('.').last().unwrap_or(pkg).to_string()
}

async fn notif_poll(app: &AppHandle) {
    let st = app.state::<AppState>();
    let settings = load_settings(&st);
    if !s_bool(&settings, "notifWatch") {
        return;
    }
    let devices: Vec<(String, String)> = st.notif.lock().unwrap().devices.clone();
    if devices.is_empty() {
        return;
    }
    for (serial, name) in devices {
        let res = run_cmd(
            app,
            args!["-s", &serial, "shell", "dumpsys", "notification", "--noredact"],
        )
        .await;
        if !res.ok || res.out.is_empty() {
            continue;
        }
        let items = parse_notifications(&res.out);
        let mut fresh: Vec<Value> = Vec::new();
        {
            let mut g = st.notif.lock().unwrap();
            let first = !g.seen.contains_key(&serial);
            let prev: HashMap<String, String> = g.seen.get(&serial).cloned().unwrap_or_default();
            let mut next: HashMap<String, String> = HashMap::new();
            for it in &items {
                let sig = format!("{}\u{1}{}", it.title, it.text);
                next.insert(it.key.clone(), sig.clone());
                if !first && prev.get(&it.key) != Some(&sig) {
                    let ts = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_millis() as u64)
                        .unwrap_or(0);
                    fresh.push(json!({
                        "serial": serial,
                        "device": if name.is_empty() { serial.clone() } else { name.clone() },
                        "pkg": it.pkg,
                        "app": app_label(&it.pkg),
                        "title": it.title,
                        "text": it.text,
                        "ts": ts
                    }));
                }
            }
            g.seen.insert(serial.clone(), next);
        }
        for f in fresh {
            let _ = app.emit("notif-new", f);
        }
    }
}

async fn notif_loop(app: AppHandle) {
    loop {
        tokio::time::sleep(Duration::from_secs(4)).await;
        notif_poll(&app).await;
    }
}

// Renderer mengirim daftar device yang sedang online (serial + nama).
#[tauri::command]
fn notif_watch(st: State<'_, AppState>, list: Value) -> bool {
    let mut devs: Vec<(String, String)> = Vec::new();
    if let Value::Array(arr) = &list {
        for d in arr {
            let serial = d.get("serial").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = d.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if !serial.is_empty() {
                devs.push((serial, name));
            }
        }
    }
    let mut g = st.notif.lock().unwrap();
    let alive: Vec<String> = devs.iter().map(|d| d.0.clone()).collect();
    g.seen.retain(|k, _| alive.contains(k));
    g.devices = devs;
    true
}

// ---------------------------------------------------------------------------
// Pesan control (client -> device), format scrcpy v4.1
// ---------------------------------------------------------------------------
fn kind_msg(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(1 + payload.len());
    v.push(kind);
    v.extend_from_slice(payload);
    v
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TouchMsg {
    action: u8,
    x: f64,
    y: f64,
    screen_width: u16,
    screen_height: u16,
    #[serde(default)]
    action_button: i32,
    #[serde(default)]
    buttons: i32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScrollMsg {
    x: f64,
    y: f64,
    screen_width: u16,
    screen_height: u16,
    #[serde(default)]
    h: f64,
    #[serde(default)]
    v: f64,
}

#[derive(Deserialize)]
struct KeyMsg {
    action: u8,
    keycode: i32,
    #[serde(default)]
    repeat: i32,
    #[serde(default)]
    meta: i32,
}

fn msg_touch(m: &TouchMsg) -> Vec<u8> {
    let mut b: Vec<u8> = Vec::with_capacity(32);
    b.push(2); // TYPE_INJECT_TOUCH_EVENT
    b.push(m.action);
    b.extend_from_slice(&(-1i64).to_be_bytes()); // POINTER_ID_MOUSE
    b.extend_from_slice(&(m.x.round() as i32).to_be_bytes());
    b.extend_from_slice(&(m.y.round() as i32).to_be_bytes());
    b.extend_from_slice(&m.screen_width.to_be_bytes());
    b.extend_from_slice(&m.screen_height.to_be_bytes());
    b.extend_from_slice(&0xffffu16.to_be_bytes()); // pressure = 1.0
    b.extend_from_slice(&m.action_button.to_be_bytes());
    b.extend_from_slice(&m.buttons.to_be_bytes());
    b
}

fn scroll_fixed(v: f64) -> i16 {
    let f = (v / 16.0).max(-1.0).min(1.0);
    (f * 32768.0).round().max(-32768.0).min(32767.0) as i16
}

fn msg_scroll(m: &ScrollMsg) -> Vec<u8> {
    let mut b: Vec<u8> = Vec::with_capacity(21);
    b.push(3); // TYPE_INJECT_SCROLL_EVENT
    b.extend_from_slice(&(m.x.round() as i32).to_be_bytes());
    b.extend_from_slice(&(m.y.round() as i32).to_be_bytes());
    b.extend_from_slice(&m.screen_width.to_be_bytes());
    b.extend_from_slice(&m.screen_height.to_be_bytes());
    b.extend_from_slice(&scroll_fixed(m.h).to_be_bytes());
    b.extend_from_slice(&scroll_fixed(m.v).to_be_bytes());
    b.extend_from_slice(&0i32.to_be_bytes()); // buttons
    b
}

fn msg_key(m: &KeyMsg) -> Vec<u8> {
    let mut b: Vec<u8> = Vec::with_capacity(14);
    b.push(0); // TYPE_INJECT_KEYCODE
    b.push(m.action);
    b.extend_from_slice(&m.keycode.to_be_bytes());
    b.extend_from_slice(&m.repeat.to_be_bytes());
    b.extend_from_slice(&m.meta.to_be_bytes());
    b
}

fn msg_text(text: &str) -> Vec<u8> {
    let bytes = text.as_bytes();
    let mut b: Vec<u8> = Vec::with_capacity(5 + bytes.len());
    b.push(1); // TYPE_INJECT_TEXT
    b.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    b.extend_from_slice(bytes);
    b
}

fn msg_set_clipboard(text: &str, paste: bool) -> Vec<u8> {
    let bytes = text.as_bytes();
    let mut b: Vec<u8> = Vec::with_capacity(14 + bytes.len());
    b.push(9); // TYPE_SET_CLIPBOARD
    b.extend_from_slice(&0i64.to_be_bytes()); // sequence (ACK tidak dilacak)
    b.push(if paste { 1 } else { 0 });
    b.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    b.extend_from_slice(bytes);
    b
}

fn uhid_create(id: u16, name: &str, desc: &[u8]) -> Vec<u8> {
    let name_bytes = name.as_bytes();
    let mut b: Vec<u8> = Vec::with_capacity(10 + name_bytes.len() + desc.len());
    b.push(12); // TYPE_UHID_CREATE
    b.extend_from_slice(&id.to_be_bytes());
    b.extend_from_slice(&0u16.to_be_bytes()); // vendor id
    b.extend_from_slice(&0u16.to_be_bytes()); // product id
    b.push(name_bytes.len() as u8);
    b.extend_from_slice(name_bytes);
    b.extend_from_slice(&(desc.len() as u16).to_be_bytes());
    b.extend_from_slice(desc);
    b
}

fn uhid_input(id: u16, data: &[u8]) -> Vec<u8> {
    let mut b: Vec<u8> = Vec::with_capacity(5 + data.len());
    b.push(13); // TYPE_UHID_INPUT
    b.extend_from_slice(&id.to_be_bytes());
    b.extend_from_slice(&(data.len() as u16).to_be_bytes());
    b.extend_from_slice(data);
    b
}

fn uhid_destroy(id: u16) -> Vec<u8> {
    let mut b: Vec<u8> = Vec::with_capacity(3);
    b.push(14); // TYPE_UHID_DESTROY
    b.extend_from_slice(&id.to_be_bytes());
    b
}

fn send_ctrl(st: &AppState, buf: Vec<u8>) -> bool {
    let g = st.ctrl.lock().unwrap();
    match &*g {
        Some(tx) => tx.send(buf).is_ok(),
        None => false,
    }
}

#[tauri::command]
fn mirror_touch(st: State<'_, AppState>, msg: TouchMsg) {
    send_ctrl(&st, msg_touch(&msg));
}

#[tauri::command]
fn mirror_scroll(st: State<'_, AppState>, msg: ScrollMsg) {
    send_ctrl(&st, msg_scroll(&msg));
}

#[tauri::command]
fn mirror_key(st: State<'_, AppState>, msg: KeyMsg) {
    send_ctrl(&st, msg_key(&msg));
}

#[tauri::command]
fn mirror_text(st: State<'_, AppState>, text: String) {
    send_ctrl(&st, msg_text(&text));
}

#[tauri::command]
fn mirror_hid_keyboard(st: State<'_, AppState>, report: Vec<u8>) {
    let enabled = st.flags.lock().unwrap().0;
    if enabled {
        send_ctrl(&st, uhid_input(UHID_KEYBOARD_ID, &report));
    }
}

#[tauri::command]
fn mirror_hid_mouse(st: State<'_, AppState>, report: Vec<u8>) {
    let enabled = st.flags.lock().unwrap().1;
    if enabled {
        send_ctrl(&st, uhid_input(UHID_MOUSE_ID, &report));
    }
}

#[tauri::command]
fn mirror_back_or_screen_on(st: State<'_, AppState>, action: u8) {
    send_ctrl(&st, vec![4, action]); // TYPE_BACK_OR_SCREEN_ON
}

#[tauri::command]
fn mirror_display_power(st: State<'_, AppState>, on: bool) {
    send_ctrl(&st, vec![10, if on { 1 } else { 0 }]); // TYPE_SET_DISPLAY_POWER
}

// Pesan tanpa payload: 5 = buka notifikasi, 6 = quick settings, 7 = tutup panel, 11 = rotate device
#[tauri::command]
fn mirror_simple(st: State<'_, AppState>, kind: u8) {
    if kind == 5 || kind == 6 || kind == 7 || kind == 11 {
        send_ctrl(&st, vec![kind]);
    }
}

// Ctrl+C / Ctrl+X: minta HP meng-copy/cut seleksi, hasil balik lewat DeviceMessage clipboard.
#[tauri::command]
fn mirror_copy(st: State<'_, AppState>, cut: bool) {
    send_ctrl(&st, vec![8, if cut { 2 } else { 1 }]); // TYPE_GET_CLIPBOARD
}

// Ctrl+V di window mirror: kirim clipboard desktop ke HP dan langsung paste.
#[tauri::command]
fn mirror_paste(st: State<'_, AppState>) {
    let text = match clip_read() {
        Some(t) if !t.is_empty() => t,
        _ => return,
    };
    *st.last_clip.lock().unwrap() = Some(text.clone());
    send_ctrl(&st, msg_set_clipboard(&text, true));
}

// ---------------------------------------------------------------------------
// Clipboard
// ---------------------------------------------------------------------------
fn clip_read() -> Option<String> {
    let mut c = arboard::Clipboard::new().ok()?;
    c.get_text().ok()
}

fn clip_write(text: &str) {
    if let Ok(mut c) = arboard::Clipboard::new() {
        let _ = c.set_text(text.to_string());
    }
}

async fn clipboard_task(app: AppHandle) {
    let st = app.state::<AppState>();
    *st.last_clip.lock().unwrap() = clip_read();
    loop {
        tokio::time::sleep(Duration::from_millis(1000)).await;
        let text = match clip_read() {
            Some(t) if !t.is_empty() => t,
            _ => continue,
        };
        let changed = {
            let mut g = st.last_clip.lock().unwrap();
            if g.as_deref() != Some(text.as_str()) {
                *g = Some(text.clone());
                true
            } else {
                false
            }
        };
        if changed {
            send_ctrl(&st, msg_set_clipboard(&text, false));
            log(&app, "clipboard desktop → HP tersinkron", false);
        }
    }
}

fn on_device_clipboard(app: &AppHandle, text: String) {
    let st = app.state::<AppState>();
    {
        let mut g = st.last_clip.lock().unwrap();
        if g.as_deref() == Some(text.as_str()) {
            return; // hindari ping-pong desktop -> HP -> desktop
        }
        *g = Some(text.clone());
    }
    clip_write(&text);
    log(app, "clipboard HP → desktop tersinkron", false);
}

// ---------------------------------------------------------------------------
// Task pembaca stream dari scrcpy-server
// ---------------------------------------------------------------------------
type Tx = mpsc::UnboundedSender<Vec<u8>>;

async fn video_task(app: AppHandle, id: u64, mut sock: TcpStream, tx: Tx, device_name: Option<String>) {
    let mut name = device_name;
    // dummy byte sudah dibaca saat connect. Lanjut: nama device (64) lalu codec id (4).
    let mut name_buf = [0u8; 64];
    let mut codec = [0u8; 4];
    let head_ok = sock.read_exact(&mut name_buf).await.is_ok() && sock.read_exact(&mut codec).await.is_ok();
    if head_ok {
        let n = String::from_utf8_lossy(&name_buf).trim_end_matches('\0').to_string();
        if !n.is_empty() {
            name = Some(n);
        }
        loop {
            let mut header = [0u8; 12];
            if sock.read_exact(&mut header).await.is_err() {
                break;
            }
            if (header[0] & 0x80) != 0 {
                // paket sesi: awal, atau setelah rotasi/resize
                let w = u32::from_be_bytes([header[4], header[5], header[6], header[7]]);
                let h = u32::from_be_bytes([header[8], header[9], header[10], header[11]]);
                let info = json!({ "deviceName": name, "width": w, "height": h });
                let _ = tx.send(kind_msg(K_SESSION, info.to_string().as_bytes()));
            } else {
                let mut b8 = [0u8; 8];
                b8.copy_from_slice(&header[..8]);
                let pts_flags = u64::from_be_bytes(b8);
                let size = u32::from_be_bytes([header[8], header[9], header[10], header[11]]) as usize;
                if size > 32 * 1024 * 1024 {
                    break;
                }
                let kind = if (pts_flags & CONFIG_FLAG) != 0 {
                    K_VCONFIG
                } else if (pts_flags & KEY_FLAG) != 0 {
                    K_VKEY
                } else {
                    K_VDELTA
                };
                let mut msg = vec![0u8; 1 + size];
                msg[0] = kind;
                if sock.read_exact(&mut msg[1..]).await.is_err() {
                    break;
                }
                let _ = tx.send(msg);
            }
        }
    }
    log(&app, "video socket ditutup", false);
    request_stop(&app, id, true);
}

// Audio: 4 byte codec id (0 = dimatikan, 1 = error konfigurasi), lalu paket dengan header 12 byte
// seperti video. Paket config Opus sudah berupa OpusHead mentah.
async fn audio_task(app: AppHandle, mut sock: TcpStream, tx: Tx) {
    let mut codec = [0u8; 4];
    if sock.read_exact(&mut codec).await.is_err() {
        return;
    }
    let codec_id = u32::from_be_bytes(codec);
    if codec_id <= 1 {
        if codec_id == 0 {
            log(&app, "audio dimatikan oleh device", false);
        } else {
            log(&app, "audio gagal dikonfigurasi di device (butuh Android 11+)", true);
        }
        // tetap baca & buang supaya socket tidak ditutup
        let mut sink = [0u8; 1024];
        loop {
            match sock.read(&mut sink).await {
                Ok(0) | Err(_) => return,
                Ok(_) => {}
            }
        }
    }
    loop {
        let mut header = [0u8; 12];
        if sock.read_exact(&mut header).await.is_err() {
            break;
        }
        let mut b8 = [0u8; 8];
        b8.copy_from_slice(&header[..8]);
        let pts_flags = u64::from_be_bytes(b8);
        let size = u32::from_be_bytes([header[8], header[9], header[10], header[11]]) as usize;
        if size > 8 * 1024 * 1024 {
            break;
        }
        let kind = if (pts_flags & CONFIG_FLAG) != 0 { K_ACONFIG } else { K_AUDIO };
        let mut msg = vec![0u8; 1 + size];
        msg[0] = kind;
        if sock.read_exact(&mut msg[1..]).await.is_err() {
            break;
        }
        let _ = tx.send(msg);
    }
}

// Device -> client (DeviceMessage): clipboard, ack clipboard, uhid output.
async fn control_reader(app: AppHandle, mut r: OwnedReadHalf) {
    loop {
        let mut t = [0u8; 1];
        if r.read_exact(&mut t).await.is_err() {
            break;
        }
        match t[0] {
            0 => {
                // TYPE_CLIPBOARD
                let mut l = [0u8; 4];
                if r.read_exact(&mut l).await.is_err() {
                    break;
                }
                let len = u32::from_be_bytes(l) as usize;
                if len > 4 * 1024 * 1024 {
                    break;
                }
                let mut b = vec![0u8; len];
                if r.read_exact(&mut b).await.is_err() {
                    break;
                }
                on_device_clipboard(&app, String::from_utf8_lossy(&b).to_string());
            }
            1 => {
                // TYPE_ACK_CLIPBOARD
                let mut b = [0u8; 8];
                if r.read_exact(&mut b).await.is_err() {
                    break;
                }
            }
            2 => {
                // TYPE_UHID_OUTPUT: id(u16) len(u16) data
                let mut h = [0u8; 4];
                if r.read_exact(&mut h).await.is_err() {
                    break;
                }
                let len = u16::from_be_bytes([h[2], h[3]]) as usize;
                let mut b = vec![0u8; len];
                if r.read_exact(&mut b).await.is_err() {
                    break;
                }
            }
            _ => {}
        }
    }
    log(&app, "control socket ditutup", false);
}

// Coba connect sampai server di HP siap. Dengan `adb forward`, connect lokal selalu sukses,
// jadi kesiapan dicek lewat dummy byte yang dikirim server tepat setelah accept.
async fn connect_video(port: u16) -> Result<TcpStream, String> {
    let mut last = "timeout".to_string();
    for _ in 0..40 {
        match TcpStream::connect(("127.0.0.1", port)).await {
            Ok(mut s) => {
                let mut b = [0u8; 1];
                match tokio::time::timeout(Duration::from_millis(1500), s.read_exact(&mut b)).await {
                    Ok(Ok(_)) => return Ok(s),
                    Ok(Err(e)) => last = e.to_string(),
                    Err(_) => last = "timeout menunggu server".to_string(),
                }
            }
            Err(e) => last = e.to_string(),
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    Err(last)
}

async fn connect_plain(port: u16) -> Result<TcpStream, String> {
    match tokio::time::timeout(Duration::from_secs(5), TcpStream::connect(("127.0.0.1", port))).await {
        Ok(Ok(s)) => Ok(s),
        Ok(Err(e)) => Err(e.to_string()),
        Err(_) => Err("timeout".to_string()),
    }
}

async fn free_port() -> std::io::Result<u16> {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let p = l.local_addr()?.port();
    drop(l);
    Ok(p)
}

// ---------------------------------------------------------------------------
// Session mirror
// ---------------------------------------------------------------------------
fn request_stop(app: &AppHandle, id: u64, notify: bool) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        stop_session(&app, Some(id), notify).await;
    });
}

// only_id = Some(id): hanya hentikan kalau sesi aktif memang itu (hindari menghentikan sesi baru).
async fn stop_session(app: &AppHandle, only_id: Option<u64>, notify: bool) {
    let st = app.state::<AppState>();
    let taken = {
        let mut g = st.session.lock().await;
        let matches = match (&*g, only_id) {
            (Some(s), Some(id)) => s.id == id,
            (Some(_), None) => true,
            _ => false,
        };
        let t = if matches { g.take() } else { None };
        t
    };
    let s = match taken {
        Some(s) => s,
        None => return,
    };

    // Hapus perangkat HID virtual di HP (aman kalau tidak pernah dibuat).
    if s.uhid_keyboard {
        send_ctrl(&st, uhid_destroy(UHID_KEYBOARD_ID));
    }
    if s.uhid_mouse {
        send_ctrl(&st, uhid_destroy(UHID_MOUSE_ID));
    }
    if s.uhid_keyboard || s.uhid_mouse {
        tokio::time::sleep(Duration::from_millis(80)).await;
    }

    *st.ctrl.lock().unwrap() = None;
    *st.serial.lock().unwrap() = None;
    *st.attach.lock().unwrap() = None;
    *st.last_clip.lock().unwrap() = None;
    *st.flags.lock().unwrap() = (false, false);

    for t in &s.tasks {
        t.abort();
    }
    let _ = run_cmd(app, args!["-s", &s.serial, "forward", "--remove", format!("tcp:{}", s.port)]).await;

    if let Some(w) = app.get_webview_window(&s.label) {
        let _ = w.destroy();
    }
    if notify {
        let _ = app.emit("mirror-stopped", Value::Null);
    }
}

async fn install_file_impl(app: &AppHandle, path: &str) -> (bool, String) {
    let st = app.state::<AppState>();
    let serial_opt: Option<String> = st.serial.lock().unwrap().clone();
    let serial = match serial_opt {
        Some(s) => s,
        None => return (false, "tidak ada device aktif".to_string()),
    };
    let p = Path::new(path);
    let ext = p
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_lowercase())
        .unwrap_or_default();
    let base = p
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();

    if ext == "apk" {
        log(app, format!("installing {}…", base), false);
        let res = run_cmd(app, args!["-s", &serial, "install", "-r", path]).await;
        let ok = Regex::new(r"(?i)Success").unwrap().is_match(&res.out);
        if ok {
            log(app, format!("APK terinstall: {}", base), false);
            return (true, format!("{} terinstall", base));
        }
        let detail = pick(&res.out, &res.err);
        log(app, format!("install gagal: {}", detail), true);
        return (false, if detail.is_empty() { "install gagal".to_string() } else { detail });
    }

    let dest = format!("/sdcard/Download/{}", base);
    log(app, format!("mengirim {} ke {}…", base, dest), false);
    let res = run_cmd(app, args!["-s", &serial, "push", path, &dest]).await;
    if res.ok {
        log(app, format!("terkirim ke {}", dest), false);
        (true, format!("dikirim ke {}", dest))
    } else {
        let detail = pick(&res.err, &res.out);
        log(app, format!("push gagal: {}", detail), true);
        (false, if detail.is_empty() { "push gagal".to_string() } else { detail })
    }
}

#[tauri::command]
async fn mirror_install_file(app: AppHandle, file_path: String) -> Value {
    let (ok, message) = install_file_impl(&app, &file_path).await;
    json!({ "success": ok, "message": message })
}

// Drag & drop ke window mirror: .apk -> adb install, selain itu -> push ke /sdcard/Download.
fn handle_drag(app: &AppHandle, ev: &DragDropEvent) {
    match ev {
        DragDropEvent::Enter { .. } => {
            let _ = app.emit("mirror-drag", "enter");
        }
        DragDropEvent::Leave => {
            let _ = app.emit("mirror-drag", "leave");
        }
        DragDropEvent::Drop { paths, .. } => {
            let _ = app.emit("mirror-drag", "leave");
            let paths: Vec<PathBuf> = paths.clone();
            let app2 = app.clone();
            tauri::async_runtime::spawn(async move {
                for p in paths {
                    let path = p.to_string_lossy().to_string();
                    let name = p
                        .file_name()
                        .and_then(|n| n.to_str())
                        .unwrap_or("file")
                        .to_string();
                    toast(&app2, format!("mengirim {}…", name), false);
                    let (ok, msg) = install_file_impl(&app2, &path).await;
                    toast(&app2, msg, !ok);
                }
            });
        }
        _ => {}
    }
}

#[tauri::command]
async fn mirror_start(app: AppHandle, serial: String, device_name: Option<String>) -> Value {
    // sesi lama (kalau ada) dihentikan diam-diam
    stop_session(&app, None, false).await;

    let st = app.state::<AppState>();
    let settings = load_settings(&st);
    let audio = s_bool(&settings, "audio");
    let control = s_bool(&settings, "control");
    let kb_uhid = control && s_bool(&settings, "keyboardUhid");
    let ms_uhid = control && s_bool(&settings, "mouseUhid");
    let fail = json!({ "success": false });

    // 1) tulis jar bawaan ke disk lalu push ke HP
    let _ = std::fs::create_dir_all(&st.data_dir);
    let jar_path = st.data_dir.join("scrcpy-server.jar");
    if let Err(e) = std::fs::write(&jar_path, SERVER_JAR) {
        log(&app, format!("gagal menulis scrcpy-server.jar: {}", e), true);
        return fail;
    }
    log(&app, format!("pushing scrcpy-server.jar ke {}…", serial), false);
    let jar_str = jar_path.to_string_lossy().to_string();
    let push = run_cmd(&app, args!["-s", &serial, "push", jar_str, DEVICE_SERVER_PATH]).await;
    if !push.ok {
        log(&app, format!("push gagal: {}", pick(&push.err, &push.out)), true);
        return fail;
    }

    // 2) port lokal + adb forward
    let port = match free_port().await {
        Ok(p) => p,
        Err(e) => {
            log(&app, format!("gagal ambil port: {}", e), true);
            return fail;
        }
    };
    let fwd = run_cmd(&app, args!["-s", &serial, "forward", format!("tcp:{}", port), "localabstract:scrcpy"]).await;
    if !fwd.ok {
        log(&app, format!("adb forward gagal: {}", pick(&fwd.err, &fwd.out)), true);
        return fail;
    }

    // 3) jalankan scrcpy-server di HP
    let server_args = build_server_args(&settings);
    let shown: Vec<String> = server_args
        .iter()
        .filter(|a| {
            !a.starts_with("log_level")
                && !a.starts_with("send_")
                && !a.starts_with("tunnel")
                && !a.starts_with("cleanup")
        })
        .cloned()
        .collect();
    log(&app, format!("menjalankan scrcpy-server di HP… ({})", shown.join(" ")), false);

    let mut argv: Vec<String> = vec![
        "-s".to_string(),
        serial.clone(),
        "shell".to_string(),
        format!("CLASSPATH={}", DEVICE_SERVER_PATH),
        "app_process".to_string(),
        "/".to_string(),
        "com.genymobile.scrcpy.Server".to_string(),
        SERVER_VERSION.to_string(),
    ];
    argv.extend(server_args.iter().cloned());

    let mut cmd = Command::new(&st.adb);
    cmd.args(&argv)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000);
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            log(&app, format!("gagal menjalankan adb shell: {}", e), true);
            let _ = run_cmd(&app, args!["-s", &serial, "forward", "--remove", format!("tcp:{}", port)]).await;
            return fail;
        }
    };

    // 4) connect socket. Urutan accept di server: video, audio (kalau aktif), control (kalau aktif).
    let video = match connect_video(port).await {
        Ok(s) => s,
        Err(e) => {
            log(&app, format!("gagal connect ke scrcpy-server: {}", e), true);
            let _ = child.kill().await;
            let _ = run_cmd(&app, args!["-s", &serial, "forward", "--remove", format!("tcp:{}", port)]).await;
            return fail;
        }
    };
    let mut audio_sock: Option<TcpStream> = None;
    let mut control_sock: Option<TcpStream> = None;
    let mut connect_err: Option<String> = None;
    if audio {
        match connect_plain(port).await {
            Ok(s) => audio_sock = Some(s),
            Err(e) => connect_err = Some(e),
        }
    }
    if connect_err.is_none() && control {
        match connect_plain(port).await {
            Ok(s) => control_sock = Some(s),
            Err(e) => connect_err = Some(e),
        }
    }
    if let Some(e) = connect_err {
        log(&app, format!("gagal connect ke scrcpy-server: {}", e), true);
        let _ = child.kill().await;
        let _ = run_cmd(&app, args!["-s", &serial, "forward", "--remove", format!("tcp:{}", port)]).await;
        return fail;
    }

    // 5) rakit sesi
    let id = st.next_id.fetch_add(1, Ordering::SeqCst) + 1;
    let label = format!("mirror{}", id);
    let (out_tx, out_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (att_tx, att_rx) = oneshot::channel::<Channel<InvokeResponseBody>>();
    *st.attach.lock().unwrap() = Some(att_tx);
    *st.serial.lock().unwrap() = Some(serial.clone());
    *st.flags.lock().unwrap() = (kb_uhid, ms_uhid);

    let mut tasks: Vec<JoinHandle> = Vec::new();

    // proses adb shell: log output server, hentikan sesi kalau server mati
    {
        let app_c = app.clone();
        tasks.push(tauri::async_runtime::spawn(async move {
            let so = child.stdout.take();
            let se = child.stderr.take();
            if let Some(o) = so {
                let a = app_c.clone();
                tauri::async_runtime::spawn(async move {
                    let mut lines = BufReader::new(o).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        let t = line.trim();
                        if !t.is_empty() {
                            log(&a, format!("[server] {}", t), false);
                        }
                    }
                });
            }
            if let Some(e) = se {
                let a = app_c.clone();
                tauri::async_runtime::spawn(async move {
                    let mut lines = BufReader::new(e).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        let t = line.trim();
                        if !t.is_empty() {
                            log(&a, format!("[server] {}", t), true);
                        }
                    }
                });
            }
            let code = match child.wait().await {
                Ok(status) => status.code(),
                Err(_) => None,
            };
            let code_txt = match code {
                Some(c) => c.to_string(),
                None => "?".to_string(),
            };
            log(&app_c, format!("scrcpy-server berhenti (code {})", code_txt), false);
            request_stop(&app_c, id, true);
        }));
    }

    // video
    tasks.push(tauri::async_runtime::spawn(video_task(
        app.clone(),
        id,
        video,
        out_tx.clone(),
        device_name.clone(),
    )));

    // audio
    if let Some(a) = audio_sock {
        tasks.push(tauri::async_runtime::spawn(audio_task(app.clone(), a, out_tx.clone())));
    }

    // control: reader + writer
    if let Some(c) = control_sock {
        let (cr, mut cw) = c.into_split();
        let (ctrl_tx, mut ctrl_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        *st.ctrl.lock().unwrap() = Some(ctrl_tx);
        tasks.push(tauri::async_runtime::spawn(control_reader(app.clone(), cr)));
        tasks.push(tauri::async_runtime::spawn(async move {
            while let Some(b) = ctrl_rx.recv().await {
                if cw.write_all(&b).await.is_err() {
                    break;
                }
            }
        }));
        if s_bool(&settings, "clipboardSync") {
            tasks.push(tauri::async_runtime::spawn(clipboard_task(app.clone())));
        }
        if kb_uhid {
            send_ctrl(&st, uhid_create(UHID_KEYBOARD_ID, "SCRCTL Keyboard", HID_KEYBOARD_DESC));
            log(&app, "UHID dibuat: SCRCTL Keyboard", false);
        }
        if ms_uhid {
            send_ctrl(&st, uhid_create(UHID_MOUSE_ID, "SCRCTL Mouse", HID_MOUSE_DESC));
            log(&app, "UHID dibuat: SCRCTL Mouse", false);
        }
        if s_bool(&settings, "turnScreenOff") {
            let app_c = app.clone();
            tasks.push(tauri::async_runtime::spawn(async move {
                tokio::time::sleep(Duration::from_millis(800)).await;
                let st2 = app_c.state::<AppState>();
                send_ctrl(&st2, vec![10, 0]); // TYPE_SET_DISPLAY_POWER off
            }));
        }
    }

    // penerus paket ke window mirror: menunggu window attach dulu supaya
    // config + keyframe awal tidak hilang.
    {
        let init = json!({
            "serial": serial,
            "deviceName": device_name,
            "control": control,
            "audio": audio,
            "keyboardUhid": kb_uhid,
            "mouseUhid": ms_uhid
        });
        tasks.push(tauri::async_runtime::spawn(async move {
            let mut rx = out_rx;
            let ch = match att_rx.await {
                Ok(c) => c,
                Err(_) => return,
            };
            let _ = ch.send(InvokeResponseBody::Raw(kind_msg(K_INIT, init.to_string().as_bytes())));
            while let Some(m) = rx.recv().await {
                if ch.send(InvokeResponseBody::Raw(m)).is_err() {
                    break;
                }
            }
        }));
    }

    *st.session.lock().await = Some(Session {
        id,
        serial: serial.clone(),
        port,
        label: label.clone(),
        uhid_keyboard: kb_uhid,
        uhid_mouse: ms_uhid,
        tasks,
    });

    // 6) window mirror
    let title = device_name.clone().unwrap_or_else(|| serial.clone());
    let mut builder = WebviewWindowBuilder::new(&app, label.clone(), WebviewUrl::App("mirror.html".into()))
        .title(title)
        .inner_size(500.0, 840.0)
        .min_inner_size(480.0, 480.0)
        .decorations(false)
        .background_color(Color(17, 17, 19, 255));
    #[cfg(windows)]
    {
        builder = builder.additional_browser_args(BROWSER_ARGS);
    }
    let win = match builder.build() {
        Ok(w) => w,
        Err(e) => {
            log(&app, format!("gagal membuka window mirror: {}", e), true);
            stop_session(&app, Some(id), false).await;
            return fail;
        }
    };
    let app_ev = app.clone();
    win.on_window_event(move |ev| match ev {
        WindowEvent::Destroyed => request_stop(&app_ev, id, true),
        WindowEvent::DragDrop(d) => handle_drag(&app_ev, d),
        _ => {}
    });

    log(&app, "terhubung, menunggu frame video…", false);
    json!({ "success": true })
}

// Window mirror memanggil ini setelah load: serahkan Channel untuk stream video/audio.
#[tauri::command]
fn mirror_attach(st: State<'_, AppState>, channel: Channel<InvokeResponseBody>) -> bool {
    let tx = st.attach.lock().unwrap().take();
    match tx {
        Some(t) => t.send(channel).is_ok(),
        None => false,
    }
}

#[tauri::command]
async fn mirror_stop(app: AppHandle) -> bool {
    stop_session(&app, None, true).await;
    true
}

#[tauri::command]
fn mirror_toggle_fullscreen(window: WebviewWindow) -> bool {
    let next = !window.is_fullscreen().unwrap_or(false);
    let _ = window.set_fullscreen(next);
    next
}

#[tauri::command]
async fn mirror_focus(app: AppHandle) -> bool {
    let st = app.state::<AppState>();
    let label = {
        let g = st.session.lock().await;
        g.as_ref().map(|s| s.label.clone())
    };
    match label {
        Some(l) => match app.get_webview_window(&l) {
            Some(w) => {
                let _ = w.set_focus();
                true
            }
            None => false,
        },
        None => false,
    }
}

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let _ = std::fs::create_dir_all(&data_dir);
            let adb = find_adb(app.handle());
            app.manage(AppState {
                data_dir,
                adb,
                session: AsyncMutex::new(None),
                next_id: AtomicU64::new(0),
                ctrl: Mutex::new(None),
                flags: Mutex::new((false, false)),
                serial: Mutex::new(None),
                attach: Mutex::new(None),
                last_clip: Mutex::new(None),
                notif: Mutex::new(NotifState::default()),
            });
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                notif_loop(handle).await;
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            // Window utama ditutup (mis. Alt+F4) -> bersihkan sesi lalu keluar.
            if window.label() == "main" {
                if let WindowEvent::Destroyed = event {
                    let app = window.app_handle().clone();
                    tauri::async_runtime::spawn(async move {
                        stop_session(&app, None, false).await;
                        app.exit(0);
                    });
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            devices_list,
            devices_save,
            settings_get,
            settings_set,
            settings_defaults,
            dialog_confirm,
            notify_desktop,
            window_minimize,
            window_maximize,
            window_drag,
            window_close,
            adb_connect,
            adb_disconnect,
            adb_pair,
            adb_devices,
            adb_scan,
            notif_watch,
            mirror_start,
            mirror_attach,
            mirror_stop,
            mirror_touch,
            mirror_scroll,
            mirror_key,
            mirror_text,
            mirror_hid_keyboard,
            mirror_hid_mouse,
            mirror_back_or_screen_on,
            mirror_display_power,
            mirror_simple,
            mirror_copy,
            mirror_paste,
            mirror_toggle_fullscreen,
            mirror_focus,
            mirror_install_file,
        ])
        .run(tauri::generate_context!())
        .expect("gagal menjalankan SCRCTL");
}
