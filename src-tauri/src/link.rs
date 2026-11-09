// Orbit Link — server TCP di PC untuk app Android "Orbit Link".
//
// Protokol (v1): frame = u32 BE panjang | u8 jenis | isi.
//   jenis 0 = JSON, jenis 1 = potongan file (4 byte id BE + data).
// Handshake: PC kirim {t:"challenge", nonce}; HP balas {t:"hello", proof=HMAC-SHA256(kode, nonce)};
// PC jawab {t:"welcome"} atau {t:"deny"}. Kode tidak pernah dikirim lewat jaringan.
// NB: isi data (notifikasi, clipboard, file) belum dienkripsi — pakai di WiFi yang kamu percaya.
//
// Fitur: notifikasi HP -> PC, clipboard dua arah, file dua arah, baterai + kontrol cepat.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use hmac::{Hmac, Mac};
use rand::Rng;
use serde_json::{json, Value};
use sha2::Sha256;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_dialog::DialogExt;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

use super::{clip_read, clip_write, log, read_json, write_json, AppState};

const DEFAULT_PORT: u16 = 5588;
const MAX_FRAME: usize = 1 << 20; // 1 MiB
const CHUNK: usize = 128 * 1024;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
enum Frame {
    Json(Value),
    Bin(Vec<u8>),
    Cam(Vec<u8>), // jenis 2: [putaran/90][cermin][JPEG] dari kamera HP
}

#[derive(Default)]
struct Cfg {
    token: String,
    port: u16,
}

#[derive(Default, Clone)]
struct Peer {
    connected: bool,
    name: String,
    battery: Option<u32>,
    charging: bool,
    torch: Option<bool>,
    dnd: Option<bool>,
    wifi: Option<bool>,
    bt: Option<bool>,
    hotspot: Option<bool>,
}

pub struct LinkState {
    cfg: Mutex<Cfg>,
    peer: Mutex<Peer>,
    tx: Mutex<Option<mpsc::Sender<Frame>>>,
    kick: Mutex<Option<oneshot::Sender<()>>>,
    clip: Mutex<Option<String>>,
    file_id: AtomicU32,
    conn: AtomicU64,
}

fn gen_token() -> String {
    const CH: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let mut rng = rand::thread_rng();
    (0..8).map(|_| CH[rng.gen_range(0..CH.len())] as char).collect()
}

fn new_nonce() -> String {
    let mut rng = rand::thread_rng();
    let bytes: Vec<u8> = (0..16).map(|_| rng.gen::<u8>()).collect();
    to_hex(&bytes)
}

fn to_hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

fn from_hex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok())
        .collect()
}

fn proof_ok(token: &str, nonce: &str, proof_hex: &str) -> bool {
    let p = match from_hex(proof_hex) {
        Some(p) => p,
        None => return false,
    };
    let mut mac = match Hmac::<Sha256>::new_from_slice(token.as_bytes()) {
        Ok(m) => m,
        Err(_) => return false,
    };
    mac.update(nonce.as_bytes());
    mac.verify_slice(&p).is_ok()
}

pub fn init(data_dir: &Path) -> LinkState {
    let path = data_dir.join("link.json");
    let v = read_json(&path, json!({}));
    let mut token = v.get("token").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let port = v
        .get("port")
        .and_then(|x| x.as_u64())
        .map(|p| p as u16)
        .filter(|p| *p >= 1024)
        .unwrap_or(DEFAULT_PORT);
    if token.len() != 8 {
        token = gen_token();
        write_json(&path, &json!({ "token": token, "port": port }));
    }
    LinkState {
        cfg: Mutex::new(Cfg { token, port }),
        peer: Mutex::new(Peer::default()),
        tx: Mutex::new(None),
        kick: Mutex::new(None),
        clip: Mutex::new(None),
        file_id: AtomicU32::new(1),
        conn: AtomicU64::new(0),
    }
}

