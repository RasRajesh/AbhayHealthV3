package com.example.abhayhealth.core.firebase

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.abhayhealth.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TAG = "FirestoreClient"
private const val PERSISTENT_CACHE_SIZE_BYTES = 100L * 1024L * 1024L // 100 MB offline disk cache

/**
 * Configures and provides the named Enterprise Cloud Firestore instance with explicit
 * persistent disk caching and automatic local cache index creation so reads, queries,
 * and writes work seamlessly with limited or no internet connectivity.
 */
object FirestoreClientProvider {

    private val configuredDatabases = mutableSetOf<String>()

    @Synchronized
    fun getInstance(context: Context): FirebaseFirestore {
        val appContext = context.applicationContext
        val databaseId = appContext.getString(R.string.firestore_database_id)
        val firestore = FirebaseFirestore.getInstance(databaseId)

        if (!configuredDatabases.contains(databaseId)) {
            configureOfflinePersistence(firestore, databaseId)
            configuredDatabases.add(databaseId)
        }
        return firestore
    }

    internal fun configureOfflinePersistence(
        firestore: FirebaseFirestore,
        databaseId: String = "custom"
    ) {
        try {
            val persistentCache = PersistentCacheSettings.newBuilder()
                .setSizeBytes(PERSISTENT_CACHE_SIZE_BYTES)
                .build()
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(persistentCache)
                .build()
            firestore.firestoreSettings = settings
        } catch (e: IllegalStateException) {
            // Firestore settings can only be modified before the first operation on an instance.
            Log.d(TAG, "Firestore settings already initialized for $databaseId: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to apply explicit PersistentCacheSettings for $databaseId", e)
        }

        try {
            firestore.persistentCacheIndexManager?.enableIndexAutoCreation()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enable persistent cache index auto-creation", e)
        }
    }

    fun isNetworkCurrentlyAvailable(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    fun observeNetworkConnectivity(context: Context): Flow<Boolean> = callbackFlow {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            trySend(true)
            close()
            return@callbackFlow
        }

        trySend(isNetworkCurrentlyAvailable(appContext))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isNetworkCurrentlyAvailable(appContext))
            }

            override fun onLost(network: Network) {
                trySend(isNetworkCurrentlyAvailable(appContext))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(validated)
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register ConnectivityManager network callback", e)
            trySend(true)
        }

        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
