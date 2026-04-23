package com.techyshishy.beadmanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techyshishy.beadmanager.data.repository.PreferencesRepository
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_BUY_UP_ENABLED
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_TRAY_CARD_MAX_GRAMS
import com.techyshishy.beadmanager.data.repository.PreferencesRepository.Companion.DEFAULT_VENDOR_PRIORITY_ORDER
import com.techyshishy.beadmanager.data.seed.CatalogSeeder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val globalLowStockThreshold: StateFlow<Double> =
        preferencesRepository.globalLowStockThreshold
            .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_GLOBAL_LOW_STOCK_THRESHOLD)

    val vendorPriorityOrder: StateFlow<List<Pair<String, String>>> =
        preferencesRepository.vendorPriorityOrder
            .map { keys -> keys.map { k -> k to (CatalogSeeder.VENDOR_DISPLAY_NAMES[k] ?: k) } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DEFAULT_VENDOR_PRIORITY_ORDER.map { k -> k to (CatalogSeeder.VENDOR_DISPLAY_NAMES[k] ?: k) },
            )

    val buyUpEnabled: StateFlow<Boolean> =
        preferencesRepository.buyUpEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_BUY_UP_ENABLED)

    val trayCardMaxGrams: StateFlow<Double> =
        preferencesRepository.trayCardMaxGrams
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_TRAY_CARD_MAX_GRAMS)

    fun setGlobalLowStockThreshold(grams: Double) {
        viewModelScope.launch {
            preferencesRepository.setGlobalLowStockThreshold(grams)
        }
    }

    fun setVendorPriorityOrder(order: List<String>) {
        viewModelScope.launch {
            preferencesRepository.setVendorPriorityOrder(order)
        }
    }

    fun setBuyUpEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBuyUpEnabled(enabled)
        }
    }

    fun setTrayCardMaxGrams(grams: Double) {
        viewModelScope.launch {
            preferencesRepository.setTrayCardMaxGrams(grams)
        }
    }
}
