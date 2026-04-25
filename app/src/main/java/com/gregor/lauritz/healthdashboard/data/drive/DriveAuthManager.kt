package com.gregor.lauritz.healthdashboard.data.drive

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveAuthState {
    data object SignedOut : DriveAuthState
    data class SignedIn(val email: String) : DriveAuthState
}

private const val OAUTH_SERVER_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com" // TODO
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

@Singleton
class DriveAuthManager
    @Inject
    constructor(
        private val prefsRepo: UserPreferencesRepository,
        private val backupPrefsRepo: BackupPreferencesRepository,
    ) {
        fun observeAuthState(): Flow<DriveAuthState> =
            prefsRepo.userPreferences.map { prefs ->
                val email = prefs.driveAccountEmail
                if (email != null) DriveAuthState.SignedIn(email) else DriveAuthState.SignedOut
            }

        suspend fun signIn(activityContext: Context): Result<DriveAuthState.SignedIn> =
            withContext(Dispatchers.Main) {
                runCatching {
                    val credentialManager = CredentialManager.create(activityContext)
                    val googleIdOption =
                        GetGoogleIdOption
                            .Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(OAUTH_SERVER_CLIENT_ID)
                            .build()
                    val request =
                        GetCredentialRequest
                            .Builder()
                            .addCredentialOption(googleIdOption)
                            .build()

                    val result =
                        try {
                            credentialManager.getCredential(activityContext, request)
                        } catch (e: GetCredentialException) {
                            throw e
                        }

                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(result.credential.data)
                    val email =
                        googleIdTokenCredential.id.ifBlank {
                            throw IllegalStateException("No email returned from Google Sign-In")
                        }

                    backupPrefsRepo.updateDriveAccountEmail(email)
                    DriveAuthState.SignedIn(email)
                }
            }

        // Returns a short-lived access token for Drive REST API calls.
        // Play Services refreshes the token automatically if expired.
        suspend fun getAccessToken(context: Context): Result<String> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val authorizationRequest =
                        AuthorizationRequest
                            .builder()
                            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                            .build()
                    suspendCancellableCoroutine { cont ->
                        Identity
                            .getAuthorizationClient(context)
                            .authorize(authorizationRequest)
                            .addOnSuccessListener { result ->
                                val token = result.accessToken
                                if (token != null) {
                                    cont.resume(token)
                                } else {
                                    cont.resumeWithException(
                                        IllegalStateException("Drive access token not returned"),
                                    )
                                }
                            }.addOnFailureListener { e -> cont.resumeWithException(e) }
                    }
                }
            }

        suspend fun signOut(context: Context) {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            backupPrefsRepo.updateDriveAccountEmail(null)
        }
    }
