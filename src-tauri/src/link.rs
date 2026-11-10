// Orbit Link — server TCP di PC untuk app Android "Orbit Link".
//
// Protokol v2: frame = u32 BE panjang | isi. Isi = jenis (u8) + data, dan setelah handshake seluruh isi
// dienkripsi AES-256-GCM (isi terkirim = nonce counter implisit + ciphertext + tag 16 byte).
//   jenis 0 = JSON, 1 = potongan file (4 byte id BE + data), 2 = frame kamera HP, 3 = layar PC.
// Handshake (teks biasa, hanya berisi nonce dan bukti):
//   PC  -> {t:"challenge", ver:2, nonce:S, pc}
//   HP  -> {t:"hello", ver:2, cnonce:C, name, proof}
//   PC  -> {t:"welcome", pc, proof} atau {t:"deny"}
// Kunci: master = PBKDF2-HMAC-SHA256(kode, "orbit-link-v2" | S | C, 100.000 putaran).
//   k_auth, k_c2s, k_s2c = HMAC-SHA256(master, "auth" | "c2s" | "s2c").
//   proof HP = HMAC(k_auth, "C" | S | C), proof PC = HMAC(k_auth, "S" | S | C): keduanya saling membuktikan kode.
//   Nonce AES-GCM = 4 byte nol + counter u64 BE per arah, jadi tidak pernah dipakai dua kali.
// Kode tidak pernah dikirim, dan semua data setelah handshake (notifikasi, clipboard, file, input, layar) terenkripsi.
//
// Fitur: notifikasi HP -> PC, clipboard dua arah, file dua arah, baterai + kontrol cepat,
// kontrol PC dari HP (mouse, keyboard, layar).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
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

use super::pcctl::{self, Button};
use super::{clip_read, clip_write, log, read_json, write_json, AppState};

const DEFAULT_PORT: u16 = 5588;
const MAX_FRAME: usize = 1 << 20; // 1 MiB
const MAX_SEALED: usize = MAX_FRAME + 16; // + tag GCM
const KDF_ROUNDS: u32 = 100_000;
const TOKEN_LEN: usize = 12;
const CHUNK: usize = 128 * 1024;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
enum Frame {
    Json(Value),
    Bin(Vec<u8>),
    Cam(Vec<u8>), // jenis 2: [putaran/90][cermin][JPEG] dari kamera HP
    Screen(Vec<u8>), // jenis 3: JPEG layar PC -> HP
}

#[derive(Default)]
struct Cfg {
    token: String,
    port: u16,
    control: bool, // HP boleh mengontrol PC (default mati)
}

fn save_cfg(path: &Path, c: &Cfg) {
    write_json(
        path,
        &json!({ "token": c.token, "port": c.port, "control": c.control }),
    );
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
    control: AtomicBool,
    screen_on: AtomicBool,
    screen_gen: AtomicU64,
    screen_q: Arc<AtomicUsize>,
    fails: Mutex<(u32, std::time::Instant)>, // kegagalan kode berturut-turut
}

