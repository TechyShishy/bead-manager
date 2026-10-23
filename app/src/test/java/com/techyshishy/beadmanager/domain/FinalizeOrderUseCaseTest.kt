package com.techyshishy.beadmanager.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.model.SUFFICIENT_THRESHOLD_GRAMS
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.InventoryRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.PriceCheckResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

    private fun inventoryRepository(): InventoryRepository = mockk {
        every { inventoryStream() } returns flowOf(emptyMap())
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
            inventoryRepository = inventoryRepository(),
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
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferencesRepository(),
        )

        val result = useCase.analyze("order1")

        assertEquals(1, result.items.size)
        assertNull(result.items[0].colorName)
    }

    @Test
    fun `analyze splits a bead across vendors when stock caps force it`() = runTest {
        val acPack = fmgPack.copy(
            id = 2, vendorKey = "ac", grams = 50.0, priceCents = 3000, stockQuantity = 1,
        )
        val fmgCapped = fmgPack.copy(
            id = 3, grams = 50.0, priceCents = 3500, stockQuantity = 2,
        )
        val catalog = catalogRepositoryWith(emptyMap())
        coEvery { catalog.packsForBead("DB0001") } returns listOf(acPack, fmgCapped)
        val preferences: PreferencesRepository = mockk {
            every { vendorPriorityOrder } returns flowOf(listOf("ac", "fmg"))
            every { buyUpEnabled } returns flowOf(false)
        }
        val item = unassignedPendingItem("DB0001").copy(targetGrams = 150.0)

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(item),
            catalogRepository = catalog,
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferences,
        )

        val result = useCase.analyze("order1")

        assertEquals(
            listOf("ac" to 1, "fmg" to 2),
            result.items.map { it.vendorKey to it.quantityUnits },
        )
    }

    @Test
    fun `replanBead keeps unchanged rows and re-finalizes changed ones without invoice`() = runTest {
        val acPack = fmgPack.copy(id = 2, vendorKey = "ac", grams = 50.0, priceCents = 3000, stockQuantity = 1)
        val fmgPack50 = fmgPack.copy(id = 3, grams = 50.0, priceCents = 3500, stockQuantity = 2)
        val catalog = catalogRepositoryWith(emptyMap())
        coEvery { catalog.packsForBead("DB0001") } returns listOf(acPack, fmgPack50)
        val preferences: PreferencesRepository = mockk {
            every { vendorPriorityOrder } returns flowOf(listOf("ac", "fmg"))
            every { buyUpEnabled } returns flowOf(false)
        }
        // Previously planned 3 x ac; Aura now reports 1 in stock.
        val ordered = OrderItemEntry(
            beadCode = "DB0001", vendorKey = "ac", targetGrams = 150.0, packGrams = 50.0,
            quantityUnits = 3, status = OrderItemStatus.ORDERED.firestoreValue, invoiceNumber = "INV-1",
        )
        val orders = orderRepositoryWith(ordered)
        val replaced = slot<List<OrderItemEntry>>()
        coEvery { orders.replaceItems("order1", any(), any(), capture(replaced)) } returns Unit

        FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orders,
            catalogRepository = catalog,
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferences,
        ).replanBead("order1", "DB0001")

        assertEquals(listOf("ac" to 1, "fmg" to 2), replaced.captured.map { it.vendorKey to it.quantityUnits })
        assertEquals(
            listOf(OrderItemStatus.FINALIZED.firestoreValue, OrderItemStatus.FINALIZED.firestoreValue),
            replaced.captured.map { it.status },
        )
        assertEquals(listOf(null, null), replaced.captured.map { it.invoiceNumber })
        assertEquals(listOf(150.0, 150.0), replaced.captured.map { it.targetGrams })
    }

    @Test(expected = UnavailablePacksException::class)
    fun `replanBead throws when the vendors cannot cover the bead`() = runTest {
        val acPack = fmgPack.copy(id = 2, vendorKey = "ac", grams = 50.0, priceCents = 3000, stockQuantity = 1)
        val catalog = catalogRepositoryWith(emptyMap())
        coEvery { catalog.packsForBead("DB0001") } returns listOf(acPack)
        val preferences: PreferencesRepository = mockk {
            every { vendorPriorityOrder } returns flowOf(listOf("ac"))
            every { buyUpEnabled } returns flowOf(false)
        }
        val finalized = OrderItemEntry(
            beadCode = "DB0001", vendorKey = "ac", targetGrams = 150.0, packGrams = 50.0,
            quantityUnits = 3, status = OrderItemStatus.FINALIZED.firestoreValue,
        )
        FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(finalized),
            catalogRepository = catalog,
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferences,
        ).replanBead("order1", "DB0001")
    }

    private fun useCaseWith(
        orders: OrderRepository,
        packs: List<VendorPackEntity>,
        priority: List<String>,
    ): FinalizeOrderUseCase {
        val catalog = catalogRepositoryWith(emptyMap())
        coEvery { catalog.packsForBead("DB0001") } returns packs
        coEvery { catalog.packByKey(any(), any(), any()) } answers {
            packs.firstOrNull {
                it.beadCode == firstArg<String>() && it.vendorKey == secondArg<String>() &&
                    it.grams == thirdArg<Double>()
            }
        }
        val preferences: PreferencesRepository = mockk {
            every { vendorPriorityOrder } returns flowOf(priority)
            every { buyUpEnabled } returns flowOf(false)
        }
        return FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orders,
            catalogRepository = catalog,
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferences,
        )
    }

    private fun assignedPending(pinned: Boolean) = OrderItemEntry(
        beadCode = "DB0001", vendorKey = "ac", targetGrams = 150.0, packGrams = 50.0,
        quantityUnits = 3, status = OrderItemStatus.PENDING.firestoreValue, pinned = pinned,
    )

    @Test
    fun `analyze falls back for an unpinned assigned row that exceeds reported stock`() = runTest {
        val acPack = fmgPack.copy(id = 2, vendorKey = "ac", grams = 50.0, priceCents = 3000, stockQuantity = 1)
        val fmgPack50 = fmgPack.copy(id = 3, grams = 50.0, priceCents = 3500)
        val result = useCaseWith(
            orderRepositoryWith(assignedPending(pinned = false)), listOf(acPack, fmgPack50), listOf("ac", "fmg"),
        ).analyze("order1")

        assertEquals(listOf("fmg" to 3), result.items.map { it.vendorKey to it.quantityUnits })
    }

    @Test
    fun `analyze keeps a pinned assigned row and flags it when stock is short`() = runTest {
        val acPack = fmgPack.copy(id = 2, vendorKey = "ac", grams = 50.0, priceCents = 3000, stockQuantity = 1)
        val fmgPack50 = fmgPack.copy(id = 3, grams = 50.0, priceCents = 3500)
        val result = useCaseWith(
            orderRepositoryWith(assignedPending(pinned = true)), listOf(acPack, fmgPack50), listOf("ac", "fmg"),
        ).analyze("order1")

        assertEquals(listOf("ac" to 3), result.items.map { it.vendorKey to it.quantityUnits })
        assertEquals(listOf(true), result.items.map { it.pinned })
        assertEquals(listOf(true), result.items.map { it.pinWarning })
    }

    @Test
    fun `savePin pins the rows and keeps invoice only on unchanged rows`() = runTest {
        val kept = OrderItemEntry(
            beadCode = "DB0001", vendorKey = "ac", targetGrams = 150.0, packGrams = 50.0,
            quantityUnits = 1, status = OrderItemStatus.ORDERED.firestoreValue, invoiceNumber = "INV-1",
        )
        val changed = kept.copy(packGrams = 7.5, quantityUnits = 2, invoiceNumber = "INV-1")
        val orders = orderRepositoryWith(kept, changed)
        val replacements = slot<List<OrderItemEntry>>()
        coEvery { orders.replaceItems("order1", any(), any(), capture(replacements)) } returns Unit

        useCaseWith(orders, listOf(fmgPack), listOf("fmg")).savePin(
            "order1", "DB0001",
            listOf(PinRow("ac", 50.0, 1), PinRow("ac", 7.5, 3), PinRow("fmg", 50.0, 1)),
        )

        val rows = replacements.captured
        assertEquals(listOf(true, true, true), rows.map { it.pinned })
        assertEquals(
            listOf(OrderItemStatus.ORDERED, OrderItemStatus.FINALIZED, OrderItemStatus.FINALIZED)
                .map { it.firestoreValue },
            rows.map { it.status },
        )
        assertEquals(listOf("INV-1", null, null), rows.map { it.invoiceNumber })
    }

    @Test
    fun `replanBead leaves pinned rows alone`() = runTest {
        val pinnedRow = OrderItemEntry(
            beadCode = "DB0001", vendorKey = "ac", targetGrams = 150.0, packGrams = 50.0,
            quantityUnits = 3, status = OrderItemStatus.FINALIZED.firestoreValue, pinned = true,
        )
        val orders = orderRepositoryWith(pinnedRow)

        useCaseWith(orders, listOf(fmgPack), listOf("fmg")).replanBead("order1", "DB0001")

        coVerify(exactly = 0) { orders.replaceItems(any(), any(), any(), any()) }
    }

    @Test
    fun `removePin hands the bead back to the optimizer with unpinned rows`() = runTest {
        val pinnedRow = OrderItemEntry(
            beadCode = "DB0001", vendorKey = "ac", targetGrams = 10.0, packGrams = 50.0,
            quantityUnits = 3, status = OrderItemStatus.FINALIZED.firestoreValue, pinned = true,
        )
        val orders = orderRepositoryWith(pinnedRow)
        val replacements = slot<List<OrderItemEntry>>()
        coEvery { orders.replaceItems("order1", any(), any(), capture(replacements)) } returns Unit

        useCaseWith(orders, listOf(fmgPack), listOf("fmg")).removePin("order1", "DB0001")

        assertEquals(listOf("fmg" to 1), replacements.captured.map { it.vendorKey to it.quantityUnits })
        assertEquals(listOf(false), replacements.captured.map { it.pinned })
    }

    @Test
    fun `checkLowStock returns empty list when inventory is empty`() = runTest {
        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepository(),
            preferencesRepository = preferencesRepository(),
        )

        val result = useCase.checkLowStock()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `checkLowStock includes items at or below global threshold`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 5.0,
                lowStockThresholdGrams = 0.0,
            ),
            "DB0002" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0002",
                quantityGrams = 10.0,
                lowStockThresholdGrams = 0.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val globalThreshold = 8.0
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(globalThreshold)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(listOf("DB0001"), result)
    }

    @Test
    fun `checkLowStock respects per-bead threshold override`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 5.0,
                lowStockThresholdGrams = 3.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(10.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(listOf("DB0001"), result)
    }

    @Test
    fun `checkLowStock excludes items below noise threshold`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = SUFFICIENT_THRESHOLD_GRAMS / 2.0,
                lowStockThresholdGrams = 0.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(10.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `checkLowStock excludes items at or below noise threshold boundary`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = SUFFICIENT_THRESHOLD_GRAMS,
                lowStockThresholdGrams = 0.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(10.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `checkLowStock respects per-bead override below global threshold`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 7.0,
                lowStockThresholdGrams = 5.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(10.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `checkLowStock includes items below per-bead threshold even when above global threshold`() = runTest {
        val inventory = mapOf(
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 7.0,
                lowStockThresholdGrams = 10.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(5.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(listOf("DB0001"), result)
    }

    @Test
    fun `checkLowStock returns sorted list when multiple items low`() = runTest {
        val inventory = mapOf(
            "DB0003" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0003",
                quantityGrams = 2.0,
                lowStockThresholdGrams = 0.0,
            ),
            "DB0001" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0001",
                quantityGrams = 3.0,
                lowStockThresholdGrams = 0.0,
            ),
            "DB0002" to com.techyshishy.beadmanager.data.firestore.InventoryEntry(
                beadCode = "DB0002",
                quantityGrams = 1.0,
                lowStockThresholdGrams = 0.0,
            ),
        )
        val inventoryRepo = mockk<InventoryRepository> {
            every { inventoryStream() } returns flowOf(inventory)
        }
        val prefsRepo = mockk<PreferencesRepository> {
            every { globalLowStockThreshold } returns flowOf(10.0)
            every { vendorPriorityOrder } returns flowOf(emptyList())
            every { buyUpEnabled } returns flowOf(false)
        }

        val useCase = FinalizeOrderUseCase(
            context = mockConnectedContext(),
            orderRepository = orderRepositoryWith(),
            catalogRepository = catalogRepositoryWith(emptyMap()),
            inventoryRepository = inventoryRepo,
            preferencesRepository = prefsRepo,
        )

        val result = useCase.checkLowStock()

        assertEquals(listOf("DB0001", "DB0002", "DB0003"), result)
    }
}
