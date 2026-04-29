package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.shop.TrafyProduct
import com.example.trafykamerasikotlin.data.shop.TrafyShopRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ShopUiState {
    data object Loading : ShopUiState()
    data class Loaded(val products: List<TrafyProduct>) : ShopUiState()
    data object Error : ShopUiState()
}

class ShopViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrafyShopRepository(application)

    private val _state = MutableStateFlow<ShopUiState>(ShopUiState.Loading)
    val state: StateFlow<ShopUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.value = ShopUiState.Loading
        viewModelScope.launch {
            try {
                val products = repo.fetchProducts()
                _state.value = if (products.isEmpty()) ShopUiState.Error
                                else ShopUiState.Loaded(products)
            } catch (e: Exception) {
                Log.w(TAG, "reload failed: ${e.message}")
                _state.value = ShopUiState.Error
            }
        }
    }

    private companion object {
        const val TAG = "Trafy.ShopVM"
    }
}
