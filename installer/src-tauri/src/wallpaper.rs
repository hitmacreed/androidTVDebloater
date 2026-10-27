//! Sends a background photo to the launcher. The launcher's "From your phone" screen runs a small
//! receiver on the home network; we open that screen over adb with our own one-time code and
//! upload the photo to it, the same way a phone does.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use crate::tv::Tv;

pub const RECEIVER_PORT: u16 = 8765;
const RECEIVER: &str = "com.hitmacreed.androidtvdebloater/.PhoneUploadActivity";
/// The screen needs a moment to open and start listening.
const READY_TIMEOUT: Duration = Duration::from_secs(15);
const IO_TIMEOUT: Duration = Duration::from_secs(30);

pub const NOT_INSTALLED: &str =
    "NLRC isn't installed on this TV yet. Install it first, then change the background.";
pub const SEND_FAILED: &str =
    "Couldn't send the photo to the TV. Check that the TV and this computer are on the same Wi-Fi, then try again.";
pub const BAD_PHOTO: &str = "The TV couldn't open that photo. Try a JPG or PNG.";

/// Six random digits; the receiver accepts only uploads that carry them.
pub fn new_code() -> String {
    let mut h = RandomState::new().build_hasher();
    h.write_u128(Instant::now().elapsed().as_nanos());
    format!("{:06}", h.finish() % 1_000_000)
}

/// Opens the receiver screen on the TV.
pub fn open_receiver(tv: &mut dyn Tv, code: &str) -> Result<(), String> {
    let out = tv
        .shell(&format!("am start -n {RECEIVER} --es code {code}"))
        .map_err(|_| SEND_FAILED.to_string())?;
    // `am start` exits 0 even when it fails, and says so in its output.
    if out.contains("Error") {
        return Err(NOT_INSTALLED.into());
    }
    Ok(())
}

pub fn request(ip: Ipv4Addr, code: &str, photo: &[u8]) -> Vec<u8> {
    let mut req = format!(
        "POST /upload HTTP/1.1\r\nHost: {ip}:{RECEIVER_PORT}\r\nX-Code: {code}\r\n\
         Content-Type: image/jpeg\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        photo.len()
    )
    .into_bytes();
    req.extend_from_slice(photo);
    req
}

/// The status code from the receiver's reply, e.g. 200 from "HTTP/1.1 200 OK".
pub fn status(reply: &str) -> Option<u16> {
    reply.lines().next()?.split_whitespace().nth(1)?.parse().ok()
}

pub fn result_for(status: Option<u16>) -> Result<(), String> {
    match status {
        Some(200) => Ok(()),
        Some(415) | Some(413) => Err(BAD_PHOTO.into()),
        _ => Err(SEND_FAILED.into()),
    }
}

/// Uploads the photo, retrying the connection while the receiver screen opens.
pub fn upload(ip: Ipv4Addr, code: &str, photo: &[u8]) -> Result<(), String> {
    let addr = SocketAddr::from((ip, RECEIVER_PORT));
    let deadline = Instant::now() + READY_TIMEOUT;
    let mut stream = loop {
        match TcpStream::connect_timeout(&addr, Duration::from_secs(2)) {
            Ok(s) => break s,
            Err(_) if Instant::now() < deadline => thread::sleep(Duration::from_millis(500)),
            Err(_) => return Err(SEND_FAILED.into()),
        }
    };
    let sent = (|| -> std::io::Result<String> {
        stream.set_read_timeout(Some(IO_TIMEOUT))?;
        stream.set_write_timeout(Some(IO_TIMEOUT))?;
        stream.write_all(&request(ip, code, photo))?;
        let mut reply = String::new();
        stream.read_to_string(&mut reply)?;
        Ok(reply)
    })();
    match sent {
        Ok(reply) => result_for(status(&reply)),
        Err(_) => Err(SEND_FAILED.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake::FakeTv;

    #[test]
    fn code_is_six_digits() {
        for _ in 0..50 {
            let c = new_code();
            assert_eq!(c.len(), 6, "{c}");
            assert!(c.chars().all(|ch| ch.is_ascii_digit()), "{c}");
        }
    }

    #[test]
    fn opens_the_receiver_with_the_code() {
        let mut tv = FakeTv::new().on("am start", "Starting: Intent { cmp=... }");
        assert_eq!(open_receiver(&mut tv, "123456"), Ok(()));
        assert_eq!(
            tv.log,
            ["am start -n com.hitmacreed.androidtvdebloater/.PhoneUploadActivity --es code 123456"]
        );
    }

    #[test]
    fn missing_launcher_is_explained() {
        let mut tv = FakeTv::new().on(
            "am start",
            "Error: Activity class {com.hitmacreed.androidtvdebloater/...} does not exist.",
        );
        assert_eq!(open_receiver(&mut tv, "123456"), Err(NOT_INSTALLED.into()));
    }

    #[test]
    fn request_carries_code_length_and_photo() {
        let req = request(Ipv4Addr::new(192, 168, 1, 50), "654321", b"JPEG");
        let text = String::from_utf8(req).unwrap();
        assert!(text.starts_with("POST /upload HTTP/1.1\r\n"));
        assert!(text.contains("\r\nX-Code: 654321\r\n"));
        assert!(text.contains("\r\nContent-Length: 4\r\n"));
        assert!(text.ends_with("\r\n\r\nJPEG"));
    }

    #[test]
    fn replies_map_to_plain_results() {
        assert_eq!(result_for(status("HTTP/1.1 200 OK\r\n\r\nOK")), Ok(()));
        assert_eq!(result_for(status("HTTP/1.1 415 Unsupported Media Type")), Err(BAD_PHOTO.into()));
        assert_eq!(result_for(status("HTTP/1.1 403 Forbidden")), Err(SEND_FAILED.into()));
        assert_eq!(result_for(status("")), Err(SEND_FAILED.into()));
    }
}
