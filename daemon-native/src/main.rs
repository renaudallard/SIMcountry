// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! Phase 4a daemon.
//!
//! Listens on TCP 127.0.0.1:39351. Each frame is a 4-byte big-endian
//! length followed by a JSON-encoded Request or Response. The discriminator
//! is `kind`.
//!
//! Sessions are state machines:
//!   AwaitingHello -> client sends Hello -> Challenge { nonce }
//!   Challenged    -> client sends AuthResponse { hmac } -> AuthOk or AuthFail+close
//!   Authed        -> Ping, GetInfo accepted; out-of-order requests get Error
//!
//! Shared secret is SHA-256 of the installed APK file. The daemon reads
//! it via /proc/self/exe -> <install_dir>/base.apk; the app reads it
//! via PackageManager.applicationInfo.sourceDir. Anyone with shell or
//! INTERNET could in principle recompute the same hash from the public
//! APK, so the gate is mostly defense-in-depth -- it forces the caller
//! to be running the exact same install (same versionCode, signed by
//! same key) and prevents trivial replay across sessions via the nonce.
//! Real per-caller identity (untrusted_app vs another local app) is
//! deferred to a future /proc-based peer check.

use std::ffi::{c_char, c_int, CString};
use std::fs::File;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::process;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const TAG: &str = "SimcountryDaemon";
const BIND_ADDR: &str = "127.0.0.1:39351";
const MAX_FRAME: usize = 64 * 1024;
const NONCE_LEN: usize = 16;

const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_WARN: c_int = 5;
const ANDROID_LOG_ERROR: c_int = 6;

#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(prio: c_int, msg: &str) {
    if let (Ok(t), Ok(m)) = (CString::new(TAG), CString::new(msg)) {
        unsafe { __android_log_write(prio, t.as_ptr(), m.as_ptr()) };
    }
    println!("{msg}");
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Request {
    Hello,
    AuthResponse { hmac: String },
    Ping,
    GetInfo,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Response {
    Challenge { nonce: String },
    AuthOk,
    AuthFail { message: String },
    Pong,
    Info {
        version: String,
        pid: u32,
        uid: u32,
    },
    Error { message: String },
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("--ping") => process::exit(run_ping()),
        Some(unknown) => {
            eprintln!("simcountryd: unknown arg `{unknown}`; usage: simcountryd [--ping]");
            process::exit(2);
        }
        None => run_daemon(),
    }
}

fn run_daemon() -> ! {
    install_signal_handlers();
    let apk_hash = match compute_apk_hash() {
        Ok(h) => Arc::new(h),
        Err(e) => {
            log(ANDROID_LOG_ERROR, &format!("apk hash failed: {e}"));
            process::exit(1);
        }
    };
    let listener = match TcpListener::bind(BIND_ADDR) {
        Ok(l) => l,
        Err(e) => {
            log(ANDROID_LOG_ERROR, &format!("bind {BIND_ADDR} failed: {e}"));
            process::exit(1);
        }
    };
    let uid = unsafe { libc::getuid() };
    log(
        ANDROID_LOG_INFO,
        &format!(
            "listening on {BIND_ADDR} pid={} uid={uid} version={} apk_hash={}…",
            process::id(),
            env!("CARGO_PKG_VERSION"),
            &hex::encode(&apk_hash[..4]),
        ),
    );
    for client in listener.incoming() {
        match client {
            Ok(stream) => {
                let h = Arc::clone(&apk_hash);
                thread::spawn(move || handle_client(stream, h));
            }
            Err(e) => log(ANDROID_LOG_WARN, &format!("accept failed: {e}")),
        }
    }
    process::exit(0);
}

enum SessionState {
    AwaitingHello,
    Challenged { nonce: [u8; NONCE_LEN] },
    Authed,
}

