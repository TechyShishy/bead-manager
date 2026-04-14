package com.techyshishy.beadmanager.ui.catalog

import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelPinTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun makeViewModel(): CatalogViewModel {
        val catalogRepository = mockk<CatalogRepository> {
            every { getAllBeadsWithVendors() } returns flowOf(emptyList())
            every { allBeadsLookup() } returns flowOf(emptyMap())
            every { distinctGlassGroups() } returns flowOf(emptyList())
        }
        val inventoryRepository = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(5.0)
        }
        return CatalogViewModel(catalogRepository, inventoryRepository, preferencesRepository)
    }

    @Test
    fun `togglePin with new code adds it to pinnedCodes`() = runTest {
        val vm = makeViewModel()

        vm.togglePin("DB0001")

        assertEquals(listOf("DB0001"), vm.pinnedCodes.value)
    }

    @Test
    fun `togglePin with code already pinned removes it`() = runTest {
        val vm = makeViewModel()

        vm.togglePin("DB0001")
        vm.togglePin("DB0001")

        assertTrue(vm.pinnedCodes.value.isEmpty())
    }

    @Test
    fun `unpinBead removes code from pinnedCodes`() = runTest {
        val vm = makeViewModel()
        vm.togglePin("DB0001")

        vm.unpinBead("DB0001")

        assertTrue(vm.pinnedCodes.value.isEmpty())
    }

    @Test
    fun `clearAllPins empties pinnedCodes`() = runTest {
        val vm = makeViewModel()
        vm.togglePin("DB0001")
        vm.togglePin("DB0002")

        vm.clearAllPins()

        assertTrue(vm.pinnedCodes.value.isEmpty())
    }

    @Test
    fun `clearAllPins also resets stockOnlyFilter`() = runTest {
        val vm = makeViewModel()
        vm.togglePin("DB0001")
        vm.toggleStockOnly()

        vm.clearAllPins()

        assertFalse(vm.stockOnlyFilter.value)
    }

    @Test
    fun `toggleStockOnly flips stockOnlyFilter on each call`() = runTest {
        val vm = makeViewModel()

        assertFalse(vm.stockOnlyFilter.value)

        vm.toggleStockOnly()
        assertTrue(vm.stockOnlyFilter.value)

        vm.toggleStockOnly()
        assertFalse(vm.stockOnlyFilter.value)
    }
}