// ---------------------------------------------------------------------------
// Frame I/O
// ---------------------------------------------------------------------------
async fn read_frame(r: &mut OwnedReadHalf) -> std::io::Result<Frame> {
    let mut len = [0u8; 4];
    r.read_exact(&mut len).await?;
    let n = u32::from_be_bytes(len) as usize;
    if n == 0 || n > MAX_FRAME {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "ukuran frame tidak valid"));
    }
    let mut buf = vec![0u8; n];
    r.read_exact(&mut buf).await?;
    let kind = buf[0];
    let body = buf.split_off(1);
    if kind == 0 {
        let v = serde_json::from_slice::<Value>(&body)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;
        Ok(Frame::Json(v))
    } else if kind == 2 {
        Ok(Frame::Cam(body))
    } else {
        Ok(Frame::Bin(body))
    }
}

async fn write_frame(w: &mut OwnedWriteHalf, f: &Frame) -> std::io::Result<()> {
    let mut out: Vec<u8>;
    match f {
        Frame::Json(v) => {
            let body = v.to_string().into_bytes();
            out = Vec::with_capacity(5 + body.len());
            out.extend_from_slice(&((body.len() + 1) as u32).to_be_bytes());
            out.push(0);
            out.extend_from_slice(&body);
        }
        Frame::Bin(b) | Frame::Cam(b) => {
            out = Vec::with_capacity(5 + b.len());
            out.extend_from_slice(&((b.len() + 1) as u32).to_be_bytes());
            out.push(if matches!(f, Frame::Cam(_)) { 2 } else { 1 });
            out.extend_from_slice(b);
        }
    }
    w.write_all(&out).await?;
    w.flush().await
}

// ---------------------------------------------------------------------------
// Util
// ---------------------------------------------------------------------------
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string()
}

fn human(n: u64) -> String {
    let f = n as f64;
    if f >= 1048576.0 {
        format!("{:.1} MB", f / 1048576.0)
    } else if f >= 1024.0 {
        format!("{:.0} KB", f / 1024.0)
    } else {
        format!("{} B", n)
    }
}

fn local_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .ok()
        .and_then(|sock| {
            sock.connect("8.8.8.8:80").ok()?; // UDP: tidak ada paket yang dikirim
            sock.local_addr().ok()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_default()
}

fn pc_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "PC".to_string())
}

fn safe_name(raw: &str) -> String {
    let base = raw.rsplit(|c| c == '/' || c == '\\').next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .map(|c| if matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*') || c.is_control() { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_matches('.').to_string();
    if cleaned.is_empty() {
        "file".to_string()
    } else {
        cleaned.chars().take(120).collect()
    }
}

fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let p = dir.join(name);
    if !p.exists() {
        return p;
    }
    let path = Path::new(name);
    let stem = path.file_stem().and_then(|x| x.to_str()).unwrap_or("file");
    let ext = path.extension().and_then(|x| x.to_str());
    for n in 1..1000 {
        let cand = match ext {
            Some(e) => format!("{} ({}).{}", stem, n, e),
            None => format!("{} ({})", stem, n),
        };
        let p = dir.join(cand);
        if !p.exists() {
            return p;
        }
    }
    dir.join(format!("{}-{}", now_ms(), name))
}

fn snapshot(app: &AppHandle) -> Value {
    let ls = app.state::<LinkState>();
    let p = ls.peer.lock().unwrap().clone();
    json!({
        "connected": p.connected,
        "name": p.name,
        "battery": p.battery,
        "charging": p.charging,
        "torch": p.torch,
        "dnd": p.dnd,
        "wifi": p.wifi,
        "bt": p.bt,
        "hotspot": p.hotspot
    })
}

fn emit_state(app: &AppHandle) {
    let _ = app.emit("link-state", snapshot(app));
}

