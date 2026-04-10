package com.techyshishy.beadmanager.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.scraper.NoConnectivityException
import com.techyshishy.beadmanager.data.scraper.ScrapingFailedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FinalizeOrderViewModel @Inject constructor(
    private val useCase: FinalizeOrderUseCase,
) : ViewModel() {

    sealed interface UiState {
        data object Checking : UiState
        data class Success(val items: List<FinalizedItem>) : UiState
        data class UnavailableError(val beadCodes: List<String>) : UiState
        data object ConnectivityError : UiState
        data object ScrapingError : UiState
        data object UnknownError : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Checking)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun initiate(orderId: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Checking
            _uiState.value = runCatching { useCase.execute(orderId) }.fold(
                onSuccess = { UiState.Success(it.items) },
                onFailure = { e ->
                    when (e) {
                        is UnavailablePacksException -> UiState.UnavailableError(e.beadCodes)
                        is NoConnectivityException -> UiState.ConnectivityError
                        is ScrapingFailedException -> UiState.ScrapingError
                        else -> UiState.UnknownError
                    }
                },
            )
        }
    }
}
