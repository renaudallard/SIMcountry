// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! Phase 1 daemon.
//!
//! Listens on the Linux abstract socket `\0simcountry-daemon`, accepts
//! length-prefixed frames (4-byte big-endian length, then payload), and
//! replies. The only command implemented is `ping`, which returns `pong`.
//! Used by the in-binary `--ping` test client to verify end-to-end framing.
//!
//! No Binder / telephony work yet; that lands in phase 4+. The wire payload
//! is still plain text and will be swapped to CBOR/JSON when typed messages
//! arrive in phase 3.

use std::ffi::{c_char, c_int, CString};
use std::io::{self, Read, Write};
use std::mem;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::process;
use std::thread;

const TAG: &str = "SimcountryDaemon";
const SOCKET_NAME: &str = "simcountry-daemon";
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
    let listener = match bind_abstract(SOCKET_NAME) {
        Ok(l) => l,
        Err(e) => {
            log(ANDROID_LOG_ERROR, &format!("bind \\0{SOCKET_NAME} failed: {e}"));
            process::exit(1);
        }
    };
    let uid = unsafe { libc::getuid() };
    log(
        ANDROID_LOG_INFO,
        &format!(
            "listening on abstract \\0{SOCKET_NAME} pid={} uid={uid}",
            process::id(),
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

fn handle_client(mut stream: UnixStream) {
    let cred = peer_cred(&stream);
    log(
        ANDROID_LOG_INFO,
        &format!(
            "client connected pid={:?} uid={:?}",
            cred.map(|c| c.pid),
            cred.map(|c| c.uid),
        ),
    );
    loop {
        let frame = match read_frame(&mut stream) {
            Ok(Some(f)) => f,
            Ok(None) => {
                log(ANDROID_LOG_INFO, "client disconnected");
                return;
            }
            Err(e) => {
                log(ANDROID_LOG_WARN, &format!("read frame failed: {e}"));
                return;
            }
        };
        let cmd = match std::str::from_utf8(&frame) {
            Ok(s) => s.trim(),
            Err(_) => {
                let _ = write_frame(&mut stream, b"error: non-utf8");
                continue;
            }
        };
        let reply: Vec<u8> = match cmd {
            "ping" => b"pong".to_vec(),
            other => format!("error: unknown command `{other}`").into_bytes(),
        };
        if let Err(e) = write_frame(&mut stream, &reply) {
            log(ANDROID_LOG_WARN, &format!("write failed: {e}"));
            return;
        }
    }
}

fn run_ping() -> i32 {
    let mut s = match connect_abstract(SOCKET_NAME) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("connect: {e}");
            return 1;
        }
    };
    if let Err(e) = write_frame(&mut s, b"ping") {
        eprintln!("write: {e}");
        return 1;
    }
    match read_frame(&mut s) {
        Ok(Some(reply)) => {
            let txt = String::from_utf8_lossy(&reply);
            println!("{txt}");
            if txt == "pong" {
                0
            } else {
                2
            }
        }
        Ok(None) => {
            eprintln!("eof before reply");
            1
        }
        Err(e) => {
            eprintln!("read: {e}");
            1
        }
    }
}

fn read_frame(s: &mut UnixStream) -> io::Result<Option<Vec<u8>>> {
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

fn write_frame(s: &mut UnixStream, data: &[u8]) -> io::Result<()> {
    let len = u32::try_from(data.len()).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, "frame too large for u32")
    })?;
    s.write_all(&len.to_be_bytes())?;
    s.write_all(data)?;
    s.flush()
}

fn bind_abstract(name: &str) -> io::Result<UnixListener> {
    let owned = abstract_socket(name, |fd, addr, len| unsafe {
        if libc::bind(fd, addr, len) < 0 {
            return Err(io::Error::last_os_error());
        }
        if libc::listen(fd, 8) < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    })?;
    Ok(UnixListener::from(owned))
}

fn connect_abstract(name: &str) -> io::Result<UnixStream> {
    let owned = abstract_socket(name, |fd, addr, len| unsafe {
        if libc::connect(fd, addr, len) < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    })?;
    Ok(UnixStream::from(owned))
}

fn abstract_socket<F>(name: &str, op: F) -> io::Result<OwnedFd>
where
    F: FnOnce(c_int, *const libc::sockaddr, libc::socklen_t) -> io::Result<()>,
{
    let mut addr: libc::sockaddr_un = unsafe { mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let bytes = name.as_bytes();
    if 1 + bytes.len() > addr.sun_path.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "abstract name too long",
        ));
    }
    // Leading null byte = Linux abstract namespace. sun_path entries are
    // c_char which is i8 on aarch64-linux-android, so cast each byte.
    addr.sun_path[0] = 0;
    for (i, &b) in bytes.iter().enumerate() {
        addr.sun_path[1 + i] = b as c_char;
    }
    let addr_len =
        (mem::size_of::<libc::sa_family_t>() + 1 + bytes.len()) as libc::socklen_t;

    let owned = unsafe {
        let fd = libc::socket(
            libc::AF_UNIX,
            libc::SOCK_STREAM | libc::SOCK_CLOEXEC,
            0,
        );
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        OwnedFd::from_raw_fd(fd)
    };
    op(
        owned.as_raw_fd(),
        &addr as *const _ as *const libc::sockaddr,
        addr_len,
    )?;
    Ok(owned)
}

#[derive(Clone, Copy)]
struct PeerCred {
    pid: i32,
    uid: u32,
}

fn peer_cred(s: &UnixStream) -> Option<PeerCred> {
    let mut cred: libc::ucred = unsafe { mem::zeroed() };
    let mut len = mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            s.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut _,
            &mut len,
        )
    };
    if rc < 0 {
        None
    } else {
        Some(PeerCred {
            pid: cred.pid,
            uid: cred.uid,
        })
    }
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
