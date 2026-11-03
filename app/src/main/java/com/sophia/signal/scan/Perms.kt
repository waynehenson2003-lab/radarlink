package com.sophia.signal.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object Perms {
    fun required(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun wifiOk(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.NEARBY_WIFI_DEVICES))

    fun bleOk(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_SCAN))

    fun locationServicesOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun explain(p: String): Pair<String, String> = when (p) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Precise location" to
            "Android gates Wi-Fi scan results and BLE scanning behind location permission because nearby signals can reveal where you are. SØPHIA never reads GPS and never stores coordinates."
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location" to
            "Requested together with precise location, as Android requires."
        "android.permission.BLUETOOTH_SCAN" -> "Nearby devices (Bluetooth)" to
            "Needed to receive BLE advertisements. SØPHIA only listens; it never connects or pairs."
        "android.permission.NEARBY_WIFI_DEVICES" -> "Nearby devices (Wi-Fi)" to
            "Android 13+ permission for reading Wi-Fi scan results. Declared as never used for location."
        "android.permission.POST_NOTIFICATIONS" -> "Notifications" to
            "Used for watchlist / priority alerts and the scanning indicator. Optional."
        else -> p to ""
    }
}