fn handle_client(mut stream: TcpStream, apk_hash: Arc<[u8; 32]>) {
    let peer = stream
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "?".into());
    log(ANDROID_LOG_INFO, &format!("client connected: {peer}"));
    let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
    let mut state = SessionState::AwaitingHello;
    loop {
        let frame = match read_frame(&mut stream) {
            Ok(Some(f)) => f,
            Ok(None) => {
                log(ANDROID_LOG_INFO, &format!("client disconnected: {peer}"));
                return;
            }
            Err(e) => {
                log(ANDROID_LOG_WARN, &format!("read frame failed from {peer}: {e}"));
                return;
            }
        };
        let req = match serde_json::from_slice::<Request>(&frame) {
            Ok(r) => r,
            Err(e) => {
                let _ = write_response(&mut stream, &Response::Error { message: format!("parse: {e}") });
                continue;
            }
        };
        let resp = dispatch(&mut state, &apk_hash, req);
        let must_close = matches!(resp, Response::AuthFail { .. });
        if let Err(e) = write_response(&mut stream, &resp) {
            log(ANDROID_LOG_WARN, &format!("write failed to {peer}: {e}"));
            return;
        }
        if must_close {
            log(ANDROID_LOG_INFO, &format!("auth failed, closing: {peer}"));
            return;
        }
    }
}

fn dispatch(state: &mut SessionState, apk_hash: &[u8; 32], req: Request) -> Response {
    match (&*state, req) {
        (SessionState::AwaitingHello, Request::Hello) => {
            let nonce = match random_bytes::<NONCE_LEN>() {
                Ok(n) => n,
                Err(e) => return Response::Error { message: format!("nonce: {e}") },
            };
            *state = SessionState::Challenged { nonce };
            Response::Challenge { nonce: hex::encode(nonce) }
        }
        (SessionState::Challenged { nonce }, Request::AuthResponse { hmac }) => {
            let mac_in = match hex::decode(&hmac) {
                Ok(b) => b,
                Err(_) => {
                    return Response::AuthFail { message: "bad hex".into() };
                }
            };
            let mut mac = match Hmac::<Sha256>::new_from_slice(apk_hash) {
                Ok(m) => m,
                Err(_) => return Response::AuthFail { message: "hmac init".into() },
            };
            mac.update(nonce);
            if mac.verify_slice(&mac_in).is_ok() {
                *state = SessionState::Authed;
                Response::AuthOk
            } else {
                Response::AuthFail { message: "hmac mismatch".into() }
            }
        }
        (SessionState::Authed, Request::Ping) => Response::Pong,
        (SessionState::Authed, Request::GetInfo) => Response::Info {
            version: env!("CARGO_PKG_VERSION").to_string(),
            pid: process::id(),
            uid: unsafe { libc::getuid() },
        },
        _ => Response::Error {
            message: "out of order or unauthorized".into(),
        },
    }
}

fn run_ping() -> i32 {
    let apk_hash = match compute_apk_hash() {
        Ok(h) => h,
        Err(e) => { eprintln!("apk hash: {e}"); return 1; }
    };
    let mut s = match TcpStream::connect(BIND_ADDR) {
        Ok(s) => s,
        Err(e) => { eprintln!("connect: {e}"); return 1; }
    };
    // Handshake.
    let nonce_bytes = match handshake(&mut s, &apk_hash) {
        Ok(()) => (),
        Err(e) => { eprintln!("handshake: {e}"); return 1; }
    };
    let _ = nonce_bytes;
    // Now ping.
    let req = serde_json::to_vec(&Request::Ping).expect("encode");
    if let Err(e) = write_frame(&mut s, &req) {
        eprintln!("write: {e}"); return 1;
    }
    let frame = match read_frame(&mut s) {
        Ok(Some(f)) => f,
        Ok(None) => { eprintln!("eof"); return 1; }
        Err(e) => { eprintln!("read: {e}"); return 1; }
    };
    match serde_json::from_slice::<Response>(&frame) {
        Ok(Response::Pong) => { println!("pong"); 0 }
        Ok(other) => { println!("unexpected: {other:?}"); 2 }
        Err(e) => { eprintln!("decode: {e}"); 1 }
    }
}

