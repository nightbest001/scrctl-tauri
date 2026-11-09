// SCRCTL — media yang sedang diputar di PC (Spotify, browser, dll) lewat Windows
// System Media Transport Controls (SMTC), untuk kartu musik di Beranda.
// Event "pc-media": { has, app, title, artist, playing, pos, dur } (pos/dur dalam ms).

use serde_json::{json, Value};
use std::sync::Mutex;
use tauri::AppHandle;

static LAST: Mutex<Option<Value>> = Mutex::new(None);

#[tauri::command]
pub fn pc_media_state() -> Value {
    LAST.lock().ok().and_then(|g| g.clone()).unwrap_or(json!({ "has": false }))
}

#[tauri::command]
pub async fn pc_media_cmd(a: String, pos: Option<i64>) -> bool {
    tauri::async_runtime::spawn_blocking(move || imp::command(&a, pos))
        .await
        .unwrap_or(false)
}

pub fn run(app: AppHandle) {
    imp::run(app);
}

#[cfg(windows)]
mod imp {
    use super::LAST;
    use serde_json::{json, Value};
    use std::time::{Duration, Instant};
    use tauri::{AppHandle, Emitter};
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session,
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };
    use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};

    fn init() {
        unsafe {
            let _ = RoInitialize(RO_INIT_MULTITHREADED);
        }
    }

    fn session() -> Option<Session> {
        let mgr = Manager::RequestAsync().ok()?.get().ok()?;
        mgr.GetCurrentSession().ok()
    }

    fn friendly(aumid: &str) -> String {
        let l = aumid.to_lowercase();
        for (k, v) in [
            ("spotify", "Spotify"),
            ("msedge", "Edge"),
            ("chrome", "Chrome"),
            ("firefox", "Firefox"),
            ("brave", "Brave"),
            ("vlc", "VLC"),
            ("musicbee", "MusicBee"),
            ("zunemusic", "Media Player"),
            ("applemusic", "Apple Music"),
        ] {
            if l.contains(k) {
                return v.to_string();
            }
        }
        let base = aumid.split('!').next().unwrap_or(aumid);
        base.trim_end_matches(".exe").to_string()
    }

    fn snapshot() -> Value {
        let s = match session() {
            Some(s) => s,
            None => return json!({ "has": false }),
        };
        let (title, artist) = match s.TryGetMediaPropertiesAsync().and_then(|o| o.get()) {
            Ok(p) => (
                p.Title().map(|t| t.to_string()).unwrap_or_default(),
                p.Artist().map(|t| t.to_string()).unwrap_or_default(),
            ),
            Err(_) => (String::new(), String::new()),
        };
        if title.is_empty() && artist.is_empty() {
            return json!({ "has": false });
        }
        let playing = s
            .GetPlaybackInfo()
            .and_then(|i| i.PlaybackStatus())
            .map(|st| st == Status::Playing)
            .unwrap_or(false);
        let (pos, dur) = match s.GetTimelineProperties() {
            Ok(t) => {
                let p = t.Position().map(|x| x.Duration / 10_000).unwrap_or(0);
                let st = t.StartTime().map(|x| x.Duration / 10_000).unwrap_or(0);
                let e = t.EndTime().map(|x| x.Duration / 10_000).unwrap_or(0);
                ((p - st).max(0), (e - st).max(0))
            }
            Err(_) => (0, 0),
        };
        let aumid = s.SourceAppUserModelId().map(|x| x.to_string()).unwrap_or_default();
        json!({
            "has": true,
            "app": friendly(&aumid),
            "title": title,
            "artist": artist,
            "playing": playing,
            "pos": pos,
            "dur": dur
        })
    }

    fn geti(v: &Value, k: &str) -> i64 {
        v.get(k).and_then(|x| x.as_i64()).unwrap_or(0)
    }

    pub fn run(app: AppHandle) {
        std::thread::spawn(move || {
            init();
            let mut last = Value::Null;
            let mut last_at = Instant::now();
            loop {
                let snap = snapshot();
                let same = ["has", "app", "title", "artist", "playing"].iter().all(|k| last.get(*k) == snap.get(*k));
                let mut emit = !same;
                if same && snap.get("playing").and_then(|x| x.as_bool()).unwrap_or(false) {
                    let expected = geti(&last, "pos") + last_at.elapsed().as_millis() as i64;
                    if (geti(&snap, "pos") - expected).abs() > 1500 {
                        emit = true;
                    }
                }
                if emit {
                    if let Ok(mut g) = LAST.lock() {
                        *g = Some(snap.clone());
                    }
                    let _ = app.emit("pc-media", snap.clone());
                    last = snap;
                    last_at = Instant::now();
                }
                std::thread::sleep(Duration::from_millis(1000));
            }
        });
    }

    pub fn command(a: &str, pos: Option<i64>) -> bool {
        init();
        let s = match session() {
            Some(s) => s,
            None => return false,
        };
        match a {
            "toggle" => s.TryTogglePlayPauseAsync().and_then(|o| o.get()).unwrap_or(false),
            "next" => s.TrySkipNextAsync().and_then(|o| o.get()).unwrap_or(false),
            "prev" => s.TrySkipPreviousAsync().and_then(|o| o.get()).unwrap_or(false),
            "seek" => s
                .TryChangePlaybackPositionAsync(pos.unwrap_or(0).max(0) * 10_000)
                .and_then(|o| o.get())
                .unwrap_or(false),
            _ => false,
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use tauri::AppHandle;
    pub fn run(_app: AppHandle) {}
    pub fn command(_a: &str, _pos: Option<i64>) -> bool {
        false
    }
}
