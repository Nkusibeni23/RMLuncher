package com.rmsoft.launcher.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Collects nearby WiFi access points + cell towers in the exact shape the Google Geolocation API
 * expects. rmsoft-server forwards this to that API to resolve a position — which is how LOCATE works
 * INDOORS on RMSoft OS (no GPS signal, no Google Play Services on the device). Needs location
 * permission (granted by Device Owner) for scan results; degrades to empty otherwise.
 */
object NetworkScanner {

    class Scan(val wifi: JSONArray, val cells: JSONArray) {
        val isEmpty: Boolean get() = wifi.length() == 0 && cells.length() == 0
    }

    fun scan(context: Context): Scan = Scan(scanWifi(context), scanCells(context))

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun scanWifi(context: Context): JSONArray {
        val arr = JSONArray()
        if (!hasLocation(context)) return arr
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return arr
        runCatching {
            // As the privileged system app we can flip WiFi on to scan even if the user left it off —
            // WiFi scanning finds nearby access points for indoor location without connecting.
            @Suppress("DEPRECATION")
            if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
            @Suppress("DEPRECATION")
            wifi.startScan() // best-effort refresh; getScanResults returns the latest either way
            wifi.scanResults?.forEach { r ->
                val bssid = r.BSSID
                if (!bssid.isNullOrBlank() && bssid != "00:00:00:00:00:00") {
                    arr.put(
                        JSONObject()
                            .put("macAddress", bssid)
                            .put("signalStrength", r.level),
                    )
                }
            }
        }
        return arr
    }

    private fun scanCells(context: Context): JSONArray {
        val arr = JSONArray()
        if (!hasLocation(context)) return arr
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return arr
        val infos = runCatching { tm.allCellInfo }.getOrNull() ?: return arr
        infos.forEach { info ->
            runCatching {
                val obj = when (info) {
                    is CellInfoLte -> info.cellIdentity.let { id ->
                        cell(id.ci, id.tac, id.mccString, id.mncString, info.cellSignalStrength.dbm, "lte")
                    }
                    is CellInfoWcdma -> info.cellIdentity.let { id ->
                        cell(id.cid, id.lac, id.mccString, id.mncString, info.cellSignalStrength.dbm, "wcdma")
                    }
                    is CellInfoGsm -> info.cellIdentity.let { id ->
                        cell(id.cid, id.lac, id.mccString, id.mncString, info.cellSignalStrength.dbm, "gsm")
                    }
                    else -> null
                }
                if (obj != null) arr.put(obj)
            }
        }
        return arr
    }

    /** Build a Google-format cell tower entry, or null if the required fields are unavailable. */
    private fun cell(
        cellId: Int,
        lac: Int,
        mccStr: String?,
        mncStr: String?,
        dbm: Int,
        radio: String,
    ): JSONObject? {
        val mcc = mccStr?.toIntOrNull() ?: return null
        val mnc = mncStr?.toIntOrNull() ?: return null
        // CellInfo.UNAVAILABLE (Int.MAX_VALUE) or non-positive ids can't be resolved — skip them.
        if (cellId <= 0 || cellId == Int.MAX_VALUE) return null
        return JSONObject()
            .put("cellId", cellId)
            .put("locationAreaCode", if (lac == Int.MAX_VALUE) 0 else lac)
            .put("mobileCountryCode", mcc)
            .put("mobileNetworkCode", mnc)
            .put("signalStrength", dbm)
            .put("radioType", radio)
    }
}