fn event(app: &AppHandle, text: impl Into<String>, err: bool) {
    let _ = app.emit("link-event", json!({ "text": text.into(), "err": err }));
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------
pub async fn serve(app: AppHandle) {
    let port = app.state::<LinkState>().cfg.lock().unwrap().port;
    let listener = match TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => {
            log(&app, format!("Orbit Link: gagal buka port {}: {}", port, e), true);
            return;
        }
    };
    log(&app, format!("Orbit Link siap di port {}", port), false);
    loop {
        match listener.accept().await {
            Ok((sock, peer)) => {
                let a = app.clone();
                tauri::async_runtime::spawn(async move {
                    handle(a, sock, peer.to_string()).await;
                });
            }
            Err(_) => tokio::time::sleep(Duration::from_millis(300)).await,
        }
    }
}

struct Incoming {
    file: tokio::fs::File,
    path: PathBuf,
    got: u64,
    size: u64,
    name: String,
}

async fn handle(app: AppHandle, sock: TcpStream, addr: String) {
    let _ = sock.set_nodelay(true);
    let (mut r, mut w) = sock.into_split();
    let pc = pc_name();

    // --- handshake ---
    let nonce = new_nonce();
    let chal = Frame::Json(json!({ "t": "challenge", "nonce": nonce, "pc": pc }));
    if write_frame(&mut w, &chal).await.is_err() {
        return;
    }
    let first = match tokio::time::timeout(Duration::from_secs(10), read_frame(&mut r)).await {
        Ok(Ok(Frame::Json(v))) => v,
        _ => return,
    };
    let token = app.state::<LinkState>().cfg.lock().unwrap().token.clone();
    let proof = s(&first, "proof");
    if s(&first, "t") != "hello" || !proof_ok(&token, &nonce, &proof) {
        let _ = write_frame(&mut w, &Frame::Json(json!({ "t": "deny" }))).await;
        log(&app, format!("Orbit Link: {} ditolak (kode salah)", addr), true);
        tokio::time::sleep(Duration::from_secs(1)).await;
        return;
    }
    let name: String = s(&first, "name")
        .chars()
        .filter(|c| !c.is_control())
        .take(40)
        .collect();
    let name = if name.trim().is_empty() { "HP".to_string() } else { name };
    if write_frame(&mut w, &Frame::Json(json!({ "t": "welcome", "pc": pc }))).await.is_err() {
        return;
    }

    // --- sesi aktif ---
    let (tx, mut rx) = mpsc::channel::<Frame>(32);
    let (kick_tx, mut kick_rx) = oneshot::channel::<()>();
    let my_id;
    {
        let ls = app.state::<LinkState>();
        my_id = ls.conn.fetch_add(1, Ordering::SeqCst) + 1;
        if let Some(old) = ls.kick.lock().unwrap().replace(kick_tx) {
            let _ = old.send(()); // HP lain / sambungan lama diputus
        }
        *ls.tx.lock().unwrap() = Some(tx.clone());
        *ls.peer.lock().unwrap() = Peer { connected: true, name: name.clone(), ..Default::default() };
        *ls.clip.lock().unwrap() = clip_read(); // clipboard yang sudah ada tidak ikut terkirim
    }
    emit_state(&app);
    event(&app, format!("HP terhubung: {}", name), false);

    let writer = tauri::async_runtime::spawn(async move {
        while let Some(f) = rx.recv().await {
            if write_frame(&mut w, &f).await.is_err() {
                break;
            }
        }
    });

    // ping + dorong clipboard PC -> HP
    let helper = {
        let app2 = app.clone();
        let tx2 = tx.clone();
        tauri::async_runtime::spawn(async move {
            let mut tick = 0u32;
            loop {
                tokio::time::sleep(Duration::from_secs(1)).await;
                tick += 1;
                if tick % 10 == 0 && tx2.send(Frame::Json(json!({ "t": "ping" }))).await.is_err() {
                    break;
                }
                let text = match clip_read() {
                    Some(t) if !t.is_empty() && t.len() <= 200_000 => t,
                    _ => continue,
                };
                let changed = {
                    let ls = app2.state::<LinkState>();
                    let mut g = ls.clip.lock().unwrap();
                    let c = g.as_deref() != Some(text.as_str());
                    if c {
                        *g = Some(text.clone());
                    }
                    c
                };
                if changed && tx2.send(Frame::Json(json!({ "t": "clip", "text": text }))).await.is_err() {
                    break;
                }
            }
        })
    };

    let mut incoming: HashMap<u32, Incoming> = HashMap::new();
    loop {
        let fr = tokio::select! {
            _ = &mut kick_rx => break,
            res = tokio::time::timeout(Duration::from_secs(30), read_frame(&mut r)) => match res {
                Ok(Ok(f)) => f,
                _ => break,
            },
        };
        match fr {
            Frame::Json(v) => on_json(&app, &name, &mut incoming, v).await,
            Frame::Bin(b) => on_chunk(&mut incoming, b).await,
            Frame::Cam(b) => on_cam(&app, b),
        }
    }

    // --- bersih-bersih ---
    helper.abort();
    {
        let ls = app.state::<LinkState>();
        if ls.conn.load(Ordering::SeqCst) == my_id {
            *ls.tx.lock().unwrap() = None;
            *ls.kick.lock().unwrap() = None;
            *ls.peer.lock().unwrap() = Peer::default();
        }
    }
    drop(tx);
    writer.abort();
    for (_, inc) in incoming.drain() {
        let Incoming { file, path, .. } = inc;
        drop(file);
        let _ = tokio::fs::remove_file(&path).await; // file setengah jadi dibuang
    }
    emit_state(&app);
    event(&app, format!("HP terputus: {}", name), false);
}

