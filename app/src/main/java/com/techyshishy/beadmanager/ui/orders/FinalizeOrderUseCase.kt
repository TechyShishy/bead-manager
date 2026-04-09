package com.techyshishy.beadmanager.ui.orders

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.techyshishy.beadmanager.data.db.VendorPackEntity
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.repository.CatalogRepository
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.scraper.NoConnectivityException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when one or more PENDING packs are confirmed out of stock. Blocks finalization. */
class UnavailablePacksException(val packs: List<VendorPackEntity>) :
    Exception("${packs.size} pack(s) out of stock — finalization blocked")

/**
 * A single finalized item: one PENDING order line that has been price-checked and is
 * ready to be placed with the vendor.
 *
 * [fetchFailed] is true when the live check threw an exception for this SKU; the
 * [priceCents] and [available] values are from the last successful check (or the
 * seed baseline) rather than a fresh reading. The item is still included in the
 * finalized result and the order is still transitioned to ORDERED.
 */
data class FinalizedItem(
    val beadCode: String,
    val vendorKey: String,
    val packGrams: Double,
    val quantityUnits: Int,
    val url: String,
    val priceCents: Int?,
    val available: Boolean?,
    val fetchFailed: Boolean,
) {
    val packGramsLabel: String
        get() = BigDecimal.valueOf(packGrams).stripTrailingZeros().toPlainString()
}

data class FinalizeResult(val items: List<FinalizedItem>)

/**
 * Orchestrates the "finalize order" workflow:
 *
 * 1. Asserts internet connectivity.
 * 2. Resolves all PENDING, vendor-assigned order items.
 * 3. Live-checks price and availability for each (skipping items whose cached data is
 *    fresh and unsupported vendors).
 * 4. Throws [UnavailablePacksException] if any pack is confirmed out of stock.
 * 5. Transitions all PENDING items to ORDERED in a single Firestore batch.
 * 6. Returns the finalized item list for display.
 *
 * A [Mutex] prevents simultaneous finalize calls for the same order; the ViewModel
 * guards against re-entry at the UI level, but the mutex provides a hard guarantee.
 */
@Singleton
class FinalizeOrderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: OrderRepository,
    private val catalogRepository: CatalogRepository,
) {
    private val mutex = Mutex()

    suspend fun execute(orderId: String): FinalizeResult = mutex.withLock {
        assertConnectivity()

        val order = orderRepository.orderStream(orderId).first()
            ?: error("Order $orderId not found")

        val pendingItems = order.items.filter { item ->
            OrderItemStatus.fromFirestore(item.status) == OrderItemStatus.PENDING &&
                item.vendorKey.isNotBlank()
        }

        if (pendingItems.isEmpty()) {
            orderRepository.finalizeOrder(orderId, order.items)
            return@withLock FinalizeResult(items = emptyList())
        }

        // Resolve Room entities for each PENDING item.
        val resolvedPacks = pendingItems.mapNotNull { item ->
            catalogRepository.packByKey(item.beadCode, item.vendorKey, item.packGrams)
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val checkResult = catalogRepository.checkAndUpdatePacks(resolvedPacks, nowSeconds)

        // Re-read fresh Room state for every pack (captures updates from this check
        // as well as data from packs that were already fresh).
        val freshPacks = resolvedPacks.map { pack ->
            catalogRepository.packByKey(pack.beadCode, pack.vendorKey, pack.grams) ?: pack
        }

        val unavailable = freshPacks.filter { it.available == false }
        if (unavailable.isNotEmpty()) throw UnavailablePacksException(unavailable)

        orderRepository.finalizeOrder(orderId, order.items)

        val packsByKey = freshPacks.associateBy { Triple(it.beadCode, it.vendorKey, it.grams) }
        val finalizedItems = pendingItems.map { item ->
            val key = Triple(item.beadCode, item.vendorKey, item.packGrams)
            val pack = packsByKey[key]
            FinalizedItem(
                beadCode = item.beadCode,
                vendorKey = item.vendorKey,
                packGrams = item.packGrams,
                quantityUnits = item.quantityUnits,
                url = pack?.url ?: "",
                priceCents = pack?.priceCents,
                available = pack?.available,
                fetchFailed = pack?.id?.let { it in checkResult.failedPackIds } ?: false,
            )
        }

        FinalizeResult(items = finalizedItems)
    }

    private fun assertConnectivity() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            throw NoConnectivityException()
        }
    }
}