fn handshake(s: &mut TcpStream, apk_hash: &[u8; 32]) -> io::Result<()> {
    write_frame(s, &serde_json::to_vec(&Request::Hello).unwrap())?;
    let frame = read_frame(s)?.ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "no challenge"))?;
    let nonce_hex = match serde_json::from_slice::<Response>(&frame) {
        Ok(Response::Challenge { nonce }) => nonce,
        Ok(other) => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("expected challenge, got {other:?}"))),
        Err(e) => return Err(io::Error::new(io::ErrorKind::InvalidData, format!("decode challenge: {e}"))),
    };
    let nonce = hex::decode(&nonce_hex)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("bad nonce hex: {e}")))?;
    let mut mac = Hmac::<Sha256>::new_from_slice(apk_hash)
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("hmac init: {e}")))?;
    mac.update(&nonce);
    let tag = mac.finalize().into_bytes();
    let auth = Request::AuthResponse { hmac: hex::encode(tag) };
    write_frame(s, &serde_json::to_vec(&auth).unwrap())?;
    let frame = read_frame(s)?.ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "no auth reply"))?;
    match serde_json::from_slice::<Response>(&frame) {
        Ok(Response::AuthOk) => Ok(()),
        Ok(Response::AuthFail { message }) => Err(io::Error::new(io::ErrorKind::PermissionDenied, message)),
        Ok(other) => Err(io::Error::new(io::ErrorKind::InvalidData, format!("expected auth_ok, got {other:?}"))),
        Err(e) => Err(io::Error::new(io::ErrorKind::InvalidData, format!("decode auth reply: {e}"))),
    }
}

fn write_response(stream: &mut TcpStream, resp: &Response) -> io::Result<()> {
    let bytes = serde_json::to_vec(resp)
        .map_err(|e| io::Error::new(io::ErrorKind::Other, format!("encode: {e}")))?;
    write_frame(stream, &bytes)
}

fn read_frame<R: Read>(s: &mut R) -> io::Result<Option<Vec<u8>>> {
    let mut len_buf = [0u8; 4];
    match s.read_exact(&mut len_buf) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_be_bytes(len_buf) as usize;
    if len > MAX_FRAME {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame too big: {len} > {MAX_FRAME}"),
        ));
    }
    let mut buf = vec![0u8; len];
    s.read_exact(&mut buf)?;
    Ok(Some(buf))
}

fn write_frame<W: Write>(s: &mut W, data: &[u8]) -> io::Result<()> {
    let len = u32::try_from(data.len()).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, "frame too large for u32")
    })?;
    s.write_all(&len.to_be_bytes())?;
    s.write_all(data)?;
    s.flush()
}

fn random_bytes<const N: usize>() -> io::Result<[u8; N]> {
    let mut buf = [0u8; N];
    let n = unsafe {
        libc::getrandom(buf.as_mut_ptr() as *mut libc::c_void, buf.len(), 0)
    };
    if n != buf.len() as isize {
        return Err(io::Error::last_os_error());
    }
    Ok(buf)
}

/// Resolve the install dir of the running binary and hash the APK there.
/// /proc/self/exe -> /data/app/<install>/lib/<abi>/libsimcountryd.so
/// APK is at /data/app/<install>/base.apk.
fn compute_apk_hash() -> io::Result<[u8; 32]> {
    let exe: PathBuf = std::fs::read_link("/proc/self/exe")?;
    let install_dir = exe
        .parent()
        .and_then(|p| p.parent())
        .and_then(|p| p.parent())
        .ok_or_else(|| io::Error::new(io::ErrorKind::Other, format!("cannot derive install dir from {exe:?}")))?;
    let apk = install_dir.join("base.apk");
    let mut f = File::open(&apk)
        .map_err(|e| io::Error::new(e.kind(), format!("open {apk:?}: {e}")))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let digest = hasher.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest);
    Ok(out)
}

extern "C" fn on_signal(_sig: c_int) {
    // Only async-signal-safe calls allowed here.
    unsafe { libc::_exit(0) };
}

fn install_signal_handlers() {
    let h = on_signal as *const () as usize;
    unsafe {
        libc::signal(libc::SIGTERM, h);
        libc::signal(libc::SIGINT, h);
        // SIGPIPE on a closed client should not kill the daemon.
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    }
}
