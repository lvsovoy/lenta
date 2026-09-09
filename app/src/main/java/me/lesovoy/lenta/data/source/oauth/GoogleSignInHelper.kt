package me.lesovoy.lenta.data.source.oauth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed class GoogleAuthResult {
    data class Success(
        val token: String,
        val email: String,
        val displayName: String
    ) : GoogleAuthResult()

    data class NeedsUserRecovery(
        val recoveryIntent: Intent,
        val account: GoogleSignInAccount
    ) : GoogleAuthResult()

    data class Failure(
        val errorMessage: String,
        val cause: Throwable? = null
    ) : GoogleAuthResult()
}

object GoogleSignInHelper {

    const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val USERINFO_PROFILE_SCOPE = "https://www.googleapis.com/auth/userinfo.profile"

    val OAUTH2_SCOPE_STRING = "oauth2:$DRIVE_READONLY_SCOPE $DRIVE_FILE_SCOPE $USERINFO_EMAIL_SCOPE $USERINFO_PROFILE_SCOPE"

    fun getGoogleSignInOptions(clientId: String? = null): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(DRIVE_READONLY_SCOPE),
                Scope(DRIVE_FILE_SCOPE)
            )

        val targetClientId = clientId?.trim()?.ifBlank { null } ?: OAuthConfig.google.clientId.trim().ifBlank { null }
        // If an OAuth Web Client ID is configured, we can also request server auth code if desired
        if (targetClientId != null && targetClientId.contains(".apps.googleusercontent.com")) {
            // Optional: builder.requestIdToken(targetClientId)
        }

        return builder.build()
    }

    fun getSignInClient(context: Context, clientId: String? = null): GoogleSignInClient {
        val gso = getGoogleSignInOptions(clientId)
        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignedInAccountFromIntent(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                Result.success(account)
            } else {
                Result.failure(Exception("Google Sign-In returned null account"))
            }
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                12501 -> "Google Sign-In was cancelled"
                12500 -> "Google Sign-In configuration error"
                7 -> "Network error during Google Sign-In"
                else -> "Google Sign-In failed (code: ${e.statusCode}): ${e.message}"
            }
            Result.failure(Exception(message, e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAccessToken(
        context: Context,
        account: GoogleSignInAccount
    ): GoogleAuthResult = withContext(Dispatchers.IO) {
        val googleAccount: Account = account.account
            ?: Account(account.email ?: return@withContext GoogleAuthResult.Failure("Account email missing"), "com.google")

        try {
            val token = GoogleAuthUtil.getToken(
                context.applicationContext,
                googleAccount,
                OAUTH2_SCOPE_STRING
            )

            val email = account.email.orEmpty().ifBlank { googleAccount.name }
            val displayName = account.displayName.orEmpty().ifBlank { email }

            GoogleAuthResult.Success(
                token = token,
                email = email,
                displayName = displayName
            )
        } catch (e: UserRecoverableAuthException) {
            val intent = e.intent
            if (intent != null) {
                GoogleAuthResult.NeedsUserRecovery(intent, account)
            } else {
                GoogleAuthResult.Failure("Google authorization requires user action", e)
            }
        } catch (e: GoogleAuthException) {
            GoogleAuthResult.Failure("Google authentication error: ${e.message}", e)
        } catch (e: IOException) {
            GoogleAuthResult.Failure("Network error communicating with Google Play Services: ${e.message}", e)
        } catch (e: Exception) {
            GoogleAuthResult.Failure("Unexpected error during Google authentication: ${e.message}", e)
        }
    }

    suspend fun refreshAccessToken(
        context: Context,
        email: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (email.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Email is blank"))
        }

        try {
            val googleAccount = Account(email, "com.google")
            val token = GoogleAuthUtil.getToken(
                context.applicationContext,
                googleAccount,
                OAUTH2_SCOPE_STRING
            )
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearToken(
        context: Context,
        token: String
    ) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context.applicationContext, token)
        } catch (_: Exception) {
        }
    }

    suspend fun signOut(context: Context, clientId: String? = null) = withContext(Dispatchers.IO) {
        try {
            val client = getSignInClient(context, clientId)
            client.signOut()
        } catch (_: Exception) {
        }
    }
}
