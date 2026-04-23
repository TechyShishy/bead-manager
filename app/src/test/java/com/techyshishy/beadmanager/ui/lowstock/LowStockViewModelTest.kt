package com.techyshishy.beadmanager.ui.lowstock

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.firestore.InventoryEntry
import com.techyshishy.beadmanager.data.model.BeadWithInventory
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
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
class LowStockViewModelTest {

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

    private fun lowStockInventory() = InventoryEntry(
        quantityGrams = 2.0,
        lowStockThresholdGrams = 5.0,
    )

    @Test
    fun `lowStockBeads emits entries sorted by code ascending`() = runTest {
        // Provide catalog entries in reverse code order to prove sorting is applied.
        val catalogEntries = listOf(
            beadWithVendors("DB-0010"),
            beadWithVendors("DB-0003"),
            beadWithVendors("DB-0007"),
        )
        val inventoryMap = catalogEntries.associate { it.bead.code to lowStockInventory() }

        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(catalogEntries)
        }
        val inventoryRepository = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventoryMap)
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(5.0)
        }

        val vm = LowStockViewModel(catalogRepository, inventoryRepository, preferencesRepository)

        var result: List<BeadWithInventory> = emptyList()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.lowStockBeads.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals(listOf("DB-0003", "DB-0007", "DB-0010"), result.map { it.code })
    }
}
