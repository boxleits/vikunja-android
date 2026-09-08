package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throws away pooled connections whenever the device gets a network.
 *
 * Reported from a device: after restoring connectivity, syncing kept failing
 * for roughly fifteen seconds, while the browser on the same phone loaded the
 * same Vikunja instance in one. That difference is the whole diagnosis — the
 * network was fine, so the delay was this app's.
 *
 * OkHttp pools connections for reuse and is never told the device changed
 * network. After a drop the pool still holds sockets that look usable and are
 * not; the next request writes to one and waits for an answer that cannot come
 * until TCP gives up, which is about that long. Browsers avoid it by dropping
 * their sockets on a connectivity change, which is exactly what this does.
 */
@Singleton
class NetworkChangeWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiProvider: VikunjaApiProvider,
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Also fires for the network already present when this registers.
            // Evicting an idle pool costs nothing, so that is fine.
            apiProvider.evictPooledConnections()
        }

        override fun onLost(network: Network) {
            // The sockets are already dead; dropping them here means the next
            // attempt doesn't have to discover that the slow way.
            apiProvider.evictPooledConnections()
        }
    }

    fun start() {
        val manager = context.getSystemService<ConnectivityManager>() ?: return
        manager.registerDefaultNetworkCallback(callback)
    }
}
