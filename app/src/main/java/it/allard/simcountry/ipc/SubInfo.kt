/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.simcountry.ipc

import android.os.Parcel
import android.os.Parcelable

data class SubInfo(
    val subId: Int,
    val iccid: String,
    val carrierName: String,
    val displayName: String,
    val mcc: String?,
    val mnc: String?,
    val isEmbedded: Boolean,
    val isActive: Boolean,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeInt(subId)
        out.writeString(iccid)
        out.writeString(carrierName)
        out.writeString(displayName)
        out.writeString(mcc)
        out.writeString(mnc)
        out.writeInt(if (isEmbedded) 1 else 0)
        out.writeInt(if (isActive) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<SubInfo> {
        override fun createFromParcel(parcel: Parcel): SubInfo = SubInfo(
            subId = parcel.readInt(),
            iccid = parcel.readString().orEmpty(),
            carrierName = parcel.readString().orEmpty(),
            displayName = parcel.readString().orEmpty(),
            mcc = parcel.readString(),
            mnc = parcel.readString(),
            isEmbedded = parcel.readInt() != 0,
            isActive = parcel.readInt() != 0,
        )

        override fun newArray(size: Int): Array<SubInfo?> = arrayOfNulls(size)
    }
}