// Frame kamera: teruskan ke UI sebagai base64 (halaman Kamera menggambarnya ke canvas).
fn on_cam(app: &AppHandle, b: Vec<u8>) {
    if b.len() < 3 {
        return;
    }
    use base64::Engine;
    let jpg = base64::engine::general_purpose::STANDARD.encode(&b[2..]);
    let _ = app.emit("link-cam", json!({ "rot": b[0], "mirror": b[1], "jpg": jpg }));
}

async fn on_chunk(inc: &mut HashMap<u32, Incoming>, b: Vec<u8>) {
    if b.len() < 4 {
        return;
    }
    let id = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
    if let Some(f) = inc.get_mut(&id) {
        if f.file.write_all(&b[4..]).await.is_ok() {
            f.got += (b.len() - 4) as u64;
        }
    }
}

async fn on_json(app: &AppHandle, name: &str, inc: &mut HashMap<u32, Incoming>, v: Value) {
    let t = s(&v, "t");
    match t.as_str() {
        "battery" => {
            let level = v.get("level").and_then(|x| x.as_u64()).map(|x| x.min(100) as u32);
            let charging = v.get("charging").and_then(|x| x.as_bool()).unwrap_or(false);
            {
                let ls = app.state::<LinkState>();
                let mut p = ls.peer.lock().unwrap();
                p.battery = level;
                p.charging = charging;
            }
            emit_state(app);
        }
        "status" => {
            {
                let ls = app.state::<LinkState>();
                let mut p = ls.peer.lock().unwrap();
                p.torch = v.get("torch").and_then(|x| x.as_bool());
                p.dnd = v.get("dnd").and_then(|x| x.as_bool());
                p.wifi = v.get("wifi").and_then(|x| x.as_bool());
                p.bt = v.get("bt").and_then(|x| x.as_bool());
                p.hotspot = v.get("hotspot").and_then(|x| x.as_bool());
            }
            emit_state(app);
        }
        "notif" => {
            let ts = v.get("ts").and_then(|x| x.as_u64()).unwrap_or_else(now_ms);
            let _ = app.emit(
                "notif-new",
                json!({
                    "serial": "link",
                    "device": name,
                    "pkg": s(&v, "pkg"),
                    "app": s(&v, "app"),
                    "title": s(&v, "title"),
                    "text": s(&v, "text"),
                    "ts": ts
                }),
            );
        }
        "clip" => {
            if let Some(text) = v.get("text").and_then(|x| x.as_str()) {
                {
                    let ls = app.state::<LinkState>();
                    *ls.clip.lock().unwrap() = Some(text.to_string()); // cegah balik ke HP
                }
                clip_write(text);
                event(app, "Clipboard HP → PC tersinkron", false);
            }
        }
        "file-start" => {
            let id = v.get("id").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            let size = v.get("size").and_then(|x| x.as_u64()).unwrap_or(0);
            let fname = safe_name(&s(&v, "name"));
            let dir = match app.path().download_dir() {
                Ok(d) => d.join("Orbit"),
                Err(_) => app.state::<AppState>().data_dir.join("received"),
            };
            let _ = tokio::fs::create_dir_all(&dir).await;
            let path = unique_path(&dir, &fname);
            match tokio::fs::File::create(&path).await {
                Ok(file) => {
                    inc.insert(id, Incoming { file, path, got: 0, size, name: fname });
                }
                Err(e) => event(app, format!("Gagal menyimpan {}: {}", fname, e), true),
            }
        }
        "file-end" => {
            let id = v.get("id").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            if let Some(mut f) = inc.remove(&id) {
                let _ = f.file.flush().await;
                let ok = f.size == 0 || f.got == f.size;
                if ok {
                    let _ = app.emit("link-file", json!({ "name": f.name, "path": f.path.to_string_lossy() }));
                    event(app, format!("File diterima: {} → {}", f.name, f.path.display()), false);
                } else {
                    event(app, format!("File {} tidak lengkap ({} dari {})", f.name, human(f.got), human(f.size)), true);
                }
            }
        }
        "event" => {
            let err = v.get("err").and_then(|x| x.as_bool()).unwrap_or(false);
            event(app, s(&v, "text"), err);
        }
        // fitur tambahan: musik, SMS, telepon, foto, apps — diolah di renderer/features.js
        "media" | "sms-threads" | "sms-thread" | "sms-sent" | "sms-changed" | "call-log" | "call-state" | "photos"
        | "apps" | "caps" | "cam-state" => {
            let _ = app.emit("link-msg", v);
        }
        _ => {}
    }
}

