package com.kachat.app.services.contacts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * The account KaChat's contact rows belong to.
 *
 * Android only lets an app put its own rows on a phone contact - the "KaChat call" and "KaChat
 * video call" lines on a contact card - when those rows belong to an account type the app owns.
 * So KaChat declares one. Nothing signs in through it and it holds no credentials: every method
 * below either does nothing or says no. It exists purely so the contacts provider has an owner
 * for the rows [com.kachat.app.services.CallableContactsExporter] writes.
 */
class KaChatAccountAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null

    /** Nothing to add: the one account is created by the app itself, never by the user. */
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle? = null

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = Bundle()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle? = null

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

/** Hands the authenticator above to the system. */
class KaChatAccountService : Service() {
    private val authenticator by lazy { KaChatAccountAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
