// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! IPhoneSubInfo client.
//!
//! Exposes the single method we need to enrich the app's own
//! SubscriptionManager enumeration:
//! `getIccSerialNumberForSubscriber(int subId, String pkg, String featureId)`.
//! It returns the iccid as a plain Java string, no parcelable parsing
//! required. Access is gated behind READ_PRIVILEGED_PHONE_STATE, which
//! the shell uid carries by default.

use std::sync::OnceLock;

use crate::binder::{self, Class};

const DESCRIPTOR: &str = "com.android.internal.telephony.IPhoneSubInfo";
const SERVICE_NAME: &str = "iphonesubinfo";

// AIDL android16-release method index 14 -> transaction code 15:
//   String getIccSerialNumberForSubscriber(int subId, String pkg, String featureId);
const TXN_GET_ICC_SERIAL_NUMBER_FOR_SUBSCRIBER: u32 = 1 + 14;

const CALLING_PACKAGE: &str = "com.android.shell";

static CLASS: OnceLock<Result<Class, String>> = OnceLock::new();

fn class() -> Result<&'static Class, String> {
    match CLASS.get_or_init(|| binder::define_class(DESCRIPTOR)) {
        Ok(c) => Ok(c),
        Err(e) => Err(e.clone()),
    }
}

fn bound() -> Result<binder::Binder, String> {
    let b = binder::get_service(SERVICE_NAME)?;
    binder::associate_class(&b, class()?)?;
    Ok(b)
}

pub fn get_iccid(sub_id: i32) -> Result<String, String> {
    let b = bound()?;
    let mut in_parcel = binder::prepare_transaction(&b)?;
    in_parcel.write_int32(sub_id)?;
    in_parcel.write_string(Some(CALLING_PACKAGE))?;
    in_parcel.write_string(None)?;
    let reply = binder::transact(&b, TXN_GET_ICC_SERIAL_NUMBER_FOR_SUBSCRIBER, in_parcel)?;
    let exception = reply.read_int32()?;
    if exception != 0 {
        return Err(format!(
            "iphonesubinfo.getIccSerialNumberForSubscriber({sub_id}) aidl exception {exception}"
        ));
    }
    Ok(reply.read_string()?.unwrap_or_default())
}
