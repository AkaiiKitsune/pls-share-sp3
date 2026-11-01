package io.github.akaiikitsune.plsshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import javax.net.SocketFactory

/**
 * Joins the printer's own Wi-Fi network and keeps it for this app.
 *
 * The official app's connection trouble comes from Android treating the INSTAX-XXXXXXXX network
 * as "no internet" and routing traffic elsewhere, or dropping it. Requesting the network with a
 * [WifiNetworkSpecifier] makes Android keep it up while the request is alive, and every socket the
 * app opens through [Network.getSocketFactory] is bound to it, whatever the phone does with
 * mobile data or other Wi-Fi networks.
 */
class PrinterNetwork(context: Context) {
    interface Listener {
        fun onConnected()
        fun onFailed()
        fun onLost()
    }

    companion object {
        const val SSID_PREFIX = "INSTAX-"
        private const val REQUEST_TIMEOUT_MS = 60_000
    }

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    val isConnected: Boolean get() = network != null

    /** Ask Android for an INSTAX- network. Android shows a picker with the matching networks. */
    fun connect(listener: Listener) {
        disconnect()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                mainHandler.post { listener.onConnected() }
            }

            override fun onUnavailable() {
                network = null
                mainHandler.post { listener.onFailed() }
            }

            override fun onLost(n: Network) {
                if (network == n) {
                    network = null
                    mainHandler.post { listener.onLost() }
                }
            }
        }
        callback = cb
        connectivity.requestNetwork(request, cb, REQUEST_TIMEOUT_MS)
    }

    /** Release the network. Android may then drop the printer's Wi-Fi. */
    fun disconnect() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
        network = null
    }

    /**
     * A socket factory bound to the printer's network. Falls back to whatever Wi-Fi network the
     * phone is on when the user joined INSTAX- by hand, and to the default factory otherwise.
     */
    fun socketFactory(): SocketFactory {
        val bound = network ?: currentWifi()
        return bound?.socketFactory ?: SocketFactory.getDefault()
    }

    @Suppress("DEPRECATION")
    private fun currentWifi(): Network? = connectivity.allNetworks.firstOrNull { n ->
        connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
