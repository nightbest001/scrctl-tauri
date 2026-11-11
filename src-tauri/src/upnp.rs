// Akses jarak jauh lewat UPnP, tanpa layanan pihak ketiga.
//
// Orbit meminta router membuka port Link (UPnP IGD). Setelah itu HP dari luar WiFi menyambung langsung
// ke IP publik router di port tersebut. Data Link tetap terenkripsi dan butuh kode.
//
// Syarat: UPnP menyala di router, dan provider memberi IP publik asli (bukan CGNAT).
// Tidak memakai pustaka tambahan: SSDP lewat UDP, SOAP lewat TCP mentah (tokio).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::oneshot;

use super::{link, log};

#[derive(Clone)]
struct Mapping {
    host: String,    // ip:port layanan UPnP router
    ctl: String,     // path controlURL
    service: String, // serviceType penuh
    ext_port: u16,
    lease: u32,
}

pub struct UpnpState {
    addr: Mutex<Option<String>>, // ip publik:port
    err: Mutex<String>,
    busy: AtomicBool,
    map: Mutex<Option<Mapping>>,
    stop: Mutex<Option<oneshot::Sender<()>>>, // menghentikan pembaruan berkala
}

pub fn init() -> UpnpState {
    UpnpState {
        addr: Mutex::new(None),
        err: Mutex::new(String::new()),
        busy: AtomicBool::new(false),
        map: Mutex::new(None),
        stop: Mutex::new(None),
    }
}

fn status(app: &AppHandle) -> Value {
    let st = app.state::<UpnpState>();
    let url = st.addr.lock().unwrap().clone();
    let running = st.map.lock().unwrap().is_some();
    let err = st.err.lock().unwrap().clone();
    let busy = st.busy.load(Ordering::SeqCst);
    json!({ "running": running, "busy": busy, "url": url, "err": err })
}

fn push(app: &AppHandle) {
    let _ = app.emit("upnp-state", status(app));
}

fn set_err(app: &AppHandle, msg: impl Into<String>) {
    *app.state::<UpnpState>().err.lock().unwrap() = msg.into();
}

