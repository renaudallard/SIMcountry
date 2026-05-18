// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! ISub client.
//!
//! Talks to the `isub` service via libbinder_ndk. Transaction codes are
//! the FIRST_CALL_TRANSACTION + AIDL method index, matched against
//! frameworks/base/telephony/java/com/android/internal/telephony/ISub.aidl
//! on the android16-release branch. Any new Android version that
//! reorders / inserts methods needs a re-audit; see the build comment
//! when that day comes.

use std::sync::OnceLock;

use crate::binder::{self, Class};

const DESCRIPTOR: &str = "com.android.internal.telephony.ISub";
const SERVICE_NAME: &str = "isub";

// AIDL index -> transaction code = 1 + index (IBinder.FIRST_CALL_TRANSACTION).
// android16-release: getDefaultDataSubId at index 29, setDefaultDataSubId at index 30.
const TXN_GET_DEFAULT_DATA_SUB_ID: u32 = 1 + 29;
const TXN_SET_DEFAULT_DATA_SUB_ID: u32 = 1 + 30;

static CLASS: OnceLock<Result<Class, String>> = OnceLock::new();

fn class() -> Result<&'static Class, String> {
    match CLASS.get_or_init(|| binder::define_class(DESCRIPTOR)) {
        Ok(c) => Ok(c),
        Err(e) => Err(e.clone()),
    }
}

fn bound_isub() -> Result<binder::Binder, String> {
    let b = binder::get_service(SERVICE_NAME)?;
    binder::associate_class(&b, class()?)?;
    Ok(b)
}

pub fn get_default_data_sub_id() -> Result<i32, String> {
    let b = bound_isub()?;
    let in_parcel = binder::prepare_transaction(&b)?;
    let reply = binder::transact(&b, TXN_GET_DEFAULT_DATA_SUB_ID, in_parcel)?;
    read_void_or_int_return(&reply)
}

pub fn set_default_data_sub_id(sub_id: i32) -> Result<(), String> {
    let b = bound_isub()?;
    let mut in_parcel = binder::prepare_transaction(&b)?;
    in_parcel.write_int32(sub_id)?;
    let reply = binder::transact(&b, TXN_SET_DEFAULT_DATA_SUB_ID, in_parcel)?;
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!("isub.setDefaultDataSubId exception {exception}"));
    }
    Ok(())
}

/// AIDL reply parcels begin with an int32 exception code (0 == no exception).
/// For methods returning `int`, the value follows that header.
fn read_void_or_int_return(reply: &binder::Parcel) -> Result<i32, String> {
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!("isub: aidl exception code {exception}"));
    }
    reply.read_int32()
}
