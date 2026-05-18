// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! Phase 3 daemon.
//!
//! Listens on TCP 127.0.0.1:39351. Each frame is a 4-byte big-endian
//! length followed by a JSON-encoded Request or Response. The discriminator
//! field is `kind`. Commands:
//!   * `ping` -> `pong` (keepalive)
//!   * `get_info` -> `info{version, pid, uid}`
//!
//! Unauthenticated for now: any local process with INTERNET can talk to
//! the daemon. Acceptable because no command is privileged yet. The auth
//! handshake lands in the same phase that introduces the first telephony
//! Binder call.

use std::ffi::{c_char, c_int, CString};
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::process;
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};

const TAG: &str = "SimcountryDaemon";
const BIND_ADDR: &str = "127.0.0.1:39351";
const MAX_FRAME: usize = 64 * 1024;

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
    Ping,
    GetInfo,
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Response {
    Pong,
    Info {
        version: String,
        pid: u32,
        uid: u32,
    },
    Error {
        message: String,
    },
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
            "listening on {BIND_ADDR} pid={} uid={uid} version={}",
            process::id(),
            env!("CARGO_PKG_VERSION"),
        ),
    );
    for client in listener.incoming() {
        match client {
            Ok(stream) => {
                thread::spawn(move || handle_client(stream));
            }
            Err(e) => log(ANDROID_LOG_WARN, &format!("accept failed: {e}")),
        }
    }
    process::exit(0);
}

fn handle_client(mut stream: TcpStream) {
    let peer = stream
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "?".into());
    log(ANDROID_LOG_INFO, &format!("client connected: {peer}"));
    let _ = stream.set_read_timeout(Some(Duration::from_secs(30)));
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
        let resp = match serde_json::from_slice::<Request>(&frame) {
            Ok(req) => dispatch(req),
            Err(e) => Response::Error {
                message: format!("parse: {e}"),
            },
        };
        let bytes = match serde_json::to_vec(&resp) {
            Ok(b) => b,
            Err(e) => {
                log(ANDROID_LOG_WARN, &format!("encode failed: {e}"));
                return;
            }
        };
        if let Err(e) = write_frame(&mut stream, &bytes) {
            log(ANDROID_LOG_WARN, &format!("write failed to {peer}: {e}"));
            return;
        }
    }
}

fn dispatch(req: Request) -> Response {
    match req {
        Request::Ping => Response::Pong,
        Request::GetInfo => Response::Info {
            version: env!("CARGO_PKG_VERSION").to_string(),
            pid: process::id(),
            uid: unsafe { libc::getuid() },
        },
    }
}

fn run_ping() -> i32 {
    let mut s = match TcpStream::connect(BIND_ADDR) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("connect: {e}");
            return 1;
        }
    };
    let req = match serde_json::to_vec(&Request::Ping) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("encode: {e}");
            return 1;
        }
    };
    if let Err(e) = write_frame(&mut s, &req) {
        eprintln!("write: {e}");
        return 1;
    }
    let frame = match read_frame(&mut s) {
        Ok(Some(f)) => f,
        Ok(None) => {
            eprintln!("eof before reply");
            return 1;
        }
        Err(e) => {
            eprintln!("read: {e}");
            return 1;
        }
    };
    match serde_json::from_slice::<Response>(&frame) {
        Ok(Response::Pong) => {
            println!("pong");
            0
        }
        Ok(other) => {
            println!("unexpected: {other:?}");
            2
        }
        Err(e) => {
            eprintln!("decode: {e}");
            1
        }
    }
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