// ---------------------------------------------------------------------------
// Helper jaringan
// ---------------------------------------------------------------------------
fn local_ip_for(target: &str) -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .ok()
        .and_then(|s| {
            s.connect(target).ok()?; // UDP: tidak ada paket yang dikirim
            s.local_addr().ok()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_default()
}

fn split_url(u: &str) -> Option<(String, String)> {
    let r = u.strip_prefix("http://")?;
    let (h, p) = match r.find('/') {
        Some(i) => (&r[..i], &r[i..]),
        None => (r, "/"),
    };
    if h.is_empty() {
        return None;
    }
    Some((h.to_string(), p.to_string()))
}

fn tag_text(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{}>", tag);
    let close = format!("</{}>", tag);
    let a = xml.find(&open)? + open.len();
    let b = xml[a..].find(&close)? + a;
    Some(xml[a..b].trim().to_string())
}

fn dechunk(b: &str) -> String {
    let mut out = String::new();
    let mut rest = b;
    loop {
        let (line, after) = match rest.split_once("\r\n") {
            Some(x) => x,
            None => break,
        };
        let n = match usize::from_str_radix(line.trim().split(';').next().unwrap_or(""), 16) {
            Ok(n) => n,
            Err(_) => break,
        };
        if n == 0 {
            break;
        }
        match after.get(..n) {
            Some(c) => out.push_str(c),
            None => {
                out.push_str(after);
                break;
            }
        }
        rest = after.get(n..).unwrap_or("").trim_start_matches("\r\n");
    }
    out
}

fn response_complete(buf: &[u8]) -> bool {
    let pos = match buf.windows(4).position(|w| w == b"\r\n\r\n") {
        Some(p) => p,
        None => return false,
    };
    let head = String::from_utf8_lossy(&buf[..pos]).to_ascii_lowercase();
    let blen = buf.len() - (pos + 4);
    for l in head.lines() {
        if let Some(v) = l.strip_prefix("content-length:") {
            if let Ok(n) = v.trim().parse::<usize>() {
                return blen >= n;
            }
        }
    }
    false
}

/// HTTP/1.0 mentah. Mengembalikan (kode status, isi).
async fn http(host: &str, method: &str, path: &str, extra: &[(&str, String)], body: &str) -> Option<(u16, String)> {
    let mut s = tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(host)).await.ok()?.ok()?;
    let mut req = format!("{} {} HTTP/1.0\r\nHost: {}\r\n", method, path, host);
    for (k, v) in extra {
        req.push_str(&format!("{}: {}\r\n", k, v));
    }
    req.push_str(&format!("Content-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body));
    s.write_all(req.as_bytes()).await.ok()?;

    let mut buf: Vec<u8> = Vec::new();
    let mut tmp = [0u8; 4096];
    loop {
        match tokio::time::timeout(Duration::from_secs(4), s.read(&mut tmp)).await {
            Ok(Ok(0)) => break,
            Ok(Ok(n)) => {
                buf.extend_from_slice(&tmp[..n]);
                if response_complete(&buf) {
                    break;
                }
            }
            _ => break,
        }
    }
    let text = String::from_utf8_lossy(&buf).to_string();
    let (head, body) = text.split_once("\r\n\r\n")?;
    let code: u16 = head.lines().next()?.split_whitespace().nth(1)?.parse().ok()?;
    let body = if head.to_ascii_lowercase().contains("transfer-encoding: chunked") {
        dechunk(body)
    } else {
        body.to_string()
    };
    Some((code, body))
}

// ---------------------------------------------------------------------------
// UPnP: cari router, panggil SOAP
// ---------------------------------------------------------------------------
async fn discover() -> Vec<String> {
    let lan = local_ip_for("239.255.255.250:1900");
    let bind = if lan.is_empty() { "0.0.0.0:0".to_string() } else { format!("{}:0", lan) };
    let sock = match UdpSocket::bind(bind.as_str()).await {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let targets = [
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "upnp:rootdevice",
    ];
    for t in targets {
        let msg = format!(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {}\r\n\r\n",
            t
        );
        let _ = sock.send_to(msg.as_bytes(), "239.255.255.250:1900").await;
    }
    let mut found: Vec<String> = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    let mut buf = [0u8; 2048];
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        if left.is_zero() {
            break;
        }
        match tokio::time::timeout(left, sock.recv_from(&mut buf)).await {
            Ok(Ok((n, _))) => {
                let text = String::from_utf8_lossy(&buf[..n]).to_string();
                for line in text.lines() {
                    if line.to_ascii_lowercase().starts_with("location:") {
                        let loc = line[9..].trim().to_string();
                        if !loc.is_empty() && !found.contains(&loc) {
                            found.push(loc);
                        }
                    }
                }
            }
            _ => break,
        }
    }
    found
}

/// Baca deskripsi perangkat; kembalikan (host, controlURL, serviceType) layanan WAN.
async fn describe(loc: &str) -> Option<(String, String, String)> {
    let (host, path) = split_url(loc)?;
    let (code, xml) = http(&host, "GET", &path, &[], "").await?;
    if code != 200 {
        return None;
    }
    let mut best: Option<(String, String, String)> = None;
    for chunk in xml.split("<service>").skip(1) {
        let st = tag_text(chunk, "serviceType").unwrap_or_default();
        if !(st.contains("WANIPConnection") || st.contains("WANPPPConnection")) {
            continue;
        }
        let ctl = match tag_text(chunk, "controlURL") {
            Some(c) => c,
            None => continue,
        };
        let (h, p) = if ctl.starts_with("http://") {
            match split_url(&ctl) {
                Some(x) => x,
                None => continue,
            }
        } else if ctl.starts_with('/') {
            (host.clone(), ctl)
        } else {
            (host.clone(), format!("/{}", ctl))
        };
        let is_ip = st.contains("WANIPConnection");
        if best.is_none() || is_ip {
            best = Some((h, p, st));
        }
    }
    best
}

async fn soap(host: &str, ctl: &str, service: &str, action: &str, args: &str) -> Result<String, String> {
    let body = format!(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{a} xmlns:u=\"{s}\">{args}</u:{a}></s:Body></s:Envelope>",
        a = action,
        s = service,
        args = args
    );
    let hdr = vec![
        ("Content-Type", "text/xml; charset=\"utf-8\"".to_string()),
        ("SOAPAction", format!("\"{}#{}\"", service, action)),
    ];
    let (code, text) = http(host, "POST", ctl, &hdr, &body).await.ok_or_else(|| "router tidak menjawab".to_string())?;
    if code == 200 {
        Ok(text)
    } else {
        let desc = tag_text(&text, "errorDescription").unwrap_or_else(|| format!("HTTP {}", code));
        let num = tag_text(&text, "errorCode").unwrap_or_default();
        Err(if num.is_empty() { desc } else { format!("{} ({})", desc, num) })
    }
}

async fn add_map(m: &Mapping, local: &str, port: u16) -> Result<(), String> {
    let args = format!(
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>{ep}</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>{ip}</NewInternalPort><NewInternalClient>{lc}</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>SCRCTL Link</NewPortMappingDescription><NewLeaseDuration>{ls}</NewLeaseDuration>",
        ep = m.ext_port,
        ip = port,
        lc = local,
        ls = m.lease
    );
    soap(&m.host, &m.ctl, &m.service, "AddPortMapping", &args).await.map(|_| ())
}

async fn external_ip(host: &str, ctl: &str, service: &str) -> Result<String, String> {
    let t = soap(host, ctl, service, "GetExternalIPAddress", "").await?;
    Ok(tag_text(&t, "NewExternalIPAddress").unwrap_or_default())
}

/// "ok" | "cgnat" | "private" | "bad"
fn ip_kind(ip: &str) -> &'static str {
    let p: Vec<u8> = ip.split('.').filter_map(|x| x.trim().parse().ok()).collect();
    if p.len() != 4 {
        return "bad";
    }
    if p[0] == 100 && (64..=127).contains(&p[1]) {
        return "cgnat";
    }
    if p[0] == 0 || p[0] == 10 || (p[0] == 172 && (16..=31).contains(&p[1])) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254) {
        return "private";
    }
    "ok"
}

