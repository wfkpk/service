package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

data class AuthResult(
    val success: Boolean,
    val fail: Boolean,
    val message: String = ""
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

    companion object CREATOR : Parcelable.Creator<AuthResult> {
        override fun createFromParcel(parcel: Parcel): AuthResult = AuthResult(parcel)
        override fun newArray(size: Int): Array<AuthResult?> = arrayOfNulls(size)
    }
}
