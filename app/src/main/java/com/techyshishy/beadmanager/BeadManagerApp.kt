package com.techyshishy.beadmanager

import android.app.Application
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BeadManagerApp : Application() {

    @Inject lateinit var catalogSeeder: CatalogSeeder

    // Application-scoped coroutine scope for fire-and-forget startup work.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            catalogSeeder.seedIfNeeded()
        }
    }
}
