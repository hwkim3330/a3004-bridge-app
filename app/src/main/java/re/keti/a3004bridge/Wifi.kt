package re.keti.a3004bridge

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

/**
 * Getting the tablet onto the router's own access point.
 *
 * This matters more than it looks. The router is the network: it serves the
 * camera, the microphone, the lidar ring and the control socket, and it has no
 * uplink. A tablet that is meant to walk around with the vehicle therefore has
 * to be associated to the router's AP, not to the building's wifi, and asking
 * someone to leave the app, find the SSID and type a passphrase is the one step
 * most likely to be got wrong in the field.
 *
 * adb cannot do this on their behalf - `cmd wifi connect-network` is refused for
 * the shell uid - so the app asks the system instead. There are three routes and
 * they degrade in that order:
 *
 *  1. API 30+: ACTION_WIFI_ADD_NETWORKS shows a system dialog naming the network
 *     and saves it on confirmation. No permission required, one tap, and the
 *     credential is handled by the platform rather than by us.
 *  2. API 29: addNetworkSuggestions, which needs CHANGE_WIFI_STATE and prompts
 *     once before the system will use the suggestion.
 *  3. Anything older: open the wifi picker so at least the right screen is in
 *     front of the user.
 *
 * The passphrase is a default for a bench device, not a secret. If this is ever
 * pointed at something that matters, the AP's key belongs in the settings field
 * beside the host, not in a constant.
 */
object Wifi {

    const val DEFAULT_SSID = "A3004-5G"
    const val DEFAULT_PASS = "keti12345678"

    /** What was attempted, so the caller can say something truthful in the UI. */
    enum class Route { SYSTEM_DIALOG, SUGGESTION, PICKER, UNSUPPORTED }

    fun join(act: Activity, ssid: String = DEFAULT_SSID,
             pass: String = DEFAULT_PASS): Route {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putExtra(Settings.EXTRA_WIFI_NETWORK_LIST,
                    arrayListOf(suggestion))
            }
            return try {
                act.startActivityForResult(intent, REQ_ADD_NETWORK)
                Route.SYSTEM_DIALOG
            } catch (e: Exception) {
                picker(act)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wm = act.applicationContext
                .getSystemService(WifiManager::class.java)
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build()
            val ok = try {
                wm?.addNetworkSuggestions(listOf(suggestion)) ==
                        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
            } catch (e: Exception) {
                false
            }
            return if (ok) Route.SUGGESTION else picker(act)
        }

        return picker(act)
    }

    private fun picker(act: Activity): Route = try {
        act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        Route.PICKER
    } catch (e: Exception) {
        Route.UNSUPPORTED
    }

    const val REQ_ADD_NETWORK = 4301
}
