// Akses jarak jauh lewat ngrok, dijalankan dari PC sendiri.
//
// Orbit menjalankan agen ngrok yang terpasang di PC (`ngrok tcp <port Link>`) dan membaca alamat publiknya
// dari API lokal agen (127.0.0.1:4040). Alamat itu dipakai di app Orbit Link di HP, sama seperti alamat WiFi.
// Data Link terenkripsi dan butuh kode, jadi server relay ngrok tidak bisa membaca isinya.
//
// Authtoken disimpan di folder data Orbit (ngrok.json). Kalau kosong, ngrok memakai konfigurasi miliknya sendiri
// (hasil `ngrok config add-authtoken ...`).

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::process::Command;
use tokio::sync::oneshot;

use super::{link, log, read_json, write_json, AppState};

pub struct NgrokState {
    url: Mutex<Option<String>>,          // host:port publik, mis. 0.tcp.ap.ngrok.io:12345
    stop: Mutex<Option<oneshot::Sender<()>>>, // ada = proses ngrok sedang jalan
    err: Mutex<String>,
    installing: AtomicBool,
}

pub fn init() -> NgrokState {
    NgrokState { url: Mutex::new(None), stop: Mutex::new(None), err: Mutex::new(String::new()), installing: AtomicBool::new(false) }
}

fn exe_name() -> &'static str {
    if cfg!(windows) {
        "ngrok.exe"
    } else {
        "ngrok"
    }
}

/// Cari ngrok: folder data Orbit, folder app, PATH, lalu lokasi umum hasil winget.
fn find_ngrok(data_dir: &Path) -> Option<PathBuf> {
    let name = exe_name();
    let mut c: Vec<PathBuf> = vec![data_dir.join(name)];
    if let Ok(me) = std::env::current_exe() {
        if let Some(d) = me.parent() {
            c.push(d.join(name));
        }
    }
    if let Some(path) = std::env::var_os("PATH") {
        for d in std::env::split_paths(&path) {
            c.push(d.join(name));
        }
    }
    if let Some(la) = std::env::var_os("LOCALAPPDATA") {
        c.push(PathBuf::from(la).join("Microsoft").join("WinGet").join("Links").join(name));
    }
    if let Some(pf) = std::env::var_os("ProgramFiles") {
        c.push(PathBuf::from(pf).join("ngrok").join(name));
    }
    c.into_iter().find(|p| p.is_file())
}

fn token_path(dir: &Path) -> PathBuf {
    dir.join("ngrok.json")
}

fn saved_token(dir: &Path) -> String {
    read_json(&token_path(dir), json!({}))
        .get("token")
        .and_then(|x| x.as_str())
        .unwrap_or("")
        .to_string()
}

fn status(app: &AppHandle) -> Value {
    let st = app.state::<NgrokState>();
    let dir = app.state::<AppState>().data_dir.clone();
    let url = st.url.lock().unwrap().clone();
    let running = st.stop.lock().unwrap().is_some();
    let err = st.err.lock().unwrap().clone();
    json!({
        "installed": find_ngrok(&dir).is_some(),
        "running": running,
        "installing": st.installing.load(Ordering::SeqCst),
        "url": url,
        "err": err,
        "has_token": !saved_token(&dir).is_empty()
    })
}

fn push(app: &AppHandle) {
    let _ = app.emit("ngrok-state", status(app));
}

fn set_err(app: &AppHandle, msg: impl Into<String>) {
    *app.state::<NgrokState>().err.lock().unwrap() = msg.into();
}

/// Ubah baris log ngrok yang berisi galat menjadi pesan yang bisa dipahami.
fn friendly_error(line: &str) -> Option<String> {
    if line.contains("Error reading configuration file") || line.contains("unknown version") {
        return Some("Config ngrok bawaan tidak cocok dengan versi ngrok. Orbit sudah memakai config sendiri; matikan lalu nyalakan lagi.".into());
    }
    if line.contains("ERR_NGROK_121") || line.contains("agent version") && line.contains("too old") {
        return Some("Versi ngrok terlalu lama. Tekan Pasang ngrok untuk memakai versi 3, atau pakai UPnP.".into());
    }
    if line.contains("ERR_NGROK_4018") || line.contains("authentication failed") {
        return Some("Authtoken ngrok kosong atau salah. Salin dari dashboard.ngrok.com, menu Your Authtoken.".into());
    }
    if line.contains("ERR_NGROK_8013") || line.contains("verified account") || line.contains("credit card") {
        return Some("Akun ngrok perlu diverifikasi (kartu) untuk tunnel TCP. Atau pakai Tailscale.".into());
    }
    if line.contains("ERR_NGROK_108") || line.contains("simultaneous ngrok agent sessions") {
        return Some("Sudah ada sesi ngrok lain jalan (akun gratis cuma 1). Tutup ngrok lain atau stop di dashboard.ngrok.com/agents.".into());
    }
    if line.contains("ERR_NGROK") || line.contains("lvl=eror") {
        return Some(line.trim().chars().take(160).collect());
    }
    None
}

