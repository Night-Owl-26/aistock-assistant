package com.aistock.assistant.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PinHasher
import com.aistock.assistant.data.Prefs
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.database.BusinessEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

val CURRENCIES = listOf(
    "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "AED" to "AED ", "SAR" to "SAR ", "LKR" to "Rs ", "MYR" to "RM ", "SGD" to "S$",
)

/** App-wide state: preferences, business profile, and whether the PIN lock is open. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val business: BusinessRepository,
) : ViewModel() {
    val prefs: StateFlow<Prefs> = settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs())
    val businessProfile: StateFlow<BusinessEntity?> = business.observeBusiness().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null = still loading, true = a PIN is set */
    val hasPin: StateFlow<Boolean?> = business.observeUser().map<_, Boolean?> { it != null && it.pinHash.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ownerName: StateFlow<String> = business.observeUser().map { it?.name.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    suspend fun unlock(pin: String): Boolean {
        val ok = business.checkPin(pin)
        if (ok) _unlocked.value = true
        return ok
    }

    /** @return false when the PIN is not 4 to 8 digits */
    suspend fun finishOnboarding(name: String, owner: String, currencyCode: String, language: String, pin: String, sample: Boolean): Boolean {
        if (pin.isNotEmpty() && !PinHasher.isValidPin(pin)) return false
        val symbol = CURRENCIES.firstOrNull { it.first == currencyCode }?.second ?: "₹"
        business.saveBusiness(name, owner, currencyCode, symbol, language)
        if (pin.isNotEmpty()) business.setPin(pin)
        if (sample) business.loadSampleData()
        _unlocked.value = true
        settings.setOnboardingDone(true)
        return true
    }

    suspend fun saveBusiness(name: String, owner: String, currencyCode: String) {
        val symbol = CURRENCIES.firstOrNull { it.first == currencyCode }?.second ?: "₹"
        business.saveBusiness(name, owner, currencyCode, symbol, prefs.value.language)
    }

    suspend fun setLanguage(language: String) {
        val b = business.business()
        if (b != null) business.saveBusiness(b.name, b.ownerName, b.currency, b.currencySymbol, language) else settings.setLanguage(language)
    }

    suspend fun setTheme(theme: String) = settings.setTheme(theme)
    suspend fun setTts(on: Boolean) = settings.setTts(on)
    suspend fun setAllowNegative(on: Boolean) = settings.setAllowNegative(on)
    suspend fun setLowStockAlerts(on: Boolean) = settings.setLowStockAlerts(on)
    suspend fun setAiMode(mode: String) = settings.setAiMode(mode)
    suspend fun saveCloud(endpoint: String, model: String, key: String): Boolean = settings.setCloud(endpoint, model, key)
    suspend fun clearCloudKey() = settings.clearCloudKey()

    /** @return false when the PIN is not valid */
    suspend fun setPin(pin: String): Boolean {
        if (pin.isNotEmpty() && !PinHasher.isValidPin(pin)) return false
        business.setPin(pin)
        _unlocked.value = true
        return true
    }

    suspend fun checkPin(pin: String): Boolean = business.checkPin(pin)
    suspend fun loadSample(): Op<Unit> = business.loadSampleData()

    suspend fun resetAll() {
        business.resetAll()
        _unlocked.value = false
    }
}
