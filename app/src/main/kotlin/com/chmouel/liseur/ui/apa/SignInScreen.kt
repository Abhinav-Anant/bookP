package com.chmouel.liseur.ui.apa

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.apa.ApaConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

@Composable
fun ApaSignInScreen(viewModel: SignInViewModel, onSkip: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "APA",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.apa_signin_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.apa_signin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.codeSent) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::setEmail,
                    label = { Text(stringResource(R.string.apa_signin_email)) },
                    singleLine = true,
                    enabled = !state.working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::sendCode,
                    enabled = !state.working && viewModel.emailValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonLabel(state.working, stringResource(R.string.apa_signin_send_code)) }
            } else {
                Text(stringResource(R.string.apa_signin_code_sent, state.email))
                OutlinedTextField(
                    value = state.code,
                    onValueChange = viewModel::setCode,
                    label = { Text(stringResource(R.string.apa_signin_code)) },
                    singleLine = true,
                    enabled = !state.working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::verify,
                    enabled = !state.working && state.code.length >= SignInViewModel.MIN_CODE_LENGTH,
                    modifier = Modifier.fillMaxWidth(),
                ) { ButtonLabel(state.working, stringResource(R.string.apa_signin_verify)) }
                TextButton(onClick = viewModel::changeEmail, enabled = !state.working) {
                    Text(stringResource(R.string.apa_signin_change_email))
                }
            }

            if (state.failed) {
                Text(
                    text = state.error ?: stringResource(R.string.apa_signin_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (ApaConfig.googleWebClientId.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.apa_signin_or),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            googleIdToken(context)
                                .onSuccess(viewModel::google)
                                .onFailure { viewModel.googleUnavailable(it.message) }
                        }
                    },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.apa_signin_google)) }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkip, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.apa_signin_skip))
            }
        }
    }
}

@Composable
private fun ButtonLabel(working: Boolean, label: String) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        Text(label)
    }
}

/** Asks Credential Manager for a Google ID token for the APA web client. */
private suspend fun googleIdToken(context: Context): Result<String> = runCatching {
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(ApaConfig.googleWebClientId)
        .setFilterByAuthorizedAccounts(false)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = try {
        CredentialManager.create(context).getCredential(context, request).credential
    } catch (e: NoCredentialException) {
        throw IllegalStateException("No Google account on this device", e)
    } catch (e: GetCredentialException) {
        throw IllegalStateException(e.errorMessage?.toString() ?: e.type, e)
    }
    GoogleIdTokenCredential.createFrom(credential.data).idToken
}
