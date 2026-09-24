package com.chmouel.liseur.ui.apa

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.apa.ApaDevice
import com.chmouel.liseur.data.apa.ApaDiscoverClient
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** null while loading; [failed] when the list could not be fetched. */
data class DevicesState(val devices: List<ApaDevice>? = null, val failed: Boolean = false)

class DevicesViewModel(private val client: ApaDiscoverClient) : ViewModel() {
    private val _state = MutableStateFlow(DevicesState())
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        _state.value = client.devices().fold(
            onSuccess = { DevicesState(devices = it) },
            onFailure = { DevicesState(failed = true) },
        )
    }

    fun signOut(device: ApaDevice) = viewModelScope.launch {
        client.revokeDevice(device.id).onSuccess { load() }.onFailure { _state.value = DevicesState(failed = true) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(checkNotNull(this[APPLICATION_KEY]).container.apaDiscover) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApaDevicesScreen(onBack: () -> Unit, viewModel: DevicesViewModel = viewModel(factory = DevicesViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<ApaDevice?>(null) }
    val unnamed = stringResource(R.string.apa_devices_unnamed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apa_devices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val devices = state.devices
        when {
            state.failed -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(stringResource(R.string.apa_devices_failed), Modifier.padding(24.dp))
            }

            devices == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(devices, key = ApaDevice::id) { device ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                        headlineContent = { Text(device.name ?: unnamed) },
                        supportingContent = {
                            Text(
                                when {
                                    device.current -> stringResource(R.string.apa_devices_this)
                                    device.lastSeen == null -> stringResource(R.string.apa_devices_never)
                                    else -> stringResource(R.string.apa_devices_last_seen, relative(device.lastSeen))
                                },
                            )
                        },
                        // This device signs out from the account screen, where
                        // its local copy of the library is cleaned up too.
                        trailingContent = if (device.current) null else {
                            {
                                TextButton(onClick = { confirming = device }) {
                                    Text(stringResource(R.string.apa_devices_sign_out))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    confirming?.let { device ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { Text(stringResource(R.string.apa_devices_confirm, device.name ?: unnamed)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.signOut(device)
                    confirming = null
                }) { Text(stringResource(R.string.apa_devices_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun relative(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(iso).toInstant().toEpochMilli()).toString()
}.getOrDefault(iso)
