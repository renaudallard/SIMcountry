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

use serde::{Deserialize, Serialize};

use crate::binder::{self, Class};
use crate::iphone_subinfo;

const DESCRIPTOR: &str = "com.android.internal.telephony.ISub";
const SERVICE_NAME: &str = "isub";

// AIDL index -> transaction code = 1 + index (IBinder.FIRST_CALL_TRANSACTION).
// android16-release positions:
//   29 getDefaultDataSubId
//   30 setDefaultDataSubId
//   31 getDefaultVoiceSubId
//   33 setDefaultVoiceSubId      (32 is getDefaultVoiceSubIdAsUser)
//   34 getDefaultSmsSubId
//   36 setDefaultSmsSubId        (35 is getDefaultSmsSubIdAsUser)
//   37 getActiveSubIdList(boolean visibleOnly)
const TXN_GET_DEFAULT_DATA_SUB_ID: u32 = 1 + 29;
const TXN_SET_DEFAULT_DATA_SUB_ID: u32 = 1 + 30;
const TXN_GET_DEFAULT_VOICE_SUB_ID: u32 = 1 + 31;
const TXN_SET_DEFAULT_VOICE_SUB_ID: u32 = 1 + 33;
const TXN_GET_DEFAULT_SMS_SUB_ID: u32 = 1 + 34;
const TXN_SET_DEFAULT_SMS_SUB_ID: u32 = 1 + 36;
const TXN_GET_ACTIVE_SUB_ID_LIST: u32 = 1 + 37;

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

#[derive(Clone, Copy)]
pub enum Aspect {
    Data,
    Voice,
    Sms,
}

impl Aspect {
    fn get_code(self) -> u32 {
        match self {
            Aspect::Data => TXN_GET_DEFAULT_DATA_SUB_ID,
            Aspect::Voice => TXN_GET_DEFAULT_VOICE_SUB_ID,
            Aspect::Sms => TXN_GET_DEFAULT_SMS_SUB_ID,
        }
    }
    fn set_code(self) -> u32 {
        match self {
            Aspect::Data => TXN_SET_DEFAULT_DATA_SUB_ID,
            Aspect::Voice => TXN_SET_DEFAULT_VOICE_SUB_ID,
            Aspect::Sms => TXN_SET_DEFAULT_SMS_SUB_ID,
        }
    }
}

pub fn get_default_sub_id(aspect: Aspect) -> Result<i32, String> {
    let b = bound_isub()?;
    let in_parcel = binder::prepare_transaction(&b)?;
    let reply = binder::transact(&b, aspect.get_code(), in_parcel)?;
    read_int_return(&reply)
}

pub fn set_default_sub_id(aspect: Aspect, sub_id: i32) -> Result<(), String> {
    let b = bound_isub()?;
    let mut in_parcel = binder::prepare_transaction(&b)?;
    in_parcel.write_int32(sub_id)?;
    let reply = binder::transact(&b, aspect.set_code(), in_parcel)?;
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!("isub.setDefault*SubId aidl exception {exception}"));
    }
    Ok(())
}

/// AIDL reply parcels begin with an int32 exception code (0 == no exception).
fn read_int_return(reply: &binder::Parcel) -> Result<i32, String> {
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!("isub: aidl exception code {exception}"));
    }
    reply.read_int32()
}

/// Wire-level enrichment from the daemon: only the fields the app cannot
/// fetch itself. Today that is just iccid (gated behind
/// READ_PRIVILEGED_PHONE_STATE which the shell uid has, but apps do not).
/// Display name / carrier name / mcc / mnc / is_embedded are public via
/// SubscriptionManager, so the app fills those in on its side.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SubInfo {
    pub sub_id: i32,
    pub iccid: String,
}

/// Enumerate every active subscription, attaching the iccid we fetch via
/// IPhoneSubInfo. Per-sub iccid lookups are independent: a single failure
/// surfaces as an empty iccid string on that entry instead of poisoning
/// the whole list.
pub fn list_subs() -> Result<Vec<SubInfo>, String> {
    let ids = get_active_sub_id_list(true)?;
    let mut out = Vec::with_capacity(ids.len());
    for id in ids {
        let iccid = match iphone_subinfo::get_iccid(id) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("subId={id} iccid lookup failed: {e}");
                String::new()
            }
        };
        out.push(SubInfo { sub_id: id, iccid });
    }
    Ok(out)
}

/// Read int[] from `getActiveSubIdList(boolean visibleOnly)`.
pub fn get_active_sub_id_list(visible_only: bool) -> Result<Vec<i32>, String> {
    let b = bound_isub()?;
    let mut in_parcel = binder::prepare_transaction(&b)?;
    in_parcel.write_int32(if visible_only { 1 } else { 0 })?;
    let reply = binder::transact(&b, TXN_GET_ACTIVE_SUB_ID_LIST, in_parcel)?;
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!("isub.getActiveSubIdList aidl exception {exception}"));
    }
    let len = reply.read_int32()?;
    if len < 0 {
        return Ok(Vec::new());
    }
    let mut out = Vec::with_capacity(len as usize);
    for _ in 0..len {
        out.push(reply.read_int32()?);
    }
    Ok(out)
}
