// Orbit — kontrol PC dari HP: mouse, keyboard, media key, dan tangkap layar.
//
// Dipakai oleh link.rs. Bagian input memakai SendInput; tangkap layar memakai GDI (StretchBlt)
// lalu dikompres ke JPEG. Di luar Windows semua fungsi hanya stub supaya proyek tetap bisa dikompilasi.

/// Nama tombol -> (virtual key, extended). Huruf dan angka satu karakter ikut dikenali.
pub fn vk_for(name: &str) -> Option<(u16, bool)> {
    let n = name.trim().to_lowercase();
    let mut chars = n.chars();
    if let (Some(c), None) = (chars.next(), chars.next()) {
        if c.is_ascii_lowercase() {
            return Some((0x41 + (c as u16 - 'a' as u16), false));
        }
        if c.is_ascii_digit() {
            return Some((0x30 + (c as u16 - '0' as u16), false));
        }
    }
    if let Some(num) = n.strip_prefix('f').and_then(|x| x.parse::<u16>().ok()) {
        if (1..=12).contains(&num) {
            return Some((0x70 + num - 1, false));
        }
    }
    let r = match n.as_str() {
        "enter" | "return" => (0x0D, false),
        "esc" | "escape" => (0x1B, false),
        "tab" => (0x09, false),
        "space" => (0x20, false),
        "backspace" => (0x08, false),
        "delete" | "del" => (0x2E, true),
        "insert" => (0x2D, true),
        "home" => (0x24, true),
        "end" => (0x23, true),
        "pageup" => (0x21, true),
        "pagedown" => (0x22, true),
        "left" => (0x25, true),
        "up" => (0x26, true),
        "right" => (0x27, true),
        "down" => (0x28, true),
        "capslock" => (0x14, false),
        "printscreen" => (0x2C, true),
        "menu" => (0x5D, true),
        // modifier
        "ctrl" | "control" => (0xA2, false),
        "alt" => (0xA4, false),
        "shift" => (0xA0, false),
        "win" | "meta" => (0x5B, true),
        // media dan volume
        "play_pause" => (0xB3, true),
        "next" => (0xB0, true),
        "prev" => (0xB1, true),
        "stop" => (0xB2, true),
        "mute" => (0xAD, true),
        "vol_down" => (0xAE, true),
        "vol_up" => (0xAF, true),
        _ => return None,
    };
    Some(r)
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Button {
    Left,
    Right,
    Middle,
}

impl Button {
    pub fn parse(s: &str) -> Button {
        match s {
            "right" => Button::Right,
            "middle" => Button::Middle,
            _ => Button::Left,
        }
    }
}

#[cfg(windows)]
mod imp {
    use super::{vk_for, Button};
    use std::mem::size_of;
    use std::ptr::null_mut;
    use windows_sys::Win32::Foundation::HWND;
    use windows_sys::Win32::Graphics::Gdi::{
        CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC, GetDIBits, ReleaseDC, SelectObject,
        SetStretchBltMode, StretchBlt, BITMAPINFO, BITMAPINFOHEADER, CAPTUREBLT, DIB_RGB_COLORS, HALFTONE, HBITMAP,
        HDC, HGDIOBJ, SRCCOPY,
    };
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP,
        KEYEVENTF_UNICODE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP,
        MOUSEEVENTF_WHEEL, MOUSEINPUT,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        DrawIconEx, GetCursorInfo, GetIconInfo, GetSystemMetrics, CURSORINFO, CURSOR_SHOWING, DI_NORMAL, ICONINFO,
        SM_CXSCREEN, SM_CYSCREEN,
    };

    fn send(inputs: &[INPUT]) {
        if inputs.is_empty() {
            return;
        }
        unsafe {
            SendInput(inputs.len() as u32, inputs.as_ptr(), size_of::<INPUT>() as i32);
        }
    }

    fn mouse(dx: i32, dy: i32, data: u32, flags: u32) -> INPUT {
        INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
        }
    }

    fn key(vk: u16, scan: u16, flags: u32) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
        }
    }

    pub fn screen_size() -> (i32, i32) {
        unsafe { (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN)) }
    }

    pub fn mouse_move_rel(dx: i32, dy: i32) {
        send(&[mouse(dx, dy, 0, MOUSEEVENTF_MOVE)]);
    }

    /// x dan y dinormalisasi 0.0..1.0 terhadap layar utama.
    pub fn mouse_move_abs(nx: f64, ny: f64) {
        let x = (nx.clamp(0.0, 1.0) * 65535.0) as i32;
        let y = (ny.clamp(0.0, 1.0) * 65535.0) as i32;
        send(&[mouse(x, y, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE)]);
    }

    fn button_flag(b: Button, down: bool) -> u32 {
        match (b, down) {
            (Button::Left, true) => MOUSEEVENTF_LEFTDOWN,
            (Button::Left, false) => MOUSEEVENTF_LEFTUP,
            (Button::Right, true) => MOUSEEVENTF_RIGHTDOWN,
            (Button::Right, false) => MOUSEEVENTF_RIGHTUP,
            (Button::Middle, true) => MOUSEEVENTF_MIDDLEDOWN,
            (Button::Middle, false) => MOUSEEVENTF_MIDDLEUP,
        }
    }

    pub fn mouse_button(b: Button, down: bool) {
        send(&[mouse(0, 0, 0, button_flag(b, down))]);
    }

    pub fn mouse_click(b: Button, n: u32) {
        let mut v = Vec::new();
        for _ in 0..n.clamp(1, 3) {
            v.push(mouse(0, 0, 0, button_flag(b, true)));
            v.push(mouse(0, 0, 0, button_flag(b, false)));
        }
        send(&v);
    }

    /// Satuan 120 = satu "klik" roda. dy positif = gulir ke atas, dx positif = ke kanan.
    pub fn scroll(dx: i32, dy: i32) {
        let mut v = Vec::new();
        if dy != 0 {
            v.push(mouse(0, 0, dy as u32, MOUSEEVENTF_WHEEL));
        }
        if dx != 0 {
            v.push(mouse(0, 0, dx as u32, MOUSEEVENTF_HWHEEL));
        }
        send(&v);
    }

    pub fn type_text(s: &str) {
        let mut v = Vec::new();
        for c in s.chars() {
            match c {
                '\r' => {}
                '\n' => {
                    v.push(key(0x0D, 0, 0));
                    v.push(key(0x0D, 0, KEYEVENTF_KEYUP));
                }
                '\t' => {
                    v.push(key(0x09, 0, 0));
                    v.push(key(0x09, 0, KEYEVENTF_KEYUP));
                }
                _ => {
                    let mut buf = [0u16; 2];
                    for u in c.encode_utf16(&mut buf).iter() {
                        v.push(key(0, *u, KEYEVENTF_UNICODE));
                        v.push(key(0, *u, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
                    }
                }
            }
        }
        send(&v);
    }

    fn vk_flags(ext: bool, up: bool) -> u32 {
        (if ext { KEYEVENTF_EXTENDEDKEY } else { 0 }) | (if up { KEYEVENTF_KEYUP } else { 0 })
    }

    /// Tekan satu tombol dengan modifier (Ctrl, Alt, Shift, Win) yang ditahan selama tombol ditekan.
    pub fn key_combo(mods: &[String], name: &str) -> bool {
        let (vk, ext) = match vk_for(name) {
            Some(k) => k,
            None => return false,
        };
        let mut mv: Vec<(u16, bool)> = Vec::new();
        for m in mods {
            match m.as_str() {
                "ctrl" | "alt" | "shift" | "win" => {
                    if let Some(k) = vk_for(m) {
                        mv.push(k);
                    }
                }
                _ => {}
            }
        }
        let mut v = Vec::new();
        for (k, e) in &mv {
            v.push(key(*k, 0, vk_flags(*e, false)));
        }
        v.push(key(vk, 0, vk_flags(ext, false)));
        v.push(key(vk, 0, vk_flags(ext, true)));
        for (k, e) in mv.iter().rev() {
            v.push(key(*k, 0, vk_flags(*e, true)));
        }
        send(&v);
        true
    }

    // -----------------------------------------------------------------------
    // Tangkap layar (GDI)
    // -----------------------------------------------------------------------
    pub struct Grabber {
        max_w: i32,
        sw: i32,
        sh: i32,
        tw: i32,
        th: i32,
        screen: HDC,
        mem: HDC,
        bmp: HBITMAP,
        old: HGDIOBJ,
        cur: Vec<u8>,
        prev: Vec<u8>,
    }

    unsafe impl Send for Grabber {}

    impl Grabber {
        pub fn new(max_w: u32) -> Option<Grabber> {
            Grabber::build(max_w.clamp(320, 3840) as i32)
        }

        fn build(max_w: i32) -> Option<Grabber> {
            unsafe {
                let (sw, sh) = screen_size();
                if sw <= 0 || sh <= 0 {
                    return None;
                }
                let mut tw = sw.min(max_w);
                tw -= tw % 2;
                let th = ((sh as i64 * tw as i64) / sw as i64) as i32;
                let th = (th - th % 2).max(2);
                let screen = GetDC(null_mut() as HWND);
                if screen.is_null() {
                    return None;
                }
                let mem = CreateCompatibleDC(screen);
                let bmp = CreateCompatibleBitmap(screen, tw, th);
                if mem.is_null() || bmp.is_null() {
                    if !bmp.is_null() {
                        DeleteObject(bmp as HGDIOBJ);
                    }
                    if !mem.is_null() {
                        DeleteDC(mem);
                    }
                    ReleaseDC(null_mut() as HWND, screen);
                    return None;
                }
                let old = SelectObject(mem, bmp as HGDIOBJ);
                SetStretchBltMode(mem, HALFTONE as i32);
                let len = (tw as usize) * (th as usize) * 4;
                Some(Grabber {
                    max_w,
                    sw,
                    sh,
                    tw,
                    th,
                    screen,
                    mem,
                    bmp,
                    old,
                    cur: vec![0u8; len],
                    prev: vec![0u8; len],
                })
            }
        }

        fn release(&mut self) {
            unsafe {
                SelectObject(self.mem, self.old);
                DeleteObject(self.bmp as HGDIOBJ);
                DeleteDC(self.mem);
                ReleaseDC(null_mut() as HWND, self.screen);
            }
        }

        /// Ambil satu frame. Some((jpeg, berubah)). `berubah` false kalau gambar sama dengan frame sebelumnya.
        pub fn frame(&mut self, quality: u8) -> Option<(Vec<u8>, bool)> {
            let (sw, sh) = screen_size();
            if sw != self.sw || sh != self.sh {
                let max_w = self.max_w;
                let next = Grabber::build(max_w)?;
                self.release();
                // pindahkan hasil build ke self tanpa memanggil Drop dua kali
                let mut next = std::mem::ManuallyDrop::new(next);
                self.sw = next.sw;
                self.sh = next.sh;
                self.tw = next.tw;
                self.th = next.th;
                self.screen = next.screen;
                self.mem = next.mem;
                self.bmp = next.bmp;
                self.old = next.old;
                self.cur = std::mem::take(&mut next.cur);
                self.prev = std::mem::take(&mut next.prev);
            }
            unsafe {
                if StretchBlt(self.mem, 0, 0, self.tw, self.th, self.screen, 0, 0, self.sw, self.sh, SRCCOPY | CAPTUREBLT)
                    == 0
                {
                    return None;
                }
                self.draw_cursor();
                let mut bi: BITMAPINFO = std::mem::zeroed();
                bi.bmiHeader = BITMAPINFOHEADER {
                    biSize: size_of::<BITMAPINFOHEADER>() as u32,
                    biWidth: self.tw,
                    biHeight: -self.th, // atas ke bawah
                    biPlanes: 1,
                    biBitCount: 32,
                    biCompression: 0, // BI_RGB
                    biSizeImage: 0,
                    biXPelsPerMeter: 0,
                    biYPelsPerMeter: 0,
                    biClrUsed: 0,
                    biClrImportant: 0,
                };
                let got = GetDIBits(
                    self.mem,
                    self.bmp,
                    0,
                    self.th as u32,
                    self.cur.as_mut_ptr() as *mut core::ffi::c_void,
                    &mut bi,
                    DIB_RGB_COLORS,
                );
                if got == 0 {
                    return None;
                }
            }
            let changed = self.cur != self.prev;
            let mut out = Vec::with_capacity(64 * 1024);
            let enc = jpeg_encoder::Encoder::new(&mut out, quality.clamp(20, 90));
            enc.encode(&self.cur, self.tw as u16, self.th as u16, jpeg_encoder::ColorType::Bgra).ok()?;
            std::mem::swap(&mut self.cur, &mut self.prev);
            Some((out, changed))
        }

        unsafe fn draw_cursor(&self) {
            let mut ci: CURSORINFO = std::mem::zeroed();
            ci.cbSize = size_of::<CURSORINFO>() as u32;
            if GetCursorInfo(&mut ci) == 0 || ci.flags & CURSOR_SHOWING == 0 || ci.hCursor.is_null() {
                return;
            }
            let mut ii: ICONINFO = std::mem::zeroed();
            if GetIconInfo(ci.hCursor, &mut ii) == 0 {
                return;
            }
            let x = ((ci.ptScreenPos.x - ii.xHotspot as i32) as i64 * self.tw as i64 / self.sw as i64) as i32;
            let y = ((ci.ptScreenPos.y - ii.yHotspot as i32) as i64 * self.th as i64 / self.sh as i64) as i32;
            DrawIconEx(self.mem, x, y, ci.hCursor, 0, 0, 0, null_mut(), DI_NORMAL);
            if !ii.hbmMask.is_null() {
                DeleteObject(ii.hbmMask as HGDIOBJ);
            }
            if !ii.hbmColor.is_null() {
                DeleteObject(ii.hbmColor as HGDIOBJ);
            }
        }
    }

    impl Drop for Grabber {
        fn drop(&mut self) {
            self.release();
        }
    }
}

#[cfg(windows)]
pub use imp::*;

#[cfg(not(windows))]
mod stub {
    use super::Button;

    pub fn screen_size() -> (i32, i32) {
        (0, 0)
    }
    pub fn mouse_move_rel(_dx: i32, _dy: i32) {}
    pub fn mouse_move_abs(_x: f64, _y: f64) {}
    pub fn mouse_button(_b: Button, _down: bool) {}
    pub fn mouse_click(_b: Button, _n: u32) {}
    pub fn scroll(_dx: i32, _dy: i32) {}
    pub fn type_text(_s: &str) {}
    pub fn key_combo(_mods: &[String], _name: &str) -> bool {
        false
    }

    pub struct Grabber;
    impl Grabber {
        pub fn new(_max_w: u32) -> Option<Grabber> {
            None
        }
        pub fn frame(&mut self, _quality: u8) -> Option<(Vec<u8>, bool)> {
            None
        }
    }
}

#[cfg(not(windows))]
pub use stub::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys() {
        assert_eq!(vk_for("a"), Some((0x41, false)));
        assert_eq!(vk_for("Z"), Some((0x5A, false)));
        assert_eq!(vk_for("5"), Some((0x35, false)));
        assert_eq!(vk_for("F12"), Some((0x7B, false)));
        assert_eq!(vk_for("f13"), None);
        assert_eq!(vk_for("left"), Some((0x25, true)));
        assert_eq!(vk_for("vol_up"), Some((0xAF, true)));
        assert_eq!(vk_for("???"), None);
        assert_eq!(vk_for(""), None);
    }

    #[test]
    fn button_parse() {
        assert_eq!(Button::parse("right"), Button::Right);
        assert_eq!(Button::parse("x"), Button::Left);
    }
}