// ---------------------------------------------------------------------------
// Kirim file PC -> HP
// ---------------------------------------------------------------------------
async fn send_file(app: AppHandle, tx: mpsc::Sender<Frame>, path: PathBuf) {
    let name = path.file_name().and_then(|x| x.to_str()).unwrap_or("file").to_string();
    let mut f = match tokio::fs::File::open(&path).await {
        Ok(f) => f,
        Err(e) => {
            event(&app, format!("Gagal membuka {}: {}", name, e), true);
            return;
        }
    };
    let size = f.metadata().await.map(|m| m.len()).unwrap_or(0);
    let id = app.state::<LinkState>().file_id.fetch_add(1, Ordering::SeqCst);
    event(&app, format!("Mengirim {} ({})…", name, human(size)), false);
    if tx
        .send(Frame::Json(json!({ "t": "file-start", "id": id, "name": name, "size": size })))
        .await
        .is_err()
    {
        return;
    }
    let mut buf = vec![0u8; CHUNK];
    loop {
        let n = match f.read(&mut buf).await {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) => {
                event(&app, format!("Gagal membaca {}: {}", name, e), true);
                return;
            }
        };
        let mut out = Vec::with_capacity(4 + n);
        out.extend_from_slice(&id.to_be_bytes());
        out.extend_from_slice(&buf[..n]);
        if tx.send(Frame::Bin(out)).await.is_err() {
            return;
        }
    }
    let _ = tx.send(Frame::Json(json!({ "t": "file-end", "id": id }))).await;
    event(&app, format!("Terkirim ke HP: {}", name), false);
}

