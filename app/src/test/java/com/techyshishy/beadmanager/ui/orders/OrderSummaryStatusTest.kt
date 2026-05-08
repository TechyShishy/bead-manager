package com.techyshishy.beadmanager.ui.orders

import com.techyshishy.beadmanager.data.firestore.OrderItemEntry
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderSummaryStatusTest {

    private fun item(status: OrderItemStatus) =
        OrderItemEntry(status = status.firestoreValue)

    @Test
    fun emptyItemsIsPending() {
        assertEquals(OrderSummaryStatus.PENDING, deriveOrderSummaryStatus(emptyList()))
    }

    @Test
    fun allPendingIsPending() {
        val items = listOf(item(OrderItemStatus.PENDING), item(OrderItemStatus.PENDING))
        assertEquals(OrderSummaryStatus.PENDING, deriveOrderSummaryStatus(items))
    }

    @Test
    fun singlePendingIsPending() {
        assertEquals(OrderSummaryStatus.PENDING, deriveOrderSummaryStatus(listOf(item(OrderItemStatus.PENDING))))
    }

    @Test
    fun anyFinalizedWithNoneOrderedIsFinalized() {
        val items = listOf(item(OrderItemStatus.PENDING), item(OrderItemStatus.FINALIZED))
        assertEquals(OrderSummaryStatus.FINALIZED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun allFinalizedIsFinalized() {
        val items = listOf(item(OrderItemStatus.FINALIZED), item(OrderItemStatus.FINALIZED))
        assertEquals(OrderSummaryStatus.FINALIZED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun anyOrderedIsOrdered() {
        val items = listOf(item(OrderItemStatus.FINALIZED), item(OrderItemStatus.ORDERED))
        assertEquals(OrderSummaryStatus.ORDERED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun anyOrderedWithPendingIsStillOrdered() {
        val items = listOf(item(OrderItemStatus.PENDING), item(OrderItemStatus.ORDERED))
        assertEquals(OrderSummaryStatus.ORDERED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun allReceivedIsReceived() {
        val items = listOf(item(OrderItemStatus.RECEIVED), item(OrderItemStatus.RECEIVED))
        assertEquals(OrderSummaryStatus.RECEIVED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun allSkippedIsReceived() {
        val items = listOf(item(OrderItemStatus.SKIPPED), item(OrderItemStatus.SKIPPED))
        assertEquals(OrderSummaryStatus.RECEIVED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun receivedAndSkippedIsReceived() {
        val items = listOf(item(OrderItemStatus.RECEIVED), item(OrderItemStatus.SKIPPED))
        assertEquals(OrderSummaryStatus.RECEIVED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun receivedWithRemainingOrderedIsOrdered() {
        val items = listOf(item(OrderItemStatus.RECEIVED), item(OrderItemStatus.ORDERED))
        assertEquals(OrderSummaryStatus.ORDERED, deriveOrderSummaryStatus(items))
    }

    @Test
    fun receivedWithRemainingPendingIsPending() {
        val items = listOf(item(OrderItemStatus.RECEIVED), item(OrderItemStatus.PENDING))
        assertEquals(OrderSummaryStatus.PENDING, deriveOrderSummaryStatus(items))
    }

    @Test
    fun skippedWithPendingIsPending() {
        val items = listOf(item(OrderItemStatus.SKIPPED), item(OrderItemStatus.PENDING))
        assertEquals(OrderSummaryStatus.PENDING, deriveOrderSummaryStatus(items))
    }

    @Test
    fun receivedWithRemainingFinalizedIsFinalized() {
        val items = listOf(item(OrderItemStatus.RECEIVED), item(OrderItemStatus.FINALIZED))
        assertEquals(OrderSummaryStatus.FINALIZED, deriveOrderSummaryStatus(items))
    }
}
