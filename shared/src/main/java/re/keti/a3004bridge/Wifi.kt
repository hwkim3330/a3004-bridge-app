package re.keti.a3004bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

/**
 * Getting this app's traffic onto the router's own access point.
 *
 * The router is the network: it serves the camera, the microphone, the ring and
 * the control socket, and it has no uplink. That last part is what makes "just
 * join the AP" the wrong design, and it took watching a tablet do it to see why:
 *
 *  - Android leaves an access point that has no internet. Joining A3004-5G
 *    worked, and some minutes later the tablet was back on the building wifi on
 *    its own, with the app showing "connecting" and nothing wrong with it.
 *  - Worse, the building network handed out 192.168.1.58/23, which covers the
 *    router's own 192.168.1.0/24. So 192.168.1.1 was ambiguous, and the app's
 *    packets went to the building network and were dropped.
 *
 * requestNetwork with a WifiNetworkSpecifier is the answer to both. It asks for a
 * connection to one specific AP for this app's use; the system keeps whatever
 * default network it likes for everything else, and bindProcessToNetwork sends
 * only our sockets over the AP. Ambiguous addressing stops mattering, because the
 * route is chosen by interface rather than by prefix.
 *
 * The older join path is kept for API 26-28, where the specifier does not exist,
 * and as a manual escape hatch. The passphrase is a default for a bench device,
 * not a secret; if this is ever pointed at something that matters, the key
 * belongs beside the host in settings rather than in a constant.
 */
object Wifi {

    const val DEFAULT_SSID = "A3004-5G"
    const val DEFAULT_PASS = "keti12345678"

    enum class Route { BOUND, REQUESTING, SYSTEM_DIALOG, SUGGESTION, PICKER, UNSUPPORTED }

    private var cm: ConnectivityManager? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    /** Non-null while this app's sockets are bound to the router's AP. */
    @Volatile
    var bound: Network? = null
        private set

    /**
     * Ask for the router's AP as this app's network.
     *
     * Returns immediately: the system shows a one-time dialog naming the AP, and
     * `bound` becomes non-null once it is connected. `onChange` is called on both
     * transitions so the UI can say which it is rather than guessing.
     */
    fun bindToAp(
        ctx: Context,
        ssid: String = DEFAULT_SSID,
        pass: String = DEFAULT_PASS,
        onChange: (Boolean) -> Unit,
    ): Route {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return legacyJoin(ctx, ssid, pass)

        release(ctx)

        val manager = ctx.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return Route.UNSUPPORTED

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()

        /*
         * NET_CAPABILITY_INTERNET is removed on purpose. The router has no
         * uplink, so a request that insists on internet is a request that can
         * never be satisfied - and this is exactly the capability whose absence
         * makes the system wander off the AP when it is the default network.
         */
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bound = network
                manager.bindProcessToNetwork(network)
                onChange(true)
            }

            override fun onLost(network: Network) {
                if (bound == network) {
                    bound = null
                    manager.bindProcessToNetwork(null)
                    onChange(false)
                }
            }

            override fun onUnavailable() {
                bound = null
                onChange(false)
            }
        }

        cm = manager
        cb = callback
        return try {
            manager.requestNetwork(req, callback)
            Route.REQUESTING
        } catch (e: Exception) {
            cb = null
            legacyJoin(ctx, ssid, pass)
        }
    }

    /** Give the AP back and let the system route normally again. */
    fun release(ctx: Context) {
        val manager = cm ?: ctx.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        val callback = cb
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            runCatching { manager.bindProcessToNetwork(null) }
        }
        cb = null
        bound = null
    }

    /**
     * The pre-API-29 path, and a manual fallback: ask the system to save the
     * network so the user can join it themselves. This makes the AP the device's
     * default network, with the wandering-off problem described above.
     */
    private fun legacyJoin(ctx: Context, ssid: String, pass: String): Route {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ctx is Activity) {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                putExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
            }
            return try {
                ctx.startActivity(intent)
                Route.SYSTEM_DIALOG
            } catch (e: Exception) {
                picker(ctx)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build()
            val ok = runCatching {
                wm?.addNetworkSuggestions(listOf(suggestion)) ==
                        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
            }.getOrDefault(false)
            if (ok) return Route.SUGGESTION
        }
        return picker(ctx)
    }

    private fun picker(ctx: Context): Route = runCatching {
        val i = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        Route.PICKER
    }.getOrDefault(Route.UNSUPPORTED)
}
