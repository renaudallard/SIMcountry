// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! Minimal FFI shim over libbinder_ndk.so.
//!
//! The NDK headers expose AIBinder_* / AParcel_* but not AServiceManager_*
//! (that one lives in include_platform/, system-only). At runtime the
//! device's libbinder_ndk.so does export it, so we resolve all symbols
//! via dlopen + dlsym on first use and stash them in a OnceLock.
//!
//! Only the bare minimum used by isub::get_default_data_sub_id() etc.
//! is bound. Adding more transactions (string args, parcelables, etc.)
//! will extend Parcel with the corresponding writers/readers.

use std::ffi::{c_char, c_void, CString};
use std::mem;
use std::ptr;
use std::sync::OnceLock;

type Status = i32;
pub const STATUS_OK: Status = 0;
pub const FLAG_NONE: u32 = 0;

type GetServiceFn = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type DefineClassFn = unsafe extern "C" fn(
    *const c_char,
    extern "C" fn(*mut c_void) -> *mut c_void,
    extern "C" fn(*mut c_void),
    extern "C" fn(*mut c_void, u32, *const c_void, *mut c_void) -> Status,
) -> *mut c_void;
type AssociateClassFn = unsafe extern "C" fn(*mut c_void, *const c_void) -> bool;
type PrepareTransactionFn = unsafe extern "C" fn(*mut c_void, *mut *mut c_void) -> Status;
type TransactFn =
    unsafe extern "C" fn(*mut c_void, u32, *mut *mut c_void, *mut *mut c_void, u32) -> Status;
type DecStrongFn = unsafe extern "C" fn(*mut c_void);
type ParcelDeleteFn = unsafe extern "C" fn(*mut c_void);
type ParcelWriteInt32Fn = unsafe extern "C" fn(*mut c_void, i32) -> Status;
type ParcelReadInt32Fn = unsafe extern "C" fn(*const c_void, *mut i32) -> Status;

struct Syms {
    get_service: GetServiceFn,
    define_class: DefineClassFn,
    associate_class: AssociateClassFn,
    prepare_transaction: PrepareTransactionFn,
    transact: TransactFn,
    dec_strong: DecStrongFn,
    parcel_delete: ParcelDeleteFn,
    parcel_write_int32: ParcelWriteInt32Fn,
    parcel_read_int32: ParcelReadInt32Fn,
}

// SAFETY: Syms holds only function pointers from a dlopen'd shared
// library. Function pointers are Send/Sync; the underlying library is
// kept loaded for the lifetime of the process.
unsafe impl Send for Syms {}
unsafe impl Sync for Syms {}

static SYMS: OnceLock<Result<Syms, String>> = OnceLock::new();

fn load_syms() -> Result<Syms, String> {
    unsafe {
        let lib = CString::new("libbinder_ndk.so").unwrap();
        let h = libc::dlopen(lib.as_ptr(), libc::RTLD_NOW);
        if h.is_null() {
            return Err("dlopen libbinder_ndk.so failed".into());
        }
        let load = |name: &str| -> Result<*mut c_void, String> {
            let c = CString::new(name).unwrap();
            let sym = libc::dlsym(h, c.as_ptr());
            if sym.is_null() {
                Err(format!("dlsym {name} not found"))
            } else {
                Ok(sym)
            }
        };
        Ok(Syms {
            get_service: mem::transmute::<*mut c_void, GetServiceFn>(load("AServiceManager_getService")?),
            define_class: mem::transmute::<*mut c_void, DefineClassFn>(load("AIBinder_Class_define")?),
            associate_class: mem::transmute::<*mut c_void, AssociateClassFn>(load("AIBinder_associateClass")?),
            prepare_transaction: mem::transmute::<*mut c_void, PrepareTransactionFn>(load("AIBinder_prepareTransaction")?),
            transact: mem::transmute::<*mut c_void, TransactFn>(load("AIBinder_transact")?),
            dec_strong: mem::transmute::<*mut c_void, DecStrongFn>(load("AIBinder_decStrong")?),
            parcel_delete: mem::transmute::<*mut c_void, ParcelDeleteFn>(load("AParcel_delete")?),
            parcel_write_int32: mem::transmute::<*mut c_void, ParcelWriteInt32Fn>(load("AParcel_writeInt32")?),
            parcel_read_int32: mem::transmute::<*mut c_void, ParcelReadInt32Fn>(load("AParcel_readInt32")?),
        })
    }
}

fn syms() -> Result<&'static Syms, String> {
    match SYMS.get_or_init(load_syms) {
        Ok(s) => Ok(s),
        Err(e) => Err(e.clone()),
    }
}

