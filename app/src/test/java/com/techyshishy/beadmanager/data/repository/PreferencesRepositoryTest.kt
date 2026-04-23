package com.techyshishy.beadmanager.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.techyshishy.beadmanager.data.firestore.FirestorePreferencesSource
import com.techyshishy.beadmanager.data.firestore.PreferencesEntry
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---- test doubles -------------------------------------------------------

    private val firestoreSource = mockk<FirestorePreferencesSource>(relaxed = true)

    private val authListeners = mutableListOf<FirebaseAuth.AuthStateListener>()
    private val mockAuth = mockk<FirebaseAuth>(relaxed = true).also { auth ->
        every { auth.addAuthStateListener(capture(authListeners)) } answers { }
        every { auth.removeAuthStateListener(any()) } answers { }
        every { auth.currentUser } returns null
    }

    private val mockDataStore = mockk<DataStore<Preferences>>(relaxed = true).also { ds ->
        every { ds.data } returns flowOf(emptyPreferences())
    }

    // ---- factory ------------------------------------------------------------

    /**
     * Creates the repository using a scope backed by [UnconfinedTestDispatcher] so that
     * coroutines launched in init{} run synchronously within the test.
     */
    private fun makeRepo(
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
    ): PreferencesRepository = PreferencesRepository(
        dataStore = mockDataStore,
        firestoreSource = firestoreSource,
        auth = mockAuth,
        appScope = scope,
    )

    // ---- auth state helpers -------------------------------------------------

    private fun fireSignIn(uid: String = "uid1") {
        val user = mockk<FirebaseUser> { every { this@mockk.uid } returns uid }
        every { mockAuth.currentUser } returns user
        authListeners.forEach { it.onAuthStateChanged(mockAuth) }
    }

    private fun fireSignOut() {
        every { mockAuth.currentUser } returns null
        authListeners.forEach { it.onAuthStateChanged(mockAuth) }
    }

    // ---- signed-out read flows read from DataStore --------------------------

    @Test
    fun `globalLowStockThreshold reads DataStore value when signed out`() = runTest {
        val key = doublePreferencesKey("global_low_stock_threshold_grams")
        every { mockDataStore.data } returns flowOf(mutablePreferencesOf(key to 8.5))
        val repo = makeRepo()
        fireSignOut()

        val values = repo.globalLowStockThreshold.take(1).toList()

        assertEquals(8.5, values.single(), 0.001)
    }

    @Test
    fun `globalLowStockThreshold emits default when signed out and DataStore has no value`() = runTest {
        every { mockDataStore.data } returns flowOf(emptyPreferences())
        val repo = makeRepo()
        fireSignOut()

        val values = repo.globalLowStockThreshold.take(1).toList()

        assertEquals(5.0, values.single(), 0.001)
    }

    @Test
    fun `vendorPriorityOrder reads DataStore value when signed out`() = runTest {
        val key = stringPreferencesKey("vendor_priority_order")
        every { mockDataStore.data } returns flowOf(mutablePreferencesOf(key to "ac,fmg"))
        val repo = makeRepo()
        fireSignOut()

        val values = repo.vendorPriorityOrder.take(1).toList()

        assertEquals(listOf("ac", "fmg"), values.single())
    }

    @Test
    fun `buyUpEnabled reads DataStore value when signed out`() = runTest {
        val key = booleanPreferencesKey("buy_up_enabled")
        every { mockDataStore.data } returns flowOf(mutablePreferencesOf(key to false))
        val repo = makeRepo()
        fireSignOut()

        val values = repo.buyUpEnabled.take(1).toList()

        assertFalse(values.single())
    }

    // ---- signed-in read flows delegate to Firestore -------------------------

    @Test
    fun `globalLowStockThreshold emits Firestore value when signed in`() = runTest {
        every { firestoreSource.preferencesStream("uid1") } returns
            flowOf(PreferencesEntry(globalLowStockThresholdGrams = 12.0))
        val repo = makeRepo()
        fireSignIn("uid1")

        val values = repo.globalLowStockThreshold.take(1).toList()

        assertEquals(12.0, values.single(), 0.001)
    }

    @Test
    fun `vendorPriorityOrder parses Firestore comma string when signed in`() = runTest {
        every { firestoreSource.preferencesStream("uid1") } returns
            flowOf(PreferencesEntry(vendorPriorityOrder = "ac,fmg"))
        val repo = makeRepo()
        fireSignIn("uid1")

        val values = repo.vendorPriorityOrder.take(1).toList()

        assertEquals(listOf("ac", "fmg"), values.single())
    }

    @Test
    fun `buyUpEnabled emits Firestore value when signed in`() = runTest {
        every { firestoreSource.preferencesStream("uid1") } returns
            flowOf(PreferencesEntry(buyUpEnabled = false))
        val repo = makeRepo()
        fireSignIn("uid1")

        val values = repo.buyUpEnabled.take(1).toList()

        assertFalse(values.single())
    }

    @Test
    fun `globalLowStockThreshold emits default when signed in but document absent`() = runTest {
        every { firestoreSource.preferencesStream("uid1") } returns flowOf(null)
        val repo = makeRepo()
        fireSignIn("uid1")

        val values = repo.globalLowStockThreshold.take(1).toList()

        assertEquals(5.0, values.single(), 0.001)
    }

    // ---- writes always update DataStore; also push Firestore when signed in --

    @Test
    fun `setGlobalLowStockThreshold delegates to firestoreSource when signed in`() = runTest {
        val repo = makeRepo()
        fireSignIn("uid1")

        repo.setGlobalLowStockThreshold(8.0)
        advanceUntilIdle()

        coVerify {
            firestoreSource.setPreferences("uid1", match { it["globalLowStockThresholdGrams"] == 8.0 })
        }
        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setVendorPriorityOrder delegates to firestoreSource when signed in`() = runTest {
        val repo = makeRepo()
        fireSignIn("uid1")

        repo.setVendorPriorityOrder(listOf("ac", "fmg"))
        advanceUntilIdle()

        coVerify {
            firestoreSource.setPreferences("uid1", match { it["vendorPriorityOrder"] == "ac,fmg" })
        }
        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setBuyUpEnabled delegates to firestoreSource when signed in`() = runTest {
        val repo = makeRepo()
        fireSignIn("uid1")

        repo.setBuyUpEnabled(false)
        advanceUntilIdle()

        coVerify {
            firestoreSource.setPreferences("uid1", match { it["buyUpEnabled"] == false })
        }
        coVerify { mockDataStore.updateData(any()) }
    }

    // ---- writes always update DataStore even when signed out ----------------

    @Test
    fun `setGlobalLowStockThreshold writes DataStore and skips Firestore when signed out`() = runTest {
        val repo = makeRepo()
        fireSignOut()

        repo.setGlobalLowStockThreshold(7.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { firestoreSource.setPreferences(any(), any()) }
        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setVendorPriorityOrder writes DataStore and skips Firestore when signed out`() = runTest {
        val repo = makeRepo()
        fireSignOut()

        repo.setVendorPriorityOrder(listOf("ac", "fmg"))
        advanceUntilIdle()

        coVerify(exactly = 0) { firestoreSource.setPreferences(any(), any()) }
        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setBuyUpEnabled writes DataStore and skips Firestore when signed out`() = runTest {
        val repo = makeRepo()
        fireSignOut()

        repo.setBuyUpEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 0) { firestoreSource.setPreferences(any(), any()) }
        coVerify { mockDataStore.updateData(any()) }
    }

    // ---- bootstrap on sign-in -----------------------------------------------

    @Test
    fun `bootstrap calls bootstrapIfAbsent with DataStore snapshot on sign-in`() = runTest {
        val thresholdKey = doublePreferencesKey("global_low_stock_threshold_grams")
        val vendorKey = stringPreferencesKey("vendor_priority_order")
        val buyUpKey = booleanPreferencesKey("buy_up_enabled")
        val trayCardKey = doublePreferencesKey("tray_card_max_grams")

        // DataStore holds custom values that should be forwarded to Firestore
        val prefs = mockk<Preferences>(relaxed = true) {
            every { get(thresholdKey) } returns 9.0
            every { get(vendorKey) } returns "ac,fmg"
            every { get(buyUpKey) } returns false
            every { get(trayCardKey) } returns 25.0
        }
        every { mockDataStore.data } returns flowOf(prefs)

        val repo = makeRepo()
        fireSignIn("uid1")
        advanceUntilIdle()

        coVerify {
            firestoreSource.bootstrapIfAbsent(
                "uid1",
                PreferencesEntry(
                    globalLowStockThresholdGrams = 9.0,
                    vendorPriorityOrder = "ac,fmg",
                    buyUpEnabled = false,
                    trayCardMaxGrams = 25.0,
                ),
            )
        }
    }

    @Test
    fun `bootstrap is not called when signed out`() = runTest {
        val repo = makeRepo()
        fireSignOut()
        advanceUntilIdle()

        coVerify(exactly = 0) { firestoreSource.bootstrapIfAbsent(any(), any()) }
    }

    // ---- trayCardMaxGrams ---------------------------------------------------

    @Test
    fun `trayCardMaxGrams reads DataStore value when signed out`() = runTest {
        val key = doublePreferencesKey("tray_card_max_grams")
        every { mockDataStore.data } returns flowOf(mutablePreferencesOf(key to 20.0))
        val repo = makeRepo()
        fireSignOut()

        val values = repo.trayCardMaxGrams.take(1).toList()

        assertEquals(20.0, values.single(), 0.001)
    }

    @Test
    fun `trayCardMaxGrams emits Firestore value when signed in`() = runTest {
        every { firestoreSource.preferencesStream("uid1") } returns
            flowOf(PreferencesEntry(trayCardMaxGrams = 15.0))
        val repo = makeRepo()
        fireSignIn("uid1")

        val values = repo.trayCardMaxGrams.take(1).toList()

        assertEquals(15.0, values.single(), 0.001)
    }

    @Test
    fun `setTrayCardMaxGrams delegates to firestoreSource when signed in`() = runTest {
        val repo = makeRepo()
        fireSignIn("uid1")

        repo.setTrayCardMaxGrams(25.0)
        advanceUntilIdle()

        coVerify {
            firestoreSource.setPreferences("uid1", match { it["trayCardMaxGrams"] == 25.0 })
        }
        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setTrayCardMaxGrams writes DataStore and skips Firestore when signed out`() = runTest {
        val repo = makeRepo()
        fireSignOut()

        repo.setTrayCardMaxGrams(20.0)
        advanceUntilIdle()

        coVerify(exactly = 0) { firestoreSource.setPreferences(any(), any()) }
        coVerify { mockDataStore.updateData(any()) }
    }
}
