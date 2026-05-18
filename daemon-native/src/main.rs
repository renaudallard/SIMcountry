// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

// Phase 0: the only goal is to verify that an ELF shipped inside the APK
// under lib/arm64-v8a/ can be executed by `adb shell` as shell uid on this
// Android 16 device. No daemon behaviour yet.
fn main() {
    let pid = std::process::id();
    let uid = unsafe { libc_getuid() };
    let comm = std::fs::read_to_string("/proc/self/comm").unwrap_or_default();
    println!(
        "simcountry-daemon phase 0 ok pid={pid} uid={uid} comm={}",
        comm.trim()
    );
}

extern "C" {
    #[link_name = "getuid"]
    fn libc_getuid() -> u32;
}
