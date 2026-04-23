package com.techyshishy.beadmanager.ui.catalog

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.firestore.FirestoreCatalogPinsSource
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coEvery
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
        return CatalogViewModel(catalogRepository, inventoryRepository, preferencesRepository, pinsSource)
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
        vm.toggleEnoughOnHand()
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
        vm.toggleEnoughOnHand()
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
        vm.toggleEnoughOnHand()
        advanceUntilIdle()

        // Only DB-0001 has non-zero inventory; DB-0002 has none.
        assertEquals(listOf("DB-0001"), result.map { it.code })
    }

    @Test
    fun `clearFilters resets enoughOnHandEnabled but preserves enoughOnHandTargetGrams`() = runTest {
        val vm = buildViewModel(emptyList())
        vm.setEnoughOnHandContext(5.0)
        vm.toggleEnoughOnHand()
        vm.clearFilters()
        assertEquals(false, vm.enoughOnHandEnabled.value)
        assertEquals(5.0, vm.enoughOnHandTargetGrams.value)
    }
}
