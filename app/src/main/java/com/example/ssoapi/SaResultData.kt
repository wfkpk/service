package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

/**
 * SaResultData is returned synchronously from [sso.login].
 *
 * success = true  → login request accepted; async result arrives via IAuthCallback
 * fail    = true  → login request rejected immediately; [message] holds the reason
 */
data class SaResultData(
    val success: Boolean,
    val fail: Boolean,
    val message: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        success = parcel.readByte() != 0.toByte(),
        fail    = parcel.readByte() != 0.toByte(),
        message = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeByte(if (fail)    1 else 0)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SaResultData> {
        override fun createFromParcel(parcel: Parcel): SaResultData = SaResultData(parcel)
        override fun newArray(size: Int): Array<SaResultData?> = arrayOfNulls(size)

        fun accepted(msg: String = "Login request accepted") =
            SaResultData(success = true,  fail = false, message = msg)

        fun rejected(msg: String) =
            SaResultData(success = false, fail = true,  message = msg)
    }
}