fn gen_token() -> String {
    const CH: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let mut rng = rand::thread_rng();
    (0..TOKEN_LEN).map(|_| CH[rng.gen_range(0..CH.len())] as char).collect()
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

fn hmac32(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("hmac key");
    for p in parts {
        mac.update(p);
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

struct Keys {
    auth: [u8; 32],
    c2s: [u8; 32],
    s2c: [u8; 32],
}

/// Turunkan kunci sesi dari kode dan dua nonce. Mahal (PBKDF2) sengaja, supaya menebak kode lewat
/// rekaman handshake tetap tidak praktis.
fn derive_keys(token: &str, s_nonce: &[u8], c_nonce: &[u8]) -> Keys {
    let mut salt = b"orbit-link-v2".to_vec();
    salt.extend_from_slice(s_nonce);
    salt.extend_from_slice(c_nonce);
    let mut master = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(token.as_bytes(), &salt, KDF_ROUNDS, &mut master);
    Keys {
        auth: hmac32(&master, &[b"auth"]),
        c2s: hmac32(&master, &[b"c2s"]),
        s2c: hmac32(&master, &[b"s2c"]),
    }
}

fn proof_for(keys: &Keys, who: &[u8], s_nonce: &[u8], c_nonce: &[u8]) -> [u8; 32] {
    hmac32(&keys.auth, &[who, s_nonce, c_nonce])
}

fn proof_ok(keys: &Keys, who: &[u8], s_nonce: &[u8], c_nonce: &[u8], proof_hex: &str) -> bool {
    let p = match from_hex(proof_hex) {
        Some(p) => p,
        None => return false,
    };
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&keys.auth).expect("hmac key");
    mac.update(who);
    mac.update(s_nonce);
    mac.update(c_nonce);
    mac.verify_slice(&p).is_ok()
}

/// Satu arah aliran terenkripsi. Counter naik tiap frame, jadi nonce tidak pernah berulang.
struct Crypt {
    c: Aes256Gcm,
    n: u64,
}

impl Crypt {
    fn new(key: &[u8; 32]) -> Crypt {
        Crypt { c: Aes256Gcm::new_from_slice(key).expect("kunci 32 byte"), n: 0 }
    }

    fn nonce(&mut self) -> [u8; 12] {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&self.n.to_be_bytes());
        self.n += 1;
        n
    }

    fn seal(&mut self, plain: &[u8]) -> Vec<u8> {
        let n = self.nonce();
        self.c.encrypt(Nonce::from_slice(&n), plain).expect("enkripsi")
    }

    fn open(&mut self, sealed: &[u8]) -> Option<Vec<u8>> {
        let n = self.nonce();
        self.c.decrypt(Nonce::from_slice(&n), sealed).ok()
    }
}

fn locked_out(ls: &LinkState) -> bool {
    let g = ls.fails.lock().unwrap();
    g.0 >= 5 && g.1.elapsed() < Duration::from_secs(30)
}

fn note_fail(ls: &LinkState) {
    let mut g = ls.fails.lock().unwrap();
    if g.1.elapsed() > Duration::from_secs(60) {
        g.0 = 0;
    }
    g.0 += 1;
    g.1 = std::time::Instant::now();
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
    let mut dirty = false;
    if token.len() != 8 && token.len() != TOKEN_LEN {
        // kode lama (8 karakter) tetap dipakai; kode baru 12 karakter
        token = gen_token();
        dirty = true;
    }
    let control = v.get("control").and_then(|x| x.as_bool()).unwrap_or(false);
    let cfg = Cfg { token, port, control };
    if dirty {
        save_cfg(&path, &cfg);
    }
    LinkState {
        cfg: Mutex::new(cfg),
        peer: Mutex::new(Peer::default()),
        tx: Mutex::new(None),
        kick: Mutex::new(None),
        clip: Mutex::new(None),
        file_id: AtomicU32::new(1),
        conn: AtomicU64::new(0),
        control: AtomicBool::new(control),
        screen_on: AtomicBool::new(false),
        screen_gen: AtomicU64::new(0),
        screen_q: Arc::new(AtomicUsize::new(0)),
        fails: Mutex::new((0, std::time::Instant::now())),
    }
}

// ---------------------------------------------------------------------------
// Frame I/O
// ---------------------------------------------------------------------------
fn bad(msg: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg.to_string())
}

async fn read_frame(r: &mut OwnedReadHalf, crypt: Option<&mut Crypt>) -> std::io::Result<Frame> {
    let mut len = [0u8; 4];
    r.read_exact(&mut len).await?;
    let n = u32::from_be_bytes(len) as usize;
    let max = if crypt.is_some() { MAX_SEALED } else { MAX_FRAME };
    if n == 0 || n > max {
        return Err(bad("ukuran frame tidak valid"));
    }
    let mut buf = vec![0u8; n];
    r.read_exact(&mut buf).await?;
    if let Some(c) = crypt {
        buf = c.open(&buf).ok_or_else(|| bad("frame gagal didekripsi"))?;
        if buf.is_empty() {
            return Err(bad("frame kosong"));
        }
    }
    let kind = buf[0];
    let body = buf.split_off(1);
    match kind {
        0 => {
            let v = serde_json::from_slice::<Value>(&body).map_err(|e| bad(&e.to_string()))?;
            Ok(Frame::Json(v))
        }
        2 => Ok(Frame::Cam(body)),
        3 => Ok(Frame::Screen(body)),
        _ => Ok(Frame::Bin(body)),
    }
}

