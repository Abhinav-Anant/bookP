package com.chmouel.liseur.ui.apa

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.apa.ApaAuthClient
import com.chmouel.liseur.data.apa.ApaConfig
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val email: String = "",
    val code: String = "",
    /** Set once a code has been sent to [email]. */
    val codeSent: Boolean = false,
    val working: Boolean = false,
    /** A message from the server, or null to show the generic one. */
    val error: String? = null,
    val failed: Boolean = false,
)

/**
 * Email-OTP and Google sign-in for the APA cloud.
 *
 * A successful sign-in mints a device key and connects the library to
 * the APA API as a Komga server, so everything after this point — the
 * catalog, downloads, position sync — is the code Liseur already has.
 */
class SignInViewModel(
    private val auth: ApaAuthClient,
    private val accounts: RemoteAccountRepository,
    private val catalog: RemoteCatalogRepository,
    private val positionSync: PositionSyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    fun setEmail(value: String) = _state.update { it.copy(email = value, failed = false, error = null) }

    fun setCode(value: String) =
        _state.update { it.copy(code = value.filter(Char::isDigit).take(CODE_LENGTH), failed = false, error = null) }

    fun changeEmail() = _state.update { it.copy(codeSent = false, code = "", failed = false, error = null) }

    val emailValid: Boolean get() = EMAIL.matches(_state.value.email.trim())

    fun sendCode() = work {
        auth.sendEmailOtp(_state.value.email).map { _state.update { it.copy(codeSent = true) } }
    }

    fun verify() = work {
        val current = _state.value
        auth.verifyEmailOtp(current.email, current.code).mapCatching { connect(it) }
    }

    fun google(idToken: String) = work {
        auth.signInWithGoogle(idToken).mapCatching { connect(it) }
    }

    /** Called when Credential Manager itself fails (cancelled, no account). */
    fun googleUnavailable(message: String?) =
        _state.update { it.copy(working = false, failed = true, error = message) }

    private suspend fun connect(accessToken: String) {
        val key = auth.mintDeviceKey(accessToken, deviceName()).getOrThrow()
        when (val result = accounts.connectKomga(url = ApaConfig.apiBaseUrl, apiKey = key)) {
            is SetupResult.Success -> catalog.refreshDetached { refreshed ->
                if (!refreshed.completed) return@refreshDetached
                positionSync.request(SyncScope.Full, System.currentTimeMillis(), refreshed.forSync())
            }
            is SetupResult.Failure -> throw IllegalStateException("APA cloud refused the device: ${result.reason}")
        }
    }

    private fun work(block: suspend () -> Result<*>) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, failed = false, error = null) }
        viewModelScope.launch {
            val result = block()
            _state.update {
                it.copy(
                    working = false,
                    failed = result.isFailure,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .ifBlank { "Android" }

    companion object {
        const val CODE_LENGTH = 10
        const val MIN_CODE_LENGTH = 6
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                SignInViewModel(
                    auth = container.apaAuth,
                    accounts = container.remoteAccount,
                    catalog = container.remoteCatalog,
                    positionSync = container.positionSync,
                )
            }
        }
    }
}