/// Baca satu aliran keluaran ngrok. Galat disimpan dan dikirim ke konsol.
fn watch<R>(app: AppHandle, r: R, is_stderr: bool) -> tauri::async_runtime::JoinHandle<()>
where
    R: tokio::io::AsyncRead + Unpin + Send + 'static,
{
    tauri::async_runtime::spawn(async move {
        let mut lines = BufReader::new(r).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let l = line.trim();
            if l.is_empty() {
                continue;
            }
            let short: String = l.chars().take(200).collect();
            if let Some(msg) = friendly_error(l) {
                set_err(&app, msg);
                log(&app, format!("ngrok: {}", short), true);
            } else if is_stderr || l.contains("lvl=warn") {
                if is_stderr && app.state::<NgrokState>().err.lock().unwrap().is_empty() {
                    set_err(&app, short.clone());
                }
                log(&app, format!("ngrok: {}", short), is_stderr);
            }
        }
    })
}

/// GET sederhana ke API lokal agen ngrok, tanpa pustaka HTTP tambahan.
async fn http_get(port: u16, path: &str) -> Option<String> {
    let mut s = tokio::time::timeout(Duration::from_millis(800), TcpStream::connect(("127.0.0.1", port)))
        .await
        .ok()?
        .ok()?;
    let req = format!("GET {} HTTP/1.0\r\nHost: 127.0.0.1:{}\r\nConnection: close\r\n\r\n", path, port);
    s.write_all(req.as_bytes()).await.ok()?;
    let mut buf = Vec::new();
    tokio::time::timeout(Duration::from_secs(2), s.read_to_end(&mut buf)).await.ok()?.ok()?;
    let text = String::from_utf8_lossy(&buf).to_string();
    text.split_once("\r\n\r\n").map(|(_, b)| b.to_string())
}

/// Alamat publik tunnel TCP yang meneruskan ke port Link.
async fn find_tunnel(port: u16) -> Option<String> {
    let suffix = format!(":{}", port);
    for web in 4040u16..=4043 {
        let body = match http_get(web, "/api/tunnels").await {
            Some(b) => b,
            None => continue,
        };
        let v: Value = match serde_json::from_str(&body) {
            Ok(v) => v,
            Err(_) => continue,
        };
        for t in v["tunnels"].as_array().into_iter().flatten() {
            let url = t["public_url"].as_str().unwrap_or("");
            let addr = t["config"]["addr"].as_str().unwrap_or("");
            if url.starts_with("tcp://") && addr.ends_with(&suffix) {
                return Some(url.trim_start_matches("tcp://").to_string());
            }
        }
    }
    None
}

/// Versi utama ngrok (2 atau 3). None kalau tidak terbaca.
async fn agent_major(exe: &Path) -> Option<u32> {
    let mut cmd = Command::new(exe);
    cmd.arg("version").stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000);
    let out = tokio::time::timeout(Duration::from_secs(8), cmd.output()).await.ok()?.ok()?;
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    let v = text.split("version").nth(1)?.trim().to_string();
    v.split('.').next()?.trim().parse().ok()
}

/// Config ngrok milik Orbit sendiri. Config bawaan (%LOCALAPPDATA%\ngrok\ngrok.yml) bisa berversi "3"
/// sehingga ngrok lama menolaknya ("unknown version '3'"). Versi "2" dibaca oleh ngrok 2 dan 3.
fn write_cfg(dir: &Path, tok: &str) -> PathBuf {
    let p = dir.join("ngrok-orbit.yml");
    let mut s = String::from("version: \"2\"\n");
    if !tok.is_empty() {
        s.push_str(&format!("authtoken: \"{}\"\n", tok));
    }
    let _ = std::fs::write(&p, s);
    p
}

