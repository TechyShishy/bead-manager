package com.techyshishy.beadmanager.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.PriceCheckResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinalizeOrderUseCaseTest {

    /** A fresh FMG pack for DB0001 — not stale, so checkAndUpdatePacks skips it. */
    private val fmgPack = VendorPackEntity(
        id = 1,
        beadCode = "DB0001",
        vendorKey = "fmg",
        grams = 10.0,
        url = "https://example.com/pack",
        priceCents = 299,
        available = true,
        lastCheckedEpochSeconds = Long.MAX_VALUE,
    )

    private fun unassignedPendingItem(beadCode: String) = OrderItemEntry(
        beadCode = beadCode,
        vendorKey = "",
        targetGrams = 10.0,
        packGrams = 0.0,
        quantityUnits = 0,
        status = OrderItemStatus.PENDING.firestoreValue,
    )

    /**
     * Returns a [Context] mock whose [ConnectivityManager] reports a fully-validated
     * internet connection, satisfying [FinalizeOrderUseCase.assertConnectivity].
     */
    private fun mockConnectedContext(): Context = mockk {
        every { getSystemService(ConnectivityManager::class.java) } returns mockk {
            val mockNetwork = mockk<android.net.Network>()
            every { activeNetwork } returns mockNetwork
            every { getNetworkCapabilities(mockNetwork) } returns mockk {
                every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
                every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
            }
        }
    }

    private fun catalogRepositoryWith(
        beadNames: Map<Pair<String, String>, String>,
    ): CatalogRepository = mockk {
        coEvery { allBeadsAsMap() } returns emptyMap()
        coEvery { vendorNamesForBeads(any()) } returns beadNames
        coEvery { packsForBead("DB0001") } returns listOf(fmgPack)
        coEvery { checkAndUpdatePacks(any(), any()) } returns PriceCheckResult(emptySet())
    }

    private fun orderRepositoryWith(vararg items: OrderItemEntry): OrderRepository = mockk {
        every { orderStream("order1") } returns flowOf(
            OrderEntry(orderId = "order1", items = items.toList())
        )
    }

    private fun preferencesRepository(): PreferencesRepository = mockk {
        every { vendorPriorityOrder } returns flowOf(listOf("fmg"))
        every { buyUpEnabled } returns flowOf(false)
    }

    @Test
    fun `analyze colorName is populated from beadCode-to-vendorKey key`() = runTest {
        // Populate the map with the correct key order: (beadCode to vendorKey).
        // If the use case accidentally uses (vendorKey to beadCode), the lookup returns null.
        val beadNames = mapOf(("DB0001" to "fmg") to "Cobalt Blue")

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(unassignedPendingItem("DB0001")),
            catalogRepository = catalogRepositoryWith(beadNames),
            preferencesRepository = preferencesRepository(),
        )

        val result = useCase.analyze("order1")

        assertEquals(1, result.items.size)
        assertEquals("Cobalt Blue", result.items[0].colorName)
    }

    @Test
    fun `analyze colorName is null when no matching entry in vendorNamesForBeads`() = runTest {
        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(unassignedPendingItem("DB0001")),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            preferencesRepository = preferencesRepository(),
        )

        val result = useCase.analyze("order1")

        assertEquals(1, result.items.size)
        assertNull(result.items[0].colorName)
    }
}
