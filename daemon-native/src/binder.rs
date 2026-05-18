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

pub type Status = i32;
pub const STATUS_OK: Status = 0;
pub const FLAG_NONE: u32 = 0;
pub const FLAG_ONEWAY: u32 = 1;

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
type ParcelGetDataPositionFn = unsafe extern "C" fn(*const c_void) -> i32;
type ParcelGetDataSizeFn = unsafe extern "C" fn(*const c_void) -> i32;
type AIBinderNewFn = unsafe extern "C" fn(*const c_void, *mut c_void) -> *mut c_void;
type ParcelWriteStrongBinderFn = unsafe extern "C" fn(*mut c_void, *mut c_void) -> Status;

pub type OnTransactFn = extern "C" fn(*mut c_void, u32, *const c_void, *mut c_void) -> Status;
type ParcelWriteStringFn = unsafe extern "C" fn(*mut c_void, *const c_char, i32) -> Status;
type ParcelReadInt64Fn = unsafe extern "C" fn(*const c_void, *mut i64) -> Status;
type ParcelReadBoolFn = unsafe extern "C" fn(*const c_void, *mut bool) -> Status;
type StringAllocator = unsafe extern "C" fn(*mut c_void, i32, *mut *mut c_char) -> bool;
type ByteArrayAllocator = unsafe extern "C" fn(*mut c_void, i32, *mut *mut i8) -> bool;
type StringArrayAllocator = unsafe extern "C" fn(*mut c_void, i32) -> bool;
type StringArrayElementAllocator = unsafe extern "C" fn(*mut c_void, usize, i32, *mut *mut c_char) -> bool;
type ParcelReadStringFn = unsafe extern "C" fn(*const c_void, *mut c_void, StringAllocator) -> Status;
type ParcelReadByteArrayFn = unsafe extern "C" fn(*const c_void, *mut c_void, ByteArrayAllocator) -> Status;
type ParcelReadStringArrayFn = unsafe extern "C" fn(
    *const c_void,
    *mut c_void,
    StringArrayAllocator,
    StringArrayElementAllocator,
) -> Status;

struct Syms {
    get_service: GetServiceFn,
    define_class: DefineClassFn,
    associate_class: AssociateClassFn,
    prepare_transaction: PrepareTransactionFn,
    transact: TransactFn,
    dec_strong: DecStrongFn,
    parcel_delete: ParcelDeleteFn,
    parcel_write_int32: ParcelWriteInt32Fn,
    parcel_write_string: ParcelWriteStringFn,
    parcel_read_int32: ParcelReadInt32Fn,
    parcel_read_int64: ParcelReadInt64Fn,
    parcel_read_bool: ParcelReadBoolFn,
    parcel_read_string: ParcelReadStringFn,
    parcel_read_byte_array: ParcelReadByteArrayFn,
    parcel_read_string_array: ParcelReadStringArrayFn,
    parcel_get_data_position: ParcelGetDataPositionFn,
    parcel_get_data_size: ParcelGetDataSizeFn,
    new_binder: AIBinderNewFn,
    parcel_write_strong_binder: ParcelWriteStrongBinderFn,
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
            parcel_write_string: mem::transmute::<*mut c_void, ParcelWriteStringFn>(load("AParcel_writeString")?),
            parcel_read_int32: mem::transmute::<*mut c_void, ParcelReadInt32Fn>(load("AParcel_readInt32")?),
            parcel_read_int64: mem::transmute::<*mut c_void, ParcelReadInt64Fn>(load("AParcel_readInt64")?),
            parcel_read_bool: mem::transmute::<*mut c_void, ParcelReadBoolFn>(load("AParcel_readBool")?),
            parcel_read_string: mem::transmute::<*mut c_void, ParcelReadStringFn>(load("AParcel_readString")?),
            parcel_read_byte_array: mem::transmute::<*mut c_void, ParcelReadByteArrayFn>(load("AParcel_readByteArray")?),
            parcel_read_string_array: mem::transmute::<*mut c_void, ParcelReadStringArrayFn>(load("AParcel_readStringArray")?),
            parcel_get_data_position: mem::transmute::<*mut c_void, ParcelGetDataPositionFn>(load("AParcel_getDataPosition")?),
            parcel_get_data_size: mem::transmute::<*mut c_void, ParcelGetDataSizeFn>(load("AParcel_getDataSize")?),
            new_binder: mem::transmute::<*mut c_void, AIBinderNewFn>(load("AIBinder_new")?),
            parcel_write_strong_binder: mem::transmute::<*mut c_void, ParcelWriteStrongBinderFn>(load("AParcel_writeStrongBinder")?),
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

// SAFETY: AIBinder is reference-counted and thread-safe per the NDK
// contract -- the strong-count operations are atomic and the underlying
// libbinder C++ object is itself thread-safe.
unsafe impl Send for Binder {}
unsafe impl Sync for Binder {}

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