/// Authtoken dari config ngrok bawaan, dipakai kalau Orbit belum menyimpan token sendiri.
fn existing_token() -> Option<String> {
    let mut paths: Vec<PathBuf> = Vec::new();
    for (var, rel) in [
        ("LOCALAPPDATA", "ngrok/ngrok.yml"),
        ("APPDATA", "ngrok/ngrok.yml"),
        ("USERPROFILE", ".ngrok2/ngrok.yml"),
        ("USERPROFILE", ".config/ngrok/ngrok.yml"),
        ("HOME", ".config/ngrok/ngrok.yml"),
        ("HOME", ".ngrok2/ngrok.yml"),
    ] {
        if let Some(b) = std::env::var_os(var) {
            paths.push(PathBuf::from(b).join(rel));
        }
    }
    for p in paths {
        if let Ok(t) = std::fs::read_to_string(&p) {
            for line in t.lines() {
                if let Some(v) = line.trim().strip_prefix("authtoken:") {
                    let v = v.trim().trim_matches('"').trim_matches('\'');
                    if !v.is_empty() {
                        return Some(v.to_string());
                    }
                }
            }
        }
    }
    None
}

/// Token hanya boleh berisi huruf, angka, garis bawah, dan strip (aman untuk ditulis ke YAML).
fn clean_token(t: &str) -> String {
    t.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect()
}

/// Unduh paket resmi ngrok v3 ke folder data Orbit (menimpa ngrok.exe lama di situ).
async fn download_official(dir: &Path) -> bool {
    if !cfg!(windows) {
        return false;
    }
    let zip = dir.join("ngrok-download.zip");
    let script = format!(
        "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; \
         Invoke-WebRequest -UseBasicParsing -Uri 'https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-windows-amd64.zip' -OutFile '{z}'; \
         Expand-Archive -Force -Path '{z}' -DestinationPath '{d}'; Remove-Item -Force '{z}'",
        z = zip.display(),
        d = dir.display()
    );
    run_quiet("powershell", &["-NoProfile", "-NonInteractive", "-Command", &script], 180).await
}

/// Jalankan perintah tanpa jendela konsol; true kalau keluar dengan kode 0 sebelum batas waktu.
async fn run_quiet(prog: &str, args: &[&str], secs: u64) -> bool {
    let mut cmd = Command::new(prog);
    cmd.args(args).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000);
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(_) => return false,
    };
    match tokio::time::timeout(Duration::from_secs(secs), child.wait()).await {
        Ok(Ok(st)) => st.success(),
        _ => {
            let _ = child.kill().await;
            false
        }
    }
}

/// Pasang ngrok: winget dulu, kalau tidak ada atau gagal unduh paket resmi ke folder data Orbit.
#[tauri::command]
pub async fn ngrok_install(app: AppHandle) -> Value {
    let dir = app.state::<AppState>().data_dir.clone();
    if find_ngrok(&dir).is_some() || app.state::<NgrokState>().installing.swap(true, Ordering::SeqCst) {
        return status(&app);
    }
    set_err(&app, "");
    push(&app);

    let mut ok = run_quiet(
        "winget",
        &["install", "-e", "--id", "Ngrok.Ngrok", "--accept-package-agreements", "--accept-source-agreements", "--silent"],
        300,
    )
    .await
        && find_ngrok(&dir).is_some();

    if !ok {
        ok = download_official(&dir).await && find_ngrok(&dir).is_some();
    }

    app.state::<NgrokState>().installing.store(false, Ordering::SeqCst);
    if ok {
        log(&app, "ngrok terpasang", false);
    } else {
        set_err(&app, "Gagal memasang ngrok otomatis. Pasang manual dari ngrok.com/download, lalu buka Orbit lagi.");
    }
    push(&app);
    status(&app)
}

#[tauri::command]
pub fn ngrok_status(app: AppHandle) -> Value {
    status(&app)
}

