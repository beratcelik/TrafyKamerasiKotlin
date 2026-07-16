package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.R
import com.example.trafykamerasikotlin.data.report.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ReportUiState {
    data object Idle : ReportUiState()
    data object Sending : ReportUiState()
    data object Sent : ReportUiState()
    /** Saved to the disk queue — delivered automatically once internet is reachable. */
    data object Queued : ReportUiState()
    data object Error : ReportUiState()
}

/** Feedback categories the user can pick — [apiValue] matches the server enum. */
enum class FeedbackType(val apiValue: String, @StringRes val labelRes: Int) {
    Bug("bug", R.string.report_type_bug),
    Improvement("improvement", R.string.report_type_improvement),
    Suggestion("suggestion", R.string.report_type_suggestion),
    Other("other", R.string.report_type_other),
}

class ReportViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.ReportVM"
    }

    private val repo = ReportRepository(application)

    private val _state = MutableStateFlow<ReportUiState>(ReportUiState.Idle)
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    /**
     * Uploads whatever the previous session left in the disk queue (crash
     * files, undelivered feedback). Called from the startup LaunchedEffect;
     * cheap no-op when the queue is empty and debounced inside the repo.
     */
    fun flushOnStartup() {
        viewModelScope.launch { repo.flushPending() }
    }

    fun submit(type: FeedbackType, description: String, contactEmail: String, includeDiagnostics: Boolean) {
        if (description.isBlank()) return
        if (_state.value is ReportUiState.Sending) return
        viewModelScope.launch {
            _state.value = ReportUiState.Sending
            _state.value = when (
                repo.submitFeedback(
                    reportType         = type.apiValue,
                    description        = description.trim(),
                    contactEmail       = contactEmail.trim().takeIf { it.isNotBlank() },
                    includeDiagnostics = includeDiagnostics,
                )
            ) {
                ReportRepository.SubmitResult.Sent   -> ReportUiState.Sent
                ReportRepository.SubmitResult.Queued -> ReportUiState.Queued
                ReportRepository.SubmitResult.Error  -> ReportUiState.Error
            }
        }
    }

    /** Clears the result state (dialog dismissed / screen left). */
    fun reset() {
        _state.value = ReportUiState.Idle
    }
}