// Orbit — kontrol HP lewat adb (Wi-Fi, Bluetooth, hotspot, buka app) dan Deck.
//
// Wi-Fi/Bluetooth/hotspot tidak bisa diubah app Link biasa (Android melarang), tapi adb punya
// izin shell, jadi tombolnya menjalankan `svc ...` lewat adb ke HP yang sedang dipilih di Beranda.

use serde_json::{json, Value};
use tauri::{AppHandle, State};

use super::{pick, read_json, run_cmd, write_json, AppState};

fn ok_serial(s: &str) -> bool {
    !s.is_empty()
        && s.len() < 80
        && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ':' | '-' | '_'))
}

fn looks_failed(out: &str) -> bool {
    let o = out.to_lowercase();
    ["exception", "error", "not found", "unknown command", "permission denied", "usage:"]
        .iter()
        .any(|k| o.contains(k))
}

#[tauri::command]
pub async fn phone_toggle(app: AppHandle, serial: String, kind: String, on: bool) -> Value {
    if !ok_serial(&serial) {
        return json!({ "ok": false, "err": "Belum ada HP yang dipilih lewat adb" });
    }
    let en = if on { "enable" } else { "disable" };
    let attempts: Vec<Vec<String>> = match kind.as_str() {
        "wifi" => vec![args!["-s", &serial, "shell", "svc", "wifi", en]],
        "bt" => vec![args!["-s", &serial, "shell", "svc", "bluetooth", en]],
        "hotspot" => {
            if on {
                vec![
                    args!["-s", &serial, "shell", "cmd", "connectivity", "start-tethering", "wifi"],
                    args!["-s", &serial, "shell", "svc", "wifi", "tether", "enable"],
                ]
            } else {
                vec![
                    args!["-s", &serial, "shell", "cmd", "connectivity", "stop-tethering", "wifi"],
                    args!["-s", &serial, "shell", "svc", "wifi", "tether", "disable"],
                ]
            }
        }
        _ => return json!({ "ok": false, "err": "jenis tidak dikenal" }),
    };
    let mut last = String::new();
    for argv in attempts {
        let r = run_cmd(&app, argv).await;
        let text = pick(&r.out, &r.err);
        if r.ok && !looks_failed(&text) {
            return json!({ "ok": true });
        }
        last = text;
    }
    let mut err: String = last.trim().chars().take(200).collect();
    if err.is_empty() {
        err = "perintah gagal".to_string();
    }
    json!({ "ok": false, "err": err })
}

// Buka app di HP lewat adb (monkey tidak butuh nama activity).
#[tauri::command]
pub async fn phone_open_app(app: AppHandle, serial: String, pkg: String) -> Value {
    let pkg_ok = !pkg.is_empty()
        && pkg.len() < 200
        && pkg.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_');
    if !ok_serial(&serial) || !pkg_ok {
        return json!({ "ok": false, "err": "HP atau app tidak valid" });
    }
    let r = run_cmd(
        &app,
        args!["-s", &serial, "shell", "monkey", "-p", &pkg, "-c", "android.intent.category.LAUNCHER", "1"],
    )
    .await;
    if r.ok && r.out.contains("Events injected: 1") {
        json!({ "ok": true })
    } else {
        json!({ "ok": false, "err": "App tidak bisa dibuka (cek koneksi adb)" })
    }
}

// ---------------------------------------------------------------------------
// Deck: tombol pintas di PC (media key, buka URL/app, atau perintah ke HP lewat Link)
// ---------------------------------------------------------------------------
#[tauri::command]
pub fn deck_get(st: State<'_, AppState>) -> Value {
    read_json(&st.data_dir.join("deck.json"), Value::Null)
}

#[tauri::command]
pub fn deck_save(st: State<'_, AppState>, deck: Value) {
    write_json(&st.data_dir.join("deck.json"), &deck);
}

#[cfg(windows)]
fn press_key(name: &str) -> Result<(), String> {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::keybd_event;
    let vk: u8 = match name {
        "play_pause" => 0xB3,
        "next" => 0xB0,
        "prev" => 0xB1,
        "stop" => 0xB2,
        "mute" => 0xAD,
        "vol_down" => 0xAE,
        "vol_up" => 0xAF,
        _ => return Err("tombol tidak dikenal".into()),
    };
    unsafe {
        keybd_event(vk, 0, 0, 0);
        keybd_event(vk, 0, 2, 0); // KEYEVENTF_KEYUP
    }
    Ok(())
}

#[cfg(not(windows))]
fn press_key(_name: &str) -> Result<(), String> {
    Err("hanya Windows".into())
}

#[tauri::command]
pub async fn deck_run(kind: String, value: String) -> Value {
    let r: Result<(), String> = match kind.as_str() {
        "key" => press_key(&value),
        "url" => {
            if value.starts_with("http://") || value.starts_with("https://") {
                super::open_with_shell(&value)
            } else {
                Err("URL harus diawali http:// atau https://".into())
            }
        }
        "open" => {
            if value.trim().is_empty() {
                Err("path kosong".into())
            } else {
                super::open_with_shell(value.trim())
            }
        }
        _ => Err("jenis tombol tidak dikenal".into()),
    };
    match r {
        Ok(()) => json!({ "ok": true }),
        Err(e) => json!({ "ok": false, "err": e }),
    }
}