#[tauri::command]
pub async fn ngrok_start(app: AppHandle, token: Option<String>) -> Value {
    if app.state::<NgrokState>().stop.lock().unwrap().is_some() {
        return status(&app);
    }
    set_err(&app, "");
    let dir = app.state::<AppState>().data_dir.clone();
    let mut exe = match find_ngrok(&dir) {
        Some(p) => p,
        None => {
            set_err(&app, "ngrok belum terpasang. Tekan Pasang ngrok.");
            push(&app);
            return status(&app);
        }
    };
    // ngrok v2 sudah ditolak server ngrok dan tidak bisa membaca config v3: pakai v3
    if agent_major(&exe).await.map_or(false, |m| m < 3) {
        log(&app, "ngrok lama (v2) terdeteksi, mengunduh ngrok v3", false);
        if download_official(&dir).await {
            if let Some(p) = find_ngrok(&dir) {
                exe = p;
            }
        }
        if agent_major(&exe).await.map_or(false, |m| m < 3) {
            set_err(&app, "ngrok di komputer ini versi lama (v2) dan gagal diperbarui otomatis. Pasang ngrok v3 dari ngrok.com/download, atau pakai UPnP.");
            push(&app);
            return status(&app);
        }
    }
    let tok = match token.map(|t| t.trim().to_string()).filter(|t| !t.is_empty()) {
        Some(t) => {
            write_json(&token_path(&dir), &json!({ "token": t }));
            t
        }
        None => saved_token(&dir),
    };
    let tok = clean_token(&tok);
    let tok = if tok.is_empty() { clean_token(&existing_token().unwrap_or_default()) } else { tok };
    let cfg = write_cfg(&dir, &tok);
    let port = link::port(&app);

    let mut cmd = Command::new(&exe);
    cmd.args(["tcp", &port.to_string(), "--log", "stdout", "--log-format", "logfmt", "--config"])
        .arg(&cfg)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if !tok.is_empty() {
        cmd.env("NGROK_AUTHTOKEN", &tok); // lewat env supaya tidak terlihat di daftar proses
    }
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000); // tanpa jendela konsol
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            set_err(&app, format!("Gagal menjalankan ngrok: {}", e));
            push(&app);
            return status(&app);
        }
    };

    let mut readers = Vec::new();
    if let Some(out) = child.stdout.take() {
        readers.push(watch(app.clone(), out, false));
    }
    if let Some(er) = child.stderr.take() {
        readers.push(watch(app.clone(), er, true));
    }

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    *app.state::<NgrokState>().stop.lock().unwrap() = Some(stop_tx);
    let a2 = app.clone();
    tauri::async_runtime::spawn(async move {
        let mut by_user = false;
        let mut code: Option<i32> = None;
        tokio::select! {
            r = child.wait() => { code = r.ok().and_then(|s| s.code()); }
            _ = stop_rx => { by_user = true; let _ = child.kill().await; }
        }
        // tunggu pembaca keluaran selesai supaya pesan galat sempat tersimpan
        for h in readers {
            let _ = tokio::time::timeout(Duration::from_secs(1), h).await;
        }
        if !by_user && a2.state::<NgrokState>().err.lock().unwrap().is_empty() {
            set_err(
                &a2,
                format!(
                    "ngrok berhenti sendiri (kode {}). Coba jalankan `ngrok tcp {}` di terminal untuk lihat galatnya.",
                    code.map(|c| c.to_string()).unwrap_or_else(|| "?".into()),
                    port
                ),
            );
        }
        let st = a2.state::<NgrokState>();
        *st.stop.lock().unwrap() = None;
        *st.url.lock().unwrap() = None;
        push(&a2);
        log(&a2, "ngrok berhenti", false);
    });
    push(&app);

    // tunggu agen siap dan alamat publiknya muncul
    for _ in 0..40 {
        if app.state::<NgrokState>().stop.lock().unwrap().is_none() {
            break; // proses sudah keluar (galat)
        }
        if let Some(u) = find_tunnel(port).await {
            *app.state::<NgrokState>().url.lock().unwrap() = Some(u.clone());
            log(&app, format!("ngrok aktif: {}", u), false);
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    if app.state::<NgrokState>().url.lock().unwrap().is_none()
        && app.state::<NgrokState>().stop.lock().unwrap().is_some()
        && app.state::<NgrokState>().err.lock().unwrap().is_empty()
    {
        set_err(&app, "ngrok belum memberi alamat. Cek koneksi internet dan authtoken.");
    }
    push(&app);
    status(&app)
}

#[tauri::command]
pub fn ngrok_stop(app: AppHandle) -> Value {
    shutdown(&app);
    *app.state::<NgrokState>().url.lock().unwrap() = None;
    push(&app);
    status(&app)
}

/// Hentikan ngrok (dipanggil juga saat Orbit ditutup).
pub fn shutdown(app: &AppHandle) {
    if let Some(tx) = app.state::<NgrokState>().stop.lock().unwrap().take() {
        let _ = tx.send(());
    }
}

#[tauri::command]
pub fn ngrok_forget_token(app: AppHandle) -> Value {
    let dir = app.state::<AppState>().data_dir.clone();
    let _ = std::fs::remove_file(token_path(&dir));
    push(&app);
    status(&app)
}