// ---------------------------------------------------------------------------
// Alur utama
// ---------------------------------------------------------------------------
async fn setup(app: &AppHandle) -> Result<(), String> {
    let port = link::port(app);

    let locs = discover().await;
    if locs.is_empty() {
        return Err("Router tidak menjawab UPnP. Nyalakan UPnP di pengaturan router, atau pakai ngrok.".to_string());
    }
    let mut found: Option<(String, String, String)> = None;
    for loc in &locs {
        if let Some(r) = describe(loc).await {
            found = Some(r);
            break;
        }
    }
    let (host, ctl, service) = match found {
        Some(x) => x,
        None => return Err("Router ada, tapi tidak punya layanan WAN untuk UPnP. Pakai ngrok.".to_string()),
    };

    let ext = external_ip(&host, &ctl, &service).await.map_err(|e| format!("Gagal membaca IP publik router: {}", e))?;
    match ip_kind(&ext) {
        "ok" => {}
        "cgnat" => {
            return Err(format!(
                "Provider memakai CGNAT (IP WAN router: {}). Port tidak bisa dibuka dari luar. Pakai ngrok.",
                ext
            ))
        }
        "private" => {
            return Err(format!(
                "Router ini ada di belakang router lain (IP WAN: {}). UPnP tidak tembus ke internet. Pakai ngrok.",
                ext
            ))
        }
        _ => return Err("Router belum punya IP publik. Cek koneksi internet router.".to_string()),
    }

    let local = local_ip_for(&host);
    if local.is_empty() {
        return Err("Gagal menentukan IP PC di jaringan ini.".to_string());
    }

    // port acak dihitung sebelum await supaya future tetap Send
    let alt: u16 = {
        use rand::Rng;
        rand::thread_rng().gen_range(20000..60000)
    };
    let mut last = String::from("ditolak");
    let mut chosen: Option<Mapping> = None;
    'outer: for ext_port in [port, alt] {
        for lease in [0u32, 3600] {
            let m = Mapping { host: host.clone(), ctl: ctl.clone(), service: service.clone(), ext_port, lease };
            match add_map(&m, &local, port).await {
                Ok(()) => {
                    chosen = Some(m);
                    break 'outer;
                }
                Err(e) => last = e,
            }
        }
    }
    let m = match chosen {
        Some(m) => m,
        None => return Err(format!("Router menolak membuka port: {}", last)),
    };

    let addr = format!("{}:{}", ext, m.ext_port);
    let (tx, mut rx) = oneshot::channel::<()>();
    {
        let st = app.state::<UpnpState>();
        *st.map.lock().unwrap() = Some(m.clone());
        *st.addr.lock().unwrap() = Some(addr.clone());
        *st.stop.lock().unwrap() = Some(tx);
    }
    log(app, format!("UPnP aktif: {}", addr), false);

    // perbarui berkala: pemetaan bisa hilang saat router restart, dan IP publik bisa berganti
    let a2 = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            tokio::select! {
                _ = &mut rx => break,
                _ = tokio::time::sleep(Duration::from_secs(900)) => {}
            }
            let local = local_ip_for(&m.host);
            if local.is_empty() {
                continue;
            }
            if let Err(e) = add_map(&m, &local, link::port(&a2)).await {
                log(&a2, format!("UPnP: gagal memperbarui port: {}", e), true);
                continue;
            }
            if let Ok(ip) = external_ip(&m.host, &m.ctl, &m.service).await {
                if ip_kind(&ip) == "ok" {
                    let next = format!("{}:{}", ip, m.ext_port);
                    let changed = {
                        let st = a2.state::<UpnpState>();
                        let mut g = st.addr.lock().unwrap();
                        let diff = g.as_deref() != Some(next.as_str());
                        if diff {
                            *g = Some(next.clone());
                        }
                        diff
                    };
                    if changed {
                        log(&a2, format!("UPnP: alamat berubah jadi {}", next), false);
                        push(&a2);
                    }
                }
            }
        }
    });
    Ok(())
}

