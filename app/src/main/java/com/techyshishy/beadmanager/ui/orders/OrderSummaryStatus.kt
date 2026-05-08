package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus

/**
 * Aggregate status for an entire order, derived from its item statuses.
 *
 * Maps to one of the four main progression stages: pending build-out, fully
 * finalized but not yet placed, at least one item placed with a vendor, or
 * all items resolved (received or skipped).
 */
enum class OrderSummaryStatus {
    /** No items, or all items are still PENDING. */
    PENDING,

    /** At least one item is FINALIZED; none have been ORDERED yet. */
    FINALIZED,

    /** At least one item is ORDERED; none have been RECEIVED yet. */
    ORDERED,

    /** All items are RECEIVED or SKIPPED (order is complete). */
    RECEIVED,
}

/**
 * Derives the aggregate [OrderSummaryStatus] from a list of order items.
 *
 * SKIPPED items are treated as resolved: they do not block the order from
 * reaching RECEIVED status, and they do not inflate the pending count.
 *
 * When [items] is empty, returns [OrderSummaryStatus.PENDING].
 */
fun deriveOrderSummaryStatus(items: List<OrderItemEntry>): OrderSummaryStatus {
    if (items.isEmpty()) return OrderSummaryStatus.PENDING

    val statuses = items.map { OrderItemStatus.fromFirestore(it.status) }

    return when {
        statuses.all { it == OrderItemStatus.RECEIVED || it == OrderItemStatus.SKIPPED } ->
            OrderSummaryStatus.RECEIVED

        statuses.any { it == OrderItemStatus.ORDERED } ->
            OrderSummaryStatus.ORDERED

        statuses.any { it == OrderItemStatus.FINALIZED } ->
            OrderSummaryStatus.FINALIZED

        else ->
            OrderSummaryStatus.PENDING
    }
}