async fn write_frame(w: &mut OwnedWriteHalf, f: &Frame, crypt: Option<&mut Crypt>) -> std::io::Result<()> {
    let mut payload: Vec<u8>;
    match f {
        Frame::Json(v) => {
            let body = v.to_string().into_bytes();
            payload = Vec::with_capacity(1 + body.len());
            payload.push(0);
            payload.extend_from_slice(&body);
        }
        Frame::Bin(b) | Frame::Cam(b) | Frame::Screen(b) => {
            payload = Vec::with_capacity(1 + b.len());
            payload.push(match f {
                Frame::Cam(_) => 2,
                Frame::Screen(_) => 3,
                _ => 1,
            });
            payload.extend_from_slice(b);
        }
    }
    let sealed = match crypt {
        Some(c) => c.seal(&payload),
        None => payload,
    };
    let mut out = Vec::with_capacity(4 + sealed.len());
    out.extend_from_slice(&(sealed.len() as u32).to_be_bytes());
    out.extend_from_slice(&sealed);
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
        "hotspot": p.hotspot,
        "control": ls.control.load(Ordering::SeqCst),
        "screen": ls.screen_on.load(Ordering::SeqCst)
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
    if locked_out(&app.state::<LinkState>()) {
        return; // terlalu banyak kode salah: tolak sementara
    }
    let nonce = new_nonce();
    let s_nonce = from_hex(&nonce).unwrap_or_default();
    let chal = Frame::Json(json!({ "t": "challenge", "ver": 2, "nonce": nonce, "pc": pc }));
    if write_frame(&mut w, &chal, None).await.is_err() {
        return;
    }
    let first = match tokio::time::timeout(Duration::from_secs(20), read_frame(&mut r, None)).await {
        Ok(Ok(Frame::Json(v))) => v,
        _ => return,
    };
    let ver = first.get("ver").and_then(|x| x.as_u64()).unwrap_or(1);
    let c_nonce = from_hex(&s(&first, "cnonce")).unwrap_or_default();
    if s(&first, "t") != "hello" || ver < 2 || c_nonce.len() != 16 {
        let _ = write_frame(&mut w, &Frame::Json(json!({ "t": "deny", "reason": "version" })), None).await;
        log(&app, format!("Orbit Link: {} memakai versi app lama. Perbarui Orbit Link di HP", addr), true);
        return;
    }
    let token = app.state::<LinkState>().cfg.lock().unwrap().token.clone();
    let keys = {
        let (t, sn, cn) = (token.clone(), s_nonce.clone(), c_nonce.clone());
        match tokio::task::spawn_blocking(move || derive_keys(&t, &sn, &cn)).await {
            Ok(k) => k,
            Err(_) => return,
        }
    };
    if !proof_ok(&keys, b"C", &s_nonce, &c_nonce, &s(&first, "proof")) {
        note_fail(&app.state::<LinkState>());
        let _ = write_frame(&mut w, &Frame::Json(json!({ "t": "deny" })), None).await;
        log(&app, format!("Orbit Link: {} ditolak (kode salah)", addr), true);
        tokio::time::sleep(Duration::from_secs(1)).await;
        return;
    }
    app.state::<LinkState>().fails.lock().unwrap().0 = 0;
    let name: String = s(&first, "name")
        .chars()
        .filter(|c| !c.is_control())
        .take(40)
        .collect();
    let name = if name.trim().is_empty() { "HP".to_string() } else { name };
    let welcome = Frame::Json(json!({
        "t": "welcome",
        "pc": pc,
        "proof": to_hex(&proof_for(&keys, b"S", &s_nonce, &c_nonce))
    }));
    if write_frame(&mut w, &welcome, None).await.is_err() {
        return;
    }
    // mulai sini semua frame terenkripsi, satu kunci per arah
    let mut read_crypt = Crypt::new(&keys.c2s);
    let mut write_crypt = Crypt::new(&keys.s2c);

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

    let screen_q = app.state::<LinkState>().screen_q.clone();
    let writer = tauri::async_runtime::spawn(async move {
        while let Some(f) = rx.recv().await {
            let is_screen = matches!(f, Frame::Screen(_));
            let res = write_frame(&mut w, &f, Some(&mut write_crypt)).await;
            if is_screen {
                let _ = screen_q.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| Some(n.saturating_sub(1)));
            }
            if res.is_err() {
                break;
            }
        }
    });
    send_pc_state(&app);

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
            res = tokio::time::timeout(Duration::from_secs(30), read_frame(&mut r, Some(&mut read_crypt))) => match res {
                Ok(Ok(f)) => f,
                _ => break,
            },
        };
        match fr {
            Frame::Json(v) => on_json(&app, &name, &mut incoming, v).await,
            Frame::Bin(b) => on_chunk(&mut incoming, b).await,
            Frame::Cam(b) => on_cam(&app, b),
            Frame::Screen(_) => {}
        }
    }

    // --- bersih-bersih ---
    helper.abort();
    {
        let ls = app.state::<LinkState>();
        if ls.conn.load(Ordering::SeqCst) == my_id {
            stop_screen(&app);
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
        "pc" => pc_input(app, &v),
        "screen" => screen_cmd(app, &v),
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
// Kontrol PC dari HP: input dan layar
// ---------------------------------------------------------------------------
fn num(v: &Value, k: &str) -> f64 {
    v.get(k).and_then(|x| x.as_f64()).filter(|x| x.is_finite()).unwrap_or(0.0)
}

fn control_on(app: &AppHandle) -> bool {
    app.state::<LinkState>().control.load(Ordering::SeqCst)
}

/// Kirim status kontrol PC ke HP: diizinkan atau tidak, ukuran layar, dan apakah layar sedang dikirim.
fn send_pc_state(app: &AppHandle) {
    let ls = app.state::<LinkState>();
    let tx = ls.tx.lock().unwrap().clone();
    if let Some(tx) = tx {
        let (w, h) = pcctl::screen_size();
        let msg = json!({
            "t": "pc-state",
            "control": ls.control.load(Ordering::SeqCst),
            "screen": ls.screen_on.load(Ordering::SeqCst),
            "w": w,
            "h": h
        });
        let _ = tx.try_send(Frame::Json(msg));
    }
}

fn pc_input(app: &AppHandle, v: &Value) {
    let a = s(v, "a");
    if a == "state" {
        send_pc_state(app);
        return;
    }
    if !control_on(app) {
        send_pc_state(app); // HP tahu kontrol dimatikan di PC
        return;
    }
    match a.as_str() {
        "move" => {
            let dx = num(v, "dx").clamp(-2000.0, 2000.0).round() as i32;
            let dy = num(v, "dy").clamp(-2000.0, 2000.0).round() as i32;
            if dx != 0 || dy != 0 {
                pcctl::mouse_move_rel(dx, dy);
            }
        }
        "abs" => pcctl::mouse_move_abs(num(v, "x"), num(v, "y")),
        "down" => pcctl::mouse_button(Button::parse(&s(v, "b")), true),
        "up" => pcctl::mouse_button(Button::parse(&s(v, "b")), false),
        "click" => {
            let n = v.get("n").and_then(|x| x.as_u64()).unwrap_or(1).min(3) as u32;
            pcctl::mouse_click(Button::parse(&s(v, "b")), n.max(1));
        }
        "scroll" => {
            let dx = num(v, "dx").clamp(-1200.0, 1200.0).round() as i32;
            let dy = num(v, "dy").clamp(-1200.0, 1200.0).round() as i32;
            pcctl::scroll(dx, dy);
        }
        "text" => {
            let text: String = s(v, "s").chars().take(500).collect();
            if !text.is_empty() {
                pcctl::type_text(&text);
            }
        }
        "key" | "media" => {
            let mods: Vec<String> = v
                .get("mods")
                .and_then(|x| x.as_array())
                .map(|arr| arr.iter().filter_map(|m| m.as_str().map(|t| t.to_string())).take(4).collect())
                .unwrap_or_default();
            let name = s(v, "k");
            if !pcctl::key_combo(&mods, &name) {
                event(app, format!("Tombol tidak dikenal dari HP: {}", name), true);
            }
        }
        _ => {}
    }
}

fn stop_screen(app: &AppHandle) {
    let ls = app.state::<LinkState>();
    ls.screen_gen.fetch_add(1, Ordering::SeqCst);
    if ls.screen_on.swap(false, Ordering::SeqCst) {
        emit_state(app);
    }
}

fn screen_cmd(app: &AppHandle, v: &Value) {
    match s(v, "a").as_str() {
        "start" => {
            if !control_on(app) {
                send_pc_state(app);
                return;
            }
            let max_w = v.get("max").and_then(|x| x.as_u64()).unwrap_or(1280).clamp(320, 1920) as u32;
            let fps = v.get("fps").and_then(|x| x.as_u64()).unwrap_or(12).clamp(1, 30) as u32;
            let q = v.get("q").and_then(|x| x.as_u64()).unwrap_or(55).clamp(20, 90) as u8;
            start_screen(app, max_w, fps, q);
        }
        "stop" => {
            stop_screen(app);
            send_pc_state(app);
            event(app, "HP berhenti melihat layar PC", false);
        }
        _ => {}
    }
}

fn start_screen(app: &AppHandle, max_w: u32, fps: u32, quality: u8) {
    let ls = app.state::<LinkState>();
    let tx = match ls.tx.lock().unwrap().clone() {
        Some(t) => t,
        None => return,
    };
    let gen = ls.screen_gen.fetch_add(1, Ordering::SeqCst) + 1;
    ls.screen_on.store(true, Ordering::SeqCst);
    let queue = ls.screen_q.clone();
    let app2 = app.clone();
    emit_state(app);
    send_pc_state(app);
    event(app, "HP sedang melihat layar PC", false);
    std::thread::spawn(move || {
        let mut grab = match pcctl::Grabber::new(max_w) {
            Some(g) => g,
            None => {
                event(&app2, "Layar PC tidak bisa ditangkap", true);
                let ls = app2.state::<LinkState>();
                if ls.screen_gen.load(Ordering::SeqCst) == gen {
                    ls.screen_on.store(false, Ordering::SeqCst);
                    emit_state(&app2);
                    send_pc_state(&app2);
                }
                return;
            }
        };
        let step = Duration::from_millis((1000 / fps.max(1)) as u64);
        let mut last_sent = std::time::Instant::now() - Duration::from_secs(10);
        loop {
            let t0 = std::time::Instant::now();
            {
                let ls = app2.state::<LinkState>();
                if ls.screen_gen.load(Ordering::SeqCst) != gen || !ls.control.load(Ordering::SeqCst) {
                    break;
                }
            }
            if let Some((jpg, changed)) = grab.frame(quality) {
                // gambar sama: kirim ulang paling cepat tiap 1 detik supaya HP tahu masih hidup
                let due = changed || last_sent.elapsed() >= Duration::from_secs(1);
                if due && queue.load(Ordering::SeqCst) < 2 {
                    queue.fetch_add(1, Ordering::SeqCst);
                    match tx.try_send(Frame::Screen(jpg)) {
                        Ok(()) => last_sent = std::time::Instant::now(),
                        Err(mpsc::error::TrySendError::Full(_)) => {
                            let _ = queue.fetch_update(Ordering::SeqCst, Ordering::SeqCst, |n| Some(n.saturating_sub(1)));
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => break,
                    }
                }
            }
            let spent = t0.elapsed();
            if spent < step {
                std::thread::sleep(step - spent);
            }
        }
        let ls = app2.state::<LinkState>();
        if ls.screen_gen.load(Ordering::SeqCst) == gen {
            ls.screen_on.store(false, Ordering::SeqCst);
            emit_state(&app2);
        }
    });
}

#[tauri::command]
pub fn link_set_control(app: AppHandle, on: bool) -> Value {
    {
        let ls = app.state::<LinkState>();
        let dir = app.state::<AppState>().data_dir.clone();
        {
            let mut c = ls.cfg.lock().unwrap();
            c.control = on;
            save_cfg(&dir.join("link.json"), &c);
        }
        ls.control.store(on, Ordering::SeqCst);
    }
    if !on {
        stop_screen(&app);
    }
    event(
        &app,
        if on { "Kontrol PC dari HP dinyalakan" } else { "Kontrol PC dari HP dimatikan" },
        false,
    );
    send_pc_state(&app);
    emit_state(&app);
    link_info(app)
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
/// Port TCP server Link (dipakai ngrok untuk meneruskan tunnel).
pub fn port(app: &AppHandle) -> u16 {
    app.state::<LinkState>().cfg.lock().unwrap().port
}

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
        let c = ls.cfg.lock().unwrap();
        save_cfg(&dir.join("link.json"), &c);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crypt_roundtrip_and_tamper() {
        let key = [7u8; 32];
        let mut a = Crypt::new(&key);
        let mut b = Crypt::new(&key);
        for i in 0..3u8 {
            let msg = vec![i; 10 + i as usize];
            let sealed = a.seal(&msg);
            assert_eq!(sealed.len(), msg.len() + 16);
            assert_eq!(b.open(&sealed).unwrap(), msg);
        }
        // frame yang diubah ditolak
        let mut sealed = a.seal(b"halo");
        sealed[0] ^= 1;
        assert!(b.open(&sealed).is_none());
    }

    #[test]
    fn replay_is_rejected() {
        let key = [9u8; 32];
        let mut a = Crypt::new(&key);
        let mut b = Crypt::new(&key);
        let first = a.seal(b"satu");
        assert!(b.open(&first).is_some());
        // frame yang sama dikirim ulang: counter penerima sudah maju, jadi gagal
        assert!(b.open(&first).is_none());
    }

    #[test]
    fn keys_depend_on_everything() {
        let sn = [1u8; 16];
        let cn = [2u8; 16];
        let k1 = derive_keys("ABCDEFGH2345", &sn, &cn);
        let k2 = derive_keys("ABCDEFGH2346", &sn, &cn);
        let k3 = derive_keys("ABCDEFGH2345", &cn, &sn);
        assert_ne!(k1.auth, k2.auth);
        assert_ne!(k1.auth, k3.auth);
        assert_ne!(k1.c2s, k1.s2c);
        let p = to_hex(&proof_for(&k1, b"C", &sn, &cn));
        assert!(proof_ok(&k1, b"C", &sn, &cn, &p));
        assert!(!proof_ok(&k1, b"S", &sn, &cn, &p)); // bukti HP tidak bisa dipakai sebagai bukti PC
        assert!(!proof_ok(&k2, b"C", &sn, &cn, &p));
    }

    /// Vektor tetap, dipakai juga oleh tes Kotlin (Proto.kt) supaya kedua sisi pasti sama.
    #[test]
    fn cross_language_vector() {
        let sn: Vec<u8> = (0u8..16).collect();
        let cn: Vec<u8> = (100u8..116).collect();
        let k = derive_keys("ABCDEFGH2345", &sn, &cn);
        let mut enc = Crypt::new(&k.s2c);
        let sealed = enc.seal(b"\x00{\"t\":\"ping\"}");
        println!("VEC auth={}", to_hex(&k.auth));
        println!("VEC c2s={}", to_hex(&k.c2s));
        println!("VEC s2c={}", to_hex(&k.s2c));
        println!("VEC proofC={}", to_hex(&proof_for(&k, b"C", &sn, &cn)));
        println!("VEC proofS={}", to_hex(&proof_for(&k, b"S", &sn, &cn)));
        println!("VEC sealed={}", to_hex(&sealed));
    }
}
