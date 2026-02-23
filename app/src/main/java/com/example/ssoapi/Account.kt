package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

data class Account(
    val guid: String,
    val mail: String,
    val profileImage: String?,
    val sessionToken: String,
    val isActive: Boolean = false
) : Parcelable {

    constructor(parcel: Parcel) : this(
        guid         = parcel.readString() ?: "",
        mail         = parcel.readString() ?: "",
        profileImage = parcel.readString(),
        sessionToken = parcel.readString() ?: "",
        isActive     = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(guid)
        parcel.writeString(mail)
        parcel.writeString(profileImage)
        parcel.writeString(sessionToken)
        parcel.writeByte(if (isActive) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Account> {
        override fun createFromParcel(parcel: Parcel): Account = Account(parcel)
        override fun newArray(size: Int): Array<Account?> = arrayOfNulls(size)
    }
}
