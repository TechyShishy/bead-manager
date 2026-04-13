package com.techyshishy.beadmanager

import android.app.Application
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.google.firebase.firestore.FirebaseFirestore
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltAndroidApp
class BeadManagerApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var catalogSeeder: CatalogSeeder
    @Inject lateinit var imageLoader: ImageLoader

    // Application-scoped coroutine scope for fire-and-forget startup work.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Must run BEFORE super.onCreate() so Hilt does not inject any component that opens a
        // Firestore snapshot listener (e.g. ProjectsViewModel). If the local SQLite persistence
        // contains an oversized document_overlays row from a large RGP import, Firestore's sync
        // engine crashes immediately when the first listener is registered. Clearing persistence
        // once prevents that crash; Firestore re-syncs cleanly from the server afterwards.
        clearFirestorePersistenceIfNeeded()
        super.onCreate()
        appScope.launch {
            catalogSeeder.seedIfNeeded()
        }
    }

    /**
     * Clears Firestore's local SQLite persistence exactly once (guarded by a SharedPreferences
     * flag). Must be called before any Firestore listeners are registered — i.e. before
     * [super.onCreate] triggers Hilt injection.
     *
     * Background: importing a large RGP project with the old code stored the entire rows array
     * inline in the ProjectEntry document. Firestore encodes pending mutations as protobuf blobs
     * in local SQLite. A large enough blob triggers [android.database.sqlite.SQLiteBlobTooBigException]
     * the next time Firestore reads document_overlays, crashing every subsequent app launch.
     */
    private fun clearFirestorePersistenceIfNeeded() {
        val prefs = getSharedPreferences("bead_manager_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("cleared_overlay_v1", false)) return
        try {
            Log.i(TAG, "Clearing Firestore local persistence (overlay_v1 migration)")
            runBlocking {
                FirebaseFirestore.getInstance().clearPersistence().await()
            }
            prefs.edit().putBoolean("cleared_overlay_v1", true).commit()
        } catch (e: Exception) {
            // clearPersistence() can fail if the SQLite file is locked by a prior crashed process.
            // The flag is not set, so the next launch retries. The app will proceed with the
            // corrupted overlay still present and likely crash again at the Firestore listener.
            Log.e(TAG, "Firestore clearPersistence failed; will retry on next launch", e)
        }
    }

    private companion object {
        private const val TAG = "BeadManagerApp"
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
