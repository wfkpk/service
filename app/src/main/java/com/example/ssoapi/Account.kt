package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

/**
 * Account data class for AIDL communication.
 * This is shared with client apps via AIDL.
 */
data class Account(
    val id: String,
    val email: String,
    val name: String,
    val isActive: Boolean = false
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        email = parcel.readString() ?: "",
        name = parcel.readString() ?: "",
        isActive = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(email)
        parcel.writeString(name)
        parcel.writeByte(if (isActive) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Account> {
        override fun createFromParcel(parcel: Parcel): Account = Account(parcel)
        override fun newArray(size: Int): Array<Account?> = arrayOfNulls(size)
    }
}