// Client-only AIBinder_Class has no real callbacks. libbinder_ndk
// rejects null function pointers, so these no-op stubs are mandatory.
extern "C" fn unused_on_create(_: *mut c_void) -> *mut c_void {
    ptr::null_mut()
}
extern "C" fn unused_on_destroy(_: *mut c_void) {}
extern "C" fn unused_on_transact(
    _: *mut c_void,
    _: u32,
    _: *const c_void,
    _: *mut c_void,
) -> Status {
    // Client never serves transactions; assert here would be a fine choice.
    -1
}

pub struct Binder {
    raw: *mut c_void,
}

impl Drop for Binder {
    fn drop(&mut self) {
        if !self.raw.is_null() {
            if let Ok(s) = syms() {
                unsafe { (s.dec_strong)(self.raw) };
            }
        }
    }
}

pub struct Class {
    raw: *mut c_void,
}

// AIBinder_Class instances have process lifetime by design in libbinder_ndk;
// there is no API to release them. We hold a permanent reference. Sharing
// the same Class across threads is safe per the NDK contract.
// SAFETY: see comment above; the raw pointer is read-only and the underlying
// object's lifecycle is owned by libbinder_ndk.
unsafe impl Send for Class {}
unsafe impl Sync for Class {}

pub struct Parcel {
    raw: *mut c_void,
}

impl Drop for Parcel {
    fn drop(&mut self) {
        if !self.raw.is_null() {
            if let Ok(s) = syms() {
                unsafe { (s.parcel_delete)(self.raw) };
            }
        }
    }
}

impl Parcel {
    pub fn write_int32(&mut self, v: i32) -> Result<(), String> {
        let s = syms()?;
        let st = unsafe { (s.parcel_write_int32)(self.raw, v) };
        if st != STATUS_OK {
            return Err(format!("AParcel_writeInt32: status {st}"));
        }
        Ok(())
    }

    pub fn read_int32(&self) -> Result<i32, String> {
        let s = syms()?;
        let mut out: i32 = 0;
        let st = unsafe { (s.parcel_read_int32)(self.raw as *const c_void, &mut out) };
        if st != STATUS_OK {
            return Err(format!("AParcel_readInt32: status {st}"));
        }
        Ok(out)
    }
}

pub fn get_service(name: &str) -> Result<Binder, String> {
    let s = syms()?;
    let c = CString::new(name).map_err(|e| e.to_string())?;
    let raw = unsafe { (s.get_service)(c.as_ptr()) };
    if raw.is_null() {
        return Err(format!("AServiceManager_getService({name}) returned null"));
    }
    Ok(Binder { raw })
}

pub fn define_class(descriptor: &str) -> Result<Class, String> {
    let s = syms()?;
    let c = CString::new(descriptor).map_err(|e| e.to_string())?;
    let raw = unsafe {
        (s.define_class)(
            c.as_ptr(),
            unused_on_create,
            unused_on_destroy,
            unused_on_transact,
        )
    };
    if raw.is_null() {
        return Err("AIBinder_Class_define returned null".into());
    }
    Ok(Class { raw })
}

pub fn associate_class(binder: &Binder, class: &Class) -> Result<(), String> {
    let s = syms()?;
    let ok = unsafe { (s.associate_class)(binder.raw, class.raw) };
    if !ok {
        return Err("AIBinder_associateClass returned false (descriptor mismatch?)".into());
    }
    Ok(())
}

pub fn prepare_transaction(binder: &Binder) -> Result<Parcel, String> {
    let s = syms()?;
    let mut p: *mut c_void = ptr::null_mut();
    let st = unsafe { (s.prepare_transaction)(binder.raw, &mut p) };
    if st != STATUS_OK {
        return Err(format!("AIBinder_prepareTransaction: status {st}"));
    }
    Ok(Parcel { raw: p })
}

pub fn transact(binder: &Binder, code: u32, mut in_parcel: Parcel) -> Result<Parcel, String> {
    let s = syms()?;
    let mut out: *mut c_void = ptr::null_mut();
    // transact consumes in_parcel. Null out our handle BEFORE the call so
    // Drop is a no-op regardless of outcome.
    let mut in_raw = in_parcel.raw;
    in_parcel.raw = ptr::null_mut();
    let st = unsafe { (s.transact)(binder.raw, code, &mut in_raw, &mut out, FLAG_NONE) };
    if st != STATUS_OK {
        if !out.is_null() {
            unsafe { (s.parcel_delete)(out) };
        }
        return Err(format!("AIBinder_transact code={code}: status {st}"));
    }
    Ok(Parcel { raw: out })
}
