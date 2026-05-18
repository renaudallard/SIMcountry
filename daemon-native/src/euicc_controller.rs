// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

//! IEuiccController client.
//!
//! Drives eSIM profile activation via the `econtroller` system service.
//! The AIDL method takes a PendingIntent for the async result callback.
//! Real PendingIntents can only be minted via ActivityManager from a
//! Java Context, which our native daemon does not have. To unblock the
//! call we publish our own AIBinder advertising the
//! `android.content.IIntentSender` descriptor and pass that in the
//! PendingIntent slot; the platform's eventual call to send() lands on
//! our absorb-everything onTransact and is silently dropped. Callers
//! detect the switch by polling isub::get_default_sub_id.

use std::ffi::c_void;
use std::sync::OnceLock;

use crate::binder::{self, Binder, Class, OnTransactFn, FLAG_ONEWAY, STATUS_OK};

const DESCRIPTOR: &str = "com.android.internal.telephony.euicc.IEuiccController";
const SERVICE_NAME: &str = "econtroller";

// AIDL android16-release method index 8 -> transaction code 9:
//   oneway void switchToSubscription(
//       int cardId, int subscriptionId,
//       String callingPackage, in PendingIntent callbackIntent);
const TXN_SWITCH_TO_SUBSCRIPTION: u32 = 1 + 8;

// IIntentSender.send is the only method on the interface and lands at
// FIRST_CALL_TRANSACTION.
const IINTENT_SENDER_DESCRIPTOR: &str = "android.content.IIntentSender";
const TXN_IINTENT_SENDER_SEND: u32 = 1;

const CALLING_PACKAGE: &str = "com.android.shell";
const DEFAULT_CARD_ID: i32 = -1; // EuiccManager.DEFAULT_CARD_ID

static CLASS: OnceLock<Result<Class, String>> = OnceLock::new();
static SENDER_CLASS: OnceLock<Result<Class, String>> = OnceLock::new();
static SENDER_BINDER: OnceLock<Result<Binder, String>> = OnceLock::new();

fn class() -> Result<&'static Class, String> {
    match CLASS.get_or_init(|| binder::define_class(DESCRIPTOR)) {
        Ok(c) => Ok(c),
        Err(e) => Err(e.clone()),
    }
}

fn bound() -> Result<Binder, String> {
    let b = binder::get_service(SERVICE_NAME)?;
    binder::associate_class(&b, class()?)?;
    Ok(b)
}

extern "C" fn iintent_sender_on_transact(
    _binder: *mut c_void,
    code: u32,
    _in_parcel: *const c_void,
    _out_parcel: *mut c_void,
) -> binder::Status {
    // android.content.IIntentSender has a single oneway method: send.
    // For any other code we report unknown transaction; for send we
    // accept and discard.
    if code == TXN_IINTENT_SENDER_SEND {
        STATUS_OK
    } else {
        -1
    }
}

fn sender_class() -> Result<&'static Class, String> {
    let on_transact: OnTransactFn = iintent_sender_on_transact;
    match SENDER_CLASS.get_or_init(|| {
        binder::define_class_with_transact(IINTENT_SENDER_DESCRIPTOR, on_transact)
    }) {
        Ok(c) => Ok(c),
        Err(e) => Err(e.clone()),
    }
}

fn sender_binder() -> Result<&'static Binder, String> {
    match SENDER_BINDER.get_or_init(|| {
        let c = sender_class()?;
        binder::new_binder(c)
    }) {
        Ok(b) => Ok(b),
        Err(e) => Err(e.clone()),
    }
}

pub fn switch_to_subscription(sub_id: i32) -> Result<(), String> {
    let pi_target = sender_binder()?;
    let b = bound()?;
    let mut in_parcel = binder::prepare_transaction(&b)?;
    in_parcel.write_int32(DEFAULT_CARD_ID)?;
    in_parcel.write_int32(sub_id)?;
    in_parcel.write_string(Some(CALLING_PACKAGE))?;
    // PendingIntent.writeToParcel writes only its IIntentSender binder.
    // writeTypedObject framing: int32(1) for non-null, then the parcelable
    // data -- which for PendingIntent is exactly writeStrongBinder(target).
    in_parcel.write_int32(1)?;
    in_parcel.write_strong_binder(pi_target)?;
    let _reply = binder::transact_flags(&b, TXN_SWITCH_TO_SUBSCRIPTION, in_parcel, FLAG_ONEWAY)?;
    Ok(())
}
