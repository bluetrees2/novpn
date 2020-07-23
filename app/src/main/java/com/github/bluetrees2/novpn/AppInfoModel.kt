package com.github.bluetrees2.novpn

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfoModel (val label: String, val name: String, val icon: Drawable, val uid : Int) {
    interface OnClickListener {
        fun onClick(m: AppInfoModel)
    }
}

data class AppInfoModelLight (val name: String, val uid : Int) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeInt(uid)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AppInfoModelLight> {
        override fun createFromParcel(parcel: Parcel): AppInfoModelLight {
            return AppInfoModelLight(parcel)
        }

        override fun newArray(size: Int): Array<AppInfoModelLight?> {
            return arrayOfNulls(size)
        }
    }
}

suspend fun getInstalledApps(packageManager: PackageManager) : List<AppInfoModel> {
    return withContext(Dispatchers.IO) {
        getInstalledAppsBlocking(packageManager)
    }
}

fun getInstalledAppsBlocking(packageManager: PackageManager): List<AppInfoModel> {
    return packageManager.getInstalledPackages(0)
        .map {
            AppInfoModel(
                it.applicationInfo.loadLabel(packageManager).toString(),
                it.packageName,
                it.applicationInfo.loadIcon(packageManager),
                it.applicationInfo.uid
            )
        }
}