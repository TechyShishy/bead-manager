package com.techyshishy.beadmanager.ui.catalog

import com.techyshishy.beadmanager.data.firestore.FirestoreCatalogPinsSource
import com.techyshishy.beadmanager.data.firestore.FirestoreFavoritesSource
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.ui.orders.MainDispatcherRule
import io.mockk.coVerify
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

    private fun makeViewModel(
        pinsSource: FirestoreCatalogPinsSource = mockk(relaxed = true) {
            every { pinnedCodesStream() } returns flowOf(emptyList())
        },
        favoritesSource: FirestoreFavoritesSource = mockk(relaxed = true) {
            every { favoritedCodesStream() } returns flowOf(emptySet())
        },
    ): CatalogViewModel {
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
            every { vendorPriorityOrder } returns flowOf(PreferencesRepository.DEFAULT_VENDOR_PRIORITY_ORDER)
        }
        return CatalogViewModel(catalogRepository, inventoryRepository, preferencesRepository, pinsSource, favoritesSource)
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

    @Test
    fun `pinAll replaces pinnedCodes with the given list`() = runTest {
        val vm = makeViewModel()
        vm.togglePin("DB0003")

        vm.pinAll(listOf("DB0001", "DB0002"))

        assertEquals(listOf("DB0001", "DB0002"), vm.pinnedCodes.value)
    }

    @Test
    fun `pinAll with empty list clears pinnedCodes`() = runTest {
        val vm = makeViewModel()
        vm.togglePin("DB0001")

        vm.pinAll(emptyList())

        assertTrue(vm.pinnedCodes.value.isEmpty())
    }

    @Test
    fun `pin adds unpinned bead to pinnedCodes`() = runTest {
        val vm = makeViewModel()

        vm.pin("DB0001")

        assertEquals(listOf("DB0001"), vm.pinnedCodes.value)
    }

    @Test
    fun `pin does not duplicate already-pinned bead`() = runTest {
        val vm = makeViewModel()
        vm.pin("DB0001")

        vm.pin("DB0001")

        assertEquals(listOf("DB0001"), vm.pinnedCodes.value)
    }

    @Test
    fun `reorderPins updates pinnedCodes to new order`() = runTest {
        val pinsSource = mockk<FirestoreCatalogPinsSource>(relaxed = true) {
            every { pinnedCodesStream() } returns flowOf(emptyList())
        }
        val vm = makeViewModel(pinsSource)
        vm.pinAll(listOf("DB0001", "DB0002", "DB0003"))

        vm.reorderPins(listOf("DB0003", "DB0001", "DB0002"))

        assertEquals(listOf("DB0003", "DB0001", "DB0002"), vm.pinnedCodes.value)
        coVerify { pinsSource.setPinnedCodes(listOf("DB0003", "DB0001", "DB0002")) }
    }

    // --- Swap candidate tests ---

    @Test
    fun `addSwapCandidate adds code to swapCandidateCodes`() = runTest {
        val vm = makeViewModel()

        vm.addSwapCandidate("DB0001")

        assertEquals(listOf("DB0001"), vm.swapCandidateCodes.value)
    }

    @Test
    fun `addSwapCandidate with duplicate code is a no-op`() = runTest {
        val vm = makeViewModel()
        vm.addSwapCandidate("DB0001")

        vm.addSwapCandidate("DB0001")

        assertEquals(listOf("DB0001"), vm.swapCandidateCodes.value)
    }

    @Test
    fun `clearSwapCandidates resets swapCandidateCodes to empty`() = runTest {
        val vm = makeViewModel()
        vm.addSwapCandidate("DB0001")
        vm.addSwapCandidate("DB0002")

        vm.clearSwapCandidates()

        assertTrue(vm.swapCandidateCodes.value.isEmpty())
    }

    @Test
    fun `swapCandidateBeads is empty for codes not in the catalog`() = runTest {
        val vm = makeViewModel()

        vm.addSwapCandidate("UNKNOWN")

        assertTrue(vm.swapCandidateBeads.value.isEmpty())
    }
}

