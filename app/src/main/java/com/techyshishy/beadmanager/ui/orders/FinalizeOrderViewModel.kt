package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.firestore.OrderItemStatus
import com.techyshishy.beadmanager.data.repository.OrderRepository
import com.techyshishy.beadmanager.data.scraper.NoConnectivityException
import com.techyshishy.beadmanager.data.scraper.ScrapingFailedException
import com.techyshishy.beadmanager.domain.FinalizeOrderUseCase
import com.techyshishy.beadmanager.domain.FinalizedItem
import com.techyshishy.beadmanager.domain.InvalidBuyUpBeadException
import com.techyshishy.beadmanager.domain.OrderAnalysis
import com.techyshishy.beadmanager.domain.UnavailablePacksException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FinalizeOrderViewModel @Inject constructor(
    private val useCase: FinalizeOrderUseCase,
    private val orderRepository: OrderRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Checking : UiState
        data class FinalizedView(val items: List<FinalizedItem>) : UiState
        /** Scrape and selection done; user decides whether to add buy-up items. */
        data class BuyUpPrompt(val analysis: OrderAnalysis, val invalidBeadCode: String? = null) : UiState
        data class UnavailableError(val beadCodes: List<String>) : UiState
        data object ConnectivityError : UiState
        data object ScrapingError : UiState
        data object UnknownError : UiState
        data object AllOrdered : UiState
        data object Reopened : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Checking)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentOrderId: String = ""

    /**
     * Initiates the finalize flow for [orderId].
     *
     * If the order already has FINALIZED items (the user is returning after navigating away),
     * the stored vendor assignments are resolved from Room without re-running the price check.
     * Otherwise, runs [FinalizeOrderUseCase.analyze]. When the result contains a buy-up
     * suggestion, emits [UiState.BuyUpPrompt] and waits for [acceptBuyUp] or [skipBuyUp].
     * When there is no suggestion, calls [FinalizeOrderUseCase.commit] immediately and emits
     * [UiState.FinalizedView].
     */
    fun initiate(orderId: String) {
        currentOrderId = orderId
        viewModelScope.launch {
            _uiState.value = UiState.Checking
            val order = orderRepository.orderStream(orderId).first()
            if (order == null) {
                _uiState.value = UiState.UnknownError
                return@launch
            }
            val hasFinalizedItems = order.items.any {
                OrderItemStatus.fromFirestore(it.status) == OrderItemStatus.FINALIZED
            }
            if (hasFinalizedItems) {
                _uiState.value = runCatching { useCase.resolveExistingOrder(orderId) }.fold(
                    onSuccess = { UiState.FinalizedView(it.items) },
                    onFailure = { UiState.UnknownError },
                )
                return@launch
            }

            val analysis = runCatching { useCase.analyze(orderId) }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    _uiState.value = when (e) {
                        is UnavailablePacksException -> UiState.UnavailableError(e.beadCodes)
                        is NoConnectivityException -> UiState.ConnectivityError
                        is ScrapingFailedException -> UiState.ScrapingError
                        else -> UiState.UnknownError
                    }
                    return@launch
                },
            )

            if (analysis.buyUpSuggestion != null) {
                _uiState.value = UiState.BuyUpPrompt(analysis)
            } else {
                commitAnalysis(analysis, buyUpBeadCode = null)
            }
        }
    }

    /**
     * Accepts the buy-up suggestion and finalizes with an extra unit of [beadCode].
     * No-op if not currently in [UiState.BuyUpPrompt].
     */
    fun acceptBuyUp(beadCode: String) {
        val analysis = (_uiState.value as? UiState.BuyUpPrompt)?.analysis ?: return
        viewModelScope.launch { commitAnalysis(analysis, buyUpBeadCode = beadCode) }
    }

    /**
     * Skips the buy-up suggestion and finalizes without extra items.
     * No-op if not currently in [UiState.BuyUpPrompt].
     */
    fun skipBuyUp() {
        val analysis = (_uiState.value as? UiState.BuyUpPrompt)?.analysis ?: return
        viewModelScope.launch { commitAnalysis(analysis, buyUpBeadCode = null) }
    }

    private suspend fun commitAnalysis(analysis: OrderAnalysis, buyUpBeadCode: String?) {
        _uiState.value = UiState.Checking
        _uiState.value = runCatching { useCase.commit(analysis, buyUpBeadCode) }.fold(
            onSuccess = { UiState.FinalizedView(it.items) },
            onFailure = { e ->
                when (e) {
                    // Re-show the prompt so the user can choose a different bead.
                    is InvalidBuyUpBeadException -> UiState.BuyUpPrompt(analysis, invalidBeadCode = e.beadCode)
                    else -> UiState.UnknownError
                }
            },
        )
    }

    /**
     * Marks all FINALIZED items for [vendorKey] as ORDERED and updates the displayed state.
     *
     * If no FINALIZED items remain after the transition, emits [UiState.AllOrdered] to trigger
     * navigation back. Otherwise, the state reflects the remaining vendors still to be ordered.
     */
    fun markVendorOrdered(vendorKey: String, invoiceNumber: String? = null) {
        val orderId = currentOrderId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val order = orderRepository.orderStream(orderId).first() ?: return@launch
            orderRepository.markVendorItemsOrdered(orderId, vendorKey, order.items, invoiceNumber)
            val currentState = _uiState.value
            if (currentState is UiState.FinalizedView) {
                val remaining = currentState.items.filter { it.vendorKey != vendorKey }
                _uiState.value = if (remaining.isEmpty()) UiState.AllOrdered
                                 else UiState.FinalizedView(remaining)
            }
        }
    }

    /**
     * Reverts all FINALIZED items to PENDING with vendor assignments cleared, then emits
     * [UiState.Reopened] to trigger navigation back to the order detail screen.
     */
    fun reopen() {
        val orderId = currentOrderId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val order = orderRepository.orderStream(orderId).first() ?: return@launch
            orderRepository.reopenOrder(orderId, order.items)
            _uiState.value = UiState.Reopened
        }
    }
}