// ---------------------------------------------------------------------------
// Command untuk UI
// ---------------------------------------------------------------------------
#[tauri::command]
pub fn link_info(app: AppHandle) -> Value {
    let (token, port) = {
        let ls = app.state::<LinkState>();
        let c = ls.cfg.lock().unwrap();
        (c.token.clone(), c.port)
    };
    let mut v = snapshot(&app);
    v["token"] = json!(token);
    v["port"] = json!(port);
    v["ip"] = json!(local_ip());
    v["pc"] = json!(pc_name());
    v
}

#[tauri::command]
pub fn link_regen(app: AppHandle) -> Value {
    {
        let ls = app.state::<LinkState>();
        let token = gen_token();
        let port = {
            let mut c = ls.cfg.lock().unwrap();
            c.token = token.clone();
            c.port
        };
        let dir = app.state::<AppState>().data_dir.clone();
        write_json(&dir.join("link.json"), &json!({ "token": token, "port": port }));
        let kick = ls.kick.lock().unwrap().take();
        if let Some(k) = kick {
            let _ = k.send(()); // HP lama harus pairing ulang dengan kode baru
        }
    }
    link_info(app)
}

#[tauri::command]
pub async fn link_cmd(app: AppHandle, cmd: String, on: Option<bool>) -> bool {
    let tx = app.state::<LinkState>().tx.lock().unwrap().clone();
    match tx {
        Some(tx) => tx
            .send(Frame::Json(json!({ "t": "cmd", "cmd": cmd, "on": on })))
            .await
            .is_ok(),
        None => false,
    }
}

#[tauri::command]
pub async fn link_send_file(app: AppHandle) -> Value {
    let tx = app.state::<LinkState>().tx.lock().unwrap().clone();
    let tx = match tx {
        Some(t) => t,
        None => return json!({ "ok": false, "err": "HP belum terhubung" }),
    };
    let a2 = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || a2.dialog().file().blocking_pick_files()).await;
    let files = match picked {
        Ok(Some(list)) => list,
        _ => return json!({ "ok": false, "cancel": true }),
    };
    let mut paths: Vec<PathBuf> = Vec::new();
    for fp in files {
        if let Ok(p) = fp.into_path() {
            paths.push(p);
        }
    }
    if paths.is_empty() {
        return json!({ "ok": false, "cancel": true });
    }
    tauri::async_runtime::spawn(async move {
        for p in paths {
            send_file(app.clone(), tx.clone(), p).await;
        }
    });
    json!({ "ok": true })
}

/// Pesan dari UI ke HP (musik, SMS, telepon, foto, apps). Hanya jenis pesan yang dikenal yang diteruskan.
#[tauri::command]
pub async fn link_send(app: AppHandle, msg: Value) -> bool {
    let t = s(&msg, "t");
    if !matches!(t.as_str(), "mcmd" | "sms" | "calls" | "photos" | "apps" | "caps" | "cam") {
        return false;
    }
    let tx = app.state::<LinkState>().tx.lock().unwrap().clone();
    match tx {
        Some(tx) => tx.send(Frame::Json(msg)).await.is_ok(),
        None => false,
    }
}

fn received_dir(app: &AppHandle) -> PathBuf {
    match app.path().download_dir() {
        Ok(d) => d.join("Orbit"),
        Err(_) => app.state::<AppState>().data_dir.join("received"),
    }
}

/// Buka file yang diterima dari HP (hanya yang ada di folder Downloads\Orbit) atau folder itu sendiri.
#[tauri::command]
pub fn link_open_received(app: AppHandle, path: Option<String>) -> Result<(), String> {
    let dir = received_dir(&app);
    let _ = std::fs::create_dir_all(&dir);
    let dir = dir.canonicalize().map_err(|e| e.to_string())?;
    let target = match path {
        Some(p) if !p.is_empty() => {
            let c = PathBuf::from(&p).canonicalize().map_err(|e| e.to_string())?;
            if !c.starts_with(&dir) {
                return Err("path ditolak".into());
            }
            c
        }
        _ => dir,
    };
    super::open_with_shell(&super::strip_verbatim(target).to_string_lossy())
}