async fn release(app: AppHandle) {
    let (tx, m) = {
        let st = app.state::<UpnpState>();
        let tx = st.stop.lock().unwrap().take();
        let m = st.map.lock().unwrap().take();
        *st.addr.lock().unwrap() = None;
        (tx, m)
    };
    if let Some(tx) = tx {
        let _ = tx.send(());
    }
    if let Some(m) = m {
        let args = format!(
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>{}</NewExternalPort><NewProtocol>TCP</NewProtocol>",
            m.ext_port
        );
        let _ = tokio::time::timeout(Duration::from_secs(4), soap(&m.host, &m.ctl, &m.service, "DeletePortMapping", &args)).await;
        log(&app, "UPnP: port ditutup", false);
    }
}

// ---------------------------------------------------------------------------
// Command untuk UI
// ---------------------------------------------------------------------------
#[tauri::command]
pub fn upnp_status(app: AppHandle) -> Value {
    status(&app)
}

#[tauri::command]
pub async fn upnp_start(app: AppHandle) -> Value {
    {
        let st = app.state::<UpnpState>();
        if st.map.lock().unwrap().is_some() || st.busy.swap(true, Ordering::SeqCst) {
            return status(&app);
        }
        *st.err.lock().unwrap() = String::new();
    }
    push(&app);
    if let Err(e) = setup(&app).await {
        log(&app, format!("UPnP: {}", e), true);
        set_err(&app, e);
    }
    app.state::<UpnpState>().busy.store(false, Ordering::SeqCst);
    push(&app);
    status(&app)
}

#[tauri::command]
pub async fn upnp_stop(app: AppHandle) -> Value {
    release(app.clone()).await;
    push(&app);
    status(&app)
}

/// Tutup port di router (dipanggil saat Orbit ditutup).
pub fn shutdown(app: &AppHandle) {
    let has = app.state::<UpnpState>().map.lock().unwrap().is_some();
    if has {
        tauri::async_runtime::block_on(release(app.clone()));
    }
}