    pub fn write_strong_binder(&mut self, binder: &Binder) -> Result<(), String> {
        let s = syms()?;
        let st = unsafe { (s.parcel_write_strong_binder)(self.raw, binder.raw) };
        if st != STATUS_OK {
            return Err(format!("AParcel_writeStrongBinder: status {st}"));
        }
        Ok(())
    }

    /// Write a Java-style nullable string. Pass `None` to write null.
    /// Internally NDK encodes the value as UTF-16 on the wire.
    pub fn write_string(&mut self, v: Option<&str>) -> Result<(), String> {
        let s = syms()?;
        let st = match v {
            None => unsafe { (s.parcel_write_string)(self.raw, ptr::null(), -1) },
            Some(text) => {
                let bytes = text.as_bytes();
                unsafe {
                    (s.parcel_write_string)(
                        self.raw,
                        bytes.as_ptr() as *const c_char,
                        bytes.len() as i32,
                    )
                }
            }
        };
        if st != STATUS_OK {
            return Err(format!("AParcel_writeString: status {st}"));
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

    pub fn data_position(&self) -> i32 {
        match syms() {
            Ok(s) => unsafe { (s.parcel_get_data_position)(self.raw as *const c_void) },
            Err(_) => -1,
        }
    }

    pub fn data_size(&self) -> i32 {
        match syms() {
            Ok(s) => unsafe { (s.parcel_get_data_size)(self.raw as *const c_void) },
            Err(_) => -1,
        }
    }

    pub fn read_int64(&self) -> Result<i64, String> {
        let s = syms()?;
        let mut out: i64 = 0;
        let st = unsafe { (s.parcel_read_int64)(self.raw as *const c_void, &mut out) };
        if st != STATUS_OK {
            return Err(format!("AParcel_readInt64: status {st}"));
        }
        Ok(out)
    }

    pub fn read_bool(&self) -> Result<bool, String> {
        let s = syms()?;
        let mut out = false;
        let st = unsafe { (s.parcel_read_bool)(self.raw as *const c_void, &mut out) };
        if st != STATUS_OK {
            return Err(format!("AParcel_readBool: status {st}"));
        }
        Ok(out)
    }

    /// Read a Java string (UTF-16 on wire, returned as UTF-8 String).
    /// Returns None when the parcel value was null.
    pub fn read_string(&self) -> Result<Option<String>, String> {
        let s = syms()?;
        let mut sink: Option<Vec<u8>> = None;
        let st = unsafe {
            (s.parcel_read_string)(
                self.raw as *const c_void,
                &mut sink as *mut _ as *mut c_void,
                string_alloc,
            )
        };
        if st != STATUS_OK {
            return Err(format!("AParcel_readString: status {st}"));
        }
        Ok(sink.map(|v| {
            let end = v.iter().position(|&c| c == 0).unwrap_or(v.len());
            String::from_utf8_lossy(&v[..end]).into_owned()
        }))
    }

    /// Read a Parcel.writeString8 value. Wire format is identical to a
    /// byte array (int32 length, length UTF-8 bytes, padded to 4), so we
    /// route through the byte-array reader and interpret the bytes as
    /// UTF-8. Returns None when the parcel value was null.
    pub fn read_string8(&self) -> Result<Option<String>, String> {
        let bytes = self.read_byte_array()?;
        Ok(bytes.map(|v| String::from_utf8_lossy(&v).into_owned()))
    }

    pub fn read_byte_array(&self) -> Result<Option<Vec<u8>>, String> {
        let s = syms()?;
        let mut sink: Option<Vec<u8>> = None;
        let st = unsafe {
            (s.parcel_read_byte_array)(
                self.raw as *const c_void,
                &mut sink as *mut _ as *mut c_void,
                byte_array_alloc,
            )
        };
        if st != STATUS_OK {
            return Err(format!("AParcel_readByteArray: status {st}"));
        }
        Ok(sink)
    }

    pub fn read_string_array(&self) -> Result<Option<Vec<Option<String>>>, String> {
        let s = syms()?;
        let mut sink: Option<Vec<Option<Vec<u8>>>> = None;
        let st = unsafe {
            (s.parcel_read_string_array)(
                self.raw as *const c_void,
                &mut sink as *mut _ as *mut c_void,
                string_array_alloc,
                string_array_elem_alloc,
            )
        };
        if st != STATUS_OK {
            return Err(format!("AParcel_readStringArray: status {st}"));
        }
        Ok(sink.map(|v| {
            v.into_iter()
                .map(|opt| {
                    opt.map(|b| {
                        let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
                        String::from_utf8_lossy(&b[..end]).into_owned()
                    })
                })
                .collect()
        }))
    }
}

// String / byte-array / string-array allocators used by AParcel_read*.
// They run synchronously inside the read call; the buffer pointer they
// publish via *buffer must remain valid until the read returns. We back
// each one with a Vec stored in user-supplied state and hand the read
// function a pointer to the Vec's heap allocation.

extern "C" fn string_alloc(
    user_data: *mut c_void,
    length: i32,
    buffer: *mut *mut c_char,
) -> bool {
    let slot = unsafe { &mut *(user_data as *mut Option<Vec<u8>>) };
    if length < 0 {
        *slot = None;
        return true;
    }
    let mut vec = vec![0u8; length as usize];
    if !buffer.is_null() {
        unsafe { *buffer = vec.as_mut_ptr() as *mut c_char };
    }
    *slot = Some(vec);
    true
}

extern "C" fn byte_array_alloc(
    user_data: *mut c_void,
    length: i32,
    out: *mut *mut i8,
) -> bool {
    let slot = unsafe { &mut *(user_data as *mut Option<Vec<u8>>) };
    if length < 0 {
        *slot = None;
        return true;
    }
    let mut vec = vec![0u8; length as usize];
    if !out.is_null() {
        unsafe { *out = vec.as_mut_ptr() as *mut i8 };
    }
    *slot = Some(vec);
    true
}

extern "C" fn string_array_alloc(user_data: *mut c_void, length: i32) -> bool {
    let slot = unsafe { &mut *(user_data as *mut Option<Vec<Option<Vec<u8>>>>) };
    if length < 0 {
        *slot = None;
        return true;
    }
    *slot = Some(vec![None; length as usize]);
    true
}

extern "C" fn string_array_elem_alloc(
    user_data: *mut c_void,
    index: usize,
    length: i32,
    buffer: *mut *mut c_char,
) -> bool {
    let slot = unsafe { &mut *(user_data as *mut Option<Vec<Option<Vec<u8>>>>) };
    let arr = match slot.as_mut() {
        Some(a) => a,
        None => return false,
    };
    if length < 0 {
        arr[index] = None;
        return true;
    }
    let mut vec = vec![0u8; length as usize];
    if !buffer.is_null() {
        unsafe { *buffer = vec.as_mut_ptr() as *mut c_char };
    }
    arr[index] = Some(vec);
    true
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
    define_class_with_transact(descriptor, unused_on_transact)
}

pub fn define_class_with_transact(
    descriptor: &str,
    on_transact: OnTransactFn,
) -> Result<Class, String> {
    let s = syms()?;
    let c = CString::new(descriptor).map_err(|e| e.to_string())?;
    let raw = unsafe {
        (s.define_class)(
            c.as_ptr(),
            unused_on_create,
            unused_on_destroy,
            on_transact,
        )
    };
    if raw.is_null() {
        return Err("AIBinder_Class_define returned null".into());
    }
    Ok(Class { raw })
}

/// Allocate a local Binder of the given class. Returned binder's strong
/// refcount starts at 1 -- drop the Binder to release our reference.
pub fn new_binder(class: &Class) -> Result<Binder, String> {
    let s = syms()?;
    let raw = unsafe { (s.new_binder)(class.raw, ptr::null_mut()) };
    if raw.is_null() {
        return Err("AIBinder_new returned null".into());
    }
    Ok(Binder { raw })
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

pub fn transact(binder: &Binder, code: u32, in_parcel: Parcel) -> Result<Parcel, String> {
    transact_flags(binder, code, in_parcel, FLAG_NONE)
}

pub fn transact_flags(
    binder: &Binder,
    code: u32,
    mut in_parcel: Parcel,
    flags: u32,
) -> Result<Parcel, String> {
    let s = syms()?;
    let mut out: *mut c_void = ptr::null_mut();
    // transact consumes in_parcel. Null out our handle BEFORE the call so
    // Drop is a no-op regardless of outcome.
    let mut in_raw = in_parcel.raw;
    in_parcel.raw = ptr::null_mut();
    let st = unsafe { (s.transact)(binder.raw, code, &mut in_raw, &mut out, flags) };
    if st != STATUS_OK {
        if !out.is_null() {
            unsafe { (s.parcel_delete)(out) };
        }
        return Err(format!("AIBinder_transact code={code}: status {st}"));
    }
    Ok(Parcel { raw: out })
}
