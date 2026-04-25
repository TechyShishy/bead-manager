package com.techyshishy.beadmanager.ui.detail

import com.techyshishy.beadmanager.data.db.BeadEntity
import com.techyshishy.beadmanager.data.db.BeadWithVendors
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeadDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun beadEntity(code: String = "DB-010") = BeadEntity(
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
    )

    private fun vendorLink(
        beadCode: String = "DB-010",
        vendorKey: String,
        beadName: String?,
    ) = VendorLinkEntity(
        beadCode = beadCode,
        vendorKey = vendorKey,
        displayName = vendorKey,
        url = "https://example.com",
        beadName = beadName,
    )

    private fun buildViewModel(
        vendorLinks: List<VendorLinkEntity>,
        priorityOrder: List<String> = listOf("fmg", "bob", "ac"),
    ): BeadDetailViewModel {
        val beadCode = "DB-010"
        val beadWithVendors = BeadWithVendors(bead = beadEntity(beadCode), vendorLinks = vendorLinks)

        val catalogRepository = mockk<CatalogRepository> {
            every { getBeadWithVendors(beadCode) } returns flowOf(beadWithVendors)
        }
        val inventoryRepository = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(emptyMap())
        }
        val preferencesRepository = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(5.0)
            every { vendorPriorityOrder } returns flowOf(priorityOrder)
        }

        val vm = BeadDetailViewModel(catalogRepository, inventoryRepository, preferencesRepository)
        vm.initialize(beadCode)
        return vm
    }

    @Test
    fun `beadName returns name from highest-priority vendor`() = runTest {
        val vm = buildViewModel(
            vendorLinks = listOf(
                vendorLink(vendorKey = "ac", beadName = "Black (Aura)"),
                vendorLink(vendorKey = "fmg", beadName = "Opaque Black"),
                vendorLink(vendorKey = "bob", beadName = "Black (Bob)"),
            ),
            priorityOrder = listOf("fmg", "bob", "ac"),
        )

        var result: String? = "unset"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beadName.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals("Opaque Black", result)
    }

    @Test
    fun `beadName skips blank names and falls back to next priority vendor`() = runTest {
        val vm = buildViewModel(
            vendorLinks = listOf(
                vendorLink(vendorKey = "fmg", beadName = ""),
                vendorLink(vendorKey = "bob", beadName = "Black (Bob)"),
                vendorLink(vendorKey = "ac", beadName = "Black (Aura)"),
            ),
            priorityOrder = listOf("fmg", "bob", "ac"),
        )

        var result: String? = "unset"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beadName.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals("Black (Bob)", result)
    }

    @Test
    fun `beadName returns null when all vendor links have blank or null names`() = runTest {
        val vm = buildViewModel(
            vendorLinks = listOf(
                vendorLink(vendorKey = "fmg", beadName = null),
                vendorLink(vendorKey = "bob", beadName = ""),
                vendorLink(vendorKey = "ac", beadName = "  "),
            ),
            priorityOrder = listOf("fmg", "bob", "ac"),
        )

        var result: String? = "unset"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beadName.collect { result = it }
        }
        advanceUntilIdle()

        assertNull(result)
    }

    @Test
    fun `beadName returns null when there are no vendor links`() = runTest {
        val vm = buildViewModel(vendorLinks = emptyList())

        var result: String? = "unset"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beadName.collect { result = it }
        }
        advanceUntilIdle()

        assertNull(result)
    }

    @Test
    fun `beadName falls back to vendor not in priorityOrder when prioritized vendors have no name`() = runTest {
        val vm = buildViewModel(
            vendorLinks = listOf(
                vendorLink(vendorKey = "fmg", beadName = null),
                vendorLink(vendorKey = "unknown_vendor", beadName = "Mystery Black"),
            ),
            priorityOrder = listOf("fmg", "bob", "ac"),
        )

        var result: String? = "unset"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.beadName.collect { result = it }
        }
        advanceUntilIdle()

        assertEquals("Mystery Black", result)
    }
}
