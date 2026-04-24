package com.techyshishy.beadmanager.ui.catalog

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.firestore.FirestoreCatalogPinsSource
import com.techyshishy.beadmanager.data.firestore.FirestoreFavoritesSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun beadWithVendors(code: String) = BeadWithVendors(
        bead = BeadEntity(
            code = code,
            hex = "000000",
            imageUrl = "",
            officialUrl = "",
            colorGroup = emptyList(),
            glassGroup = "",
            finishes = emptyList(),
            dyed = "",
            galvanized = "",
            plating = "",
        ),
        vendorLinks = emptyList(),
    )

    private fun inventoryEntry(grams: Double) = InventoryEntry(quantityGrams = grams)

    private fun buildViewModel(
        entries: List<BeadWithVendors>,
        inventory: Map<String, InventoryEntry> = emptyMap(),
        favoritesSource: FirestoreFavoritesSource = mockk {
            every { favoritedCodesStream() } returns flowOf(emptySet())
            coEvery { favorite(any()) } just Runs
            coEvery { unfavorite(any()) } just Runs
        },
    ): CatalogViewModel {
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(entries)
            every { allBeadsLookup() } returns flowOf(emptyMap())
            every { distinctGlassGroups() } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(5.0)
        }
        val pinsSource = mockk<FirestoreCatalogPinsSource> {
            every { pinnedCodesStream() } returns flowOf(emptyList())
            coEvery { setPinnedCodes(any()) } just Runs
        }
        return CatalogViewModel(catalogRepository, inventoryRepository, preferencesRepository, pinsSource, favoritesSource)
    }

    @Test
    fun `enough in stock filter hides beads with insufficient inventory`() = runTest {
        val entries = listOf(
            beadWithVendors("DB-0001"),
            beadWithVendors("DB-0002"),
            beadWithVendors("DB-0003"),
        )
        val inventory = mapOf(
            "DB-0001" to inventoryEntry(8.0),
            "DB-0002" to inventoryEntry(3.0),
            "DB-0003" to inventoryEntry(6.0),
        )
        val vm = buildViewModel(entries, inventory)

        var result: List<BeadWithInventory> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        // All three beads visible before the filter is applied.
        assertEquals(3, result.size)

        vm.setEnoughOnHandContext(6.0)
        advanceUntilIdle()

        // DB-0001 (8g) and DB-0003 (6g) meet the 6g requirement; DB-0002 (3g) does not.
        assertEquals(listOf("DB-0001", "DB-0003"), result.map { it.code })
    }

    @Test
    fun `clearEnoughOnHandFilter restores full bead list`() = runTest {
        val entries = listOf(
            beadWithVendors("DB-0001"),
            beadWithVendors("DB-0002"),
        )
        val inventory = mapOf(
            "DB-0001" to inventoryEntry(10.0),
            "DB-0002" to inventoryEntry(1.0),
        )
        val vm = buildViewModel(entries, inventory)

        var result: List<BeadWithInventory> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        vm.setEnoughOnHandContext(5.0)
        advanceUntilIdle()
        assertEquals(listOf("DB-0001"), result.map { it.code })

        vm.clearEnoughOnHandFilter()
        advanceUntilIdle()
        assertEquals(2, result.size)
    }

    @Test
    fun `enough in stock with zero targetGrams falls back to any non-zero inventory`() = runTest {
        val entries = listOf(
            beadWithVendors("DB-0001"),
            beadWithVendors("DB-0002"),
        )
        val inventory = mapOf(
            "DB-0001" to inventoryEntry(0.5),
            // DB-0002 has no inventory entry — isOwned is false
        )
        val vm = buildViewModel(entries, inventory)

        var result: List<BeadWithInventory> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { result = it }
        }
        advanceUntilIdle()

        vm.setEnoughOnHandContext(0.0)
        advanceUntilIdle()

        // Only DB-0001 has non-zero inventory; DB-0002 has none.
        assertEquals(listOf("DB-0001"), result.map { it.code })
    }

    @Test
    fun `clearFilters resets enoughOnHandEnabled but preserves enoughOnHandTargetGrams`() = runTest {
        val vm = buildViewModel(emptyList())
        vm.setEnoughOnHandContext(5.0)
        vm.clearFilters()
        assertEquals(false, vm.enoughOnHandEnabled.value)
        assertEquals(false, vm.filterState.value.ownedOnly)
        assertEquals(5.0, vm.enoughOnHandTargetGrams.value)
    }

    @Test
    fun `setEnoughOnHandContext auto-enables enoughOnHand and ownedOnly`() = runTest {
        val vm = buildViewModel(emptyList())
        vm.setEnoughOnHandContext(10.0)
        assertEquals(true, vm.enoughOnHandEnabled.value)
        assertEquals(true, vm.filterState.value.ownedOnly)
        assertEquals(10.0, vm.enoughOnHandTargetGrams.value)
    }

    @Test
    fun `clearEnoughOnHandFilter resets ownedOnly`() = runTest {
        val vm = buildViewModel(emptyList())
        vm.setEnoughOnHandContext(5.0)
        assertEquals(true, vm.filterState.value.ownedOnly)
        vm.clearEnoughOnHandFilter()
        assertEquals(false, vm.filterState.value.ownedOnly)
        assertEquals(false, vm.enoughOnHandEnabled.value)
        assertEquals(null, vm.enoughOnHandTargetGrams.value)
    }

    @Test
    fun `favoritedOnly filter hides non-favorited beads`() = runTest {
        val entries = listOf(
            beadWithVendors("DB-0001"),
            beadWithVendors("DB-0002"),
        )
        val vm = buildViewModel(entries)
        val collected = mutableListOf<List<BeadWithInventory>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { collected.add(it) }
        }
        vm.favoritedCodes.value = setOf("DB-0001")
        vm.toggleFavoritedOnly()
        advanceUntilIdle()
        val filtered = collected.last()
        assertEquals(listOf("DB-0001"), filtered.map { it.code })
        job.cancel()
    }

    @Test
    fun `toggleFavorite updates favoritedCodes optimistically and dispatches to Firestore`() = runTest {
        val favoritesSource = mockk<FirestoreFavoritesSource> {
            every { favoritedCodesStream() } returns flowOf(emptySet())
            coEvery { favorite(any()) } just Runs
            coEvery { unfavorite(any()) } just Runs
        }
        val vm = buildViewModel(emptyList(), favoritesSource = favoritesSource)
        vm.toggleFavorite("DB-0001")
        assertEquals(setOf("DB-0001"), vm.favoritedCodes.value)
        advanceUntilIdle()
        coVerify(exactly = 1) { favoritesSource.favorite("DB-0001") }
        vm.toggleFavorite("DB-0001")
        assertEquals(emptySet<String>(), vm.favoritedCodes.value)
        advanceUntilIdle()
        coVerify(exactly = 1) { favoritesSource.unfavorite("DB-0001") }
    }

    @Test
    fun `favoritedOnly filter shows all beads when disabled`() = runTest {
        val entries = listOf(
            beadWithVendors("DB-0001"),
            beadWithVendors("DB-0002"),
        )
        val vm = buildViewModel(entries)
        val collected = mutableListOf<List<BeadWithInventory>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beads.collect { collected.add(it) }
        }
        vm.favoritedCodes.value = setOf("DB-0001")
        vm.toggleFavoritedOnly()
        vm.toggleFavoritedOnly()
        advanceUntilIdle()
        assertEquals(2, collected.last().size)
        job.cancel()
    }
}
