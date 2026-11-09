package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.PendingKnsCommit
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionId
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KnsInscriptionScript
import com.kachat.app.util.Schnorr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and submits KNS commit/reveal inscription transactions — the same commit->reveal
 * pattern used for both domain registration and profile field updates. Mirrors
 * [KaspaWalletEngine]'s structure; verified byte-for-byte against the iOS reference
 * (`KaChat/Services/KaChatTransactionBuilder.swift:480-1188`).
 */
@Singleton
class KnsInscriptionEngine @Inject constructor(
    private val networkService: NetworkService,
    private val nodePoolManager: NodePoolManager,
    private val settings: AppSettingsRepository,
    /** The app's one record of coins just spent: picked from without them, and told what a
     *  commit spends (iOS d23e6f7a, reserved centrally in `NodePoolService`). */
    private val walletEngine: KaspaWalletEngine,
    /** The failed reveal's localized text, and when a pending commit was saved. */
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    data class CommitResult(
        val commitTxId: String,
        val redeemScript: ByteArray,
        val commitScriptPubKeyHex: String,
        val commitAmountSompi: Long,
        val revealAmountSompi: Long
    )

    // Serializes commit/reveal builds the same way KaspaWalletEngine.sendKaspa does, for the
    // same reason: overlapping UTXO fetch+select would otherwise race and double-spend.
    private val mutex = Mutex()

    /** Commits saved by this run: left to the send that made them, not resumed at launch (iOS
     *  4f8e14bb `savedThisRun`). */
    private val savedThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** When the pending commit was saved, kept beside it: [PendingKnsCommit] has no field for it. */
    private val recoveryPrefs get() = appContext.getSharedPreferences(RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)

    private suspend fun savePending(commit: PendingKnsCommit) {
        settings.setPendingKnsCommit(commit)
        savedThisRun.add(commit.commitTxId)
        recoveryPrefs.edit().putString(KEY_SAVED_ID, commit.commitTxId).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
    }

    private suspend fun clearPending() {
        settings.clearPendingKnsCommit()
        recoveryPrefs.edit().clear().apply()
    }

    /** How long ago [commitTxId] was saved. One saved before this was kept counts from now. */
    private fun pendingAgeMs(commitTxId: String): Long {
        val now = System.currentTimeMillis()
        if (recoveryPrefs.getString(KEY_SAVED_ID, null) != commitTxId) {
            recoveryPrefs.edit().putString(KEY_SAVED_ID, commitTxId).putLong(KEY_SAVED_AT, now).apply()
            return 0L
        }
        return now - recoveryPrefs.getLong(KEY_SAVED_AT, now)
    }

    /** The commit whose reveal hasn't been accepted yet, when it wasn't made by this run. */
    suspend fun pendingCommitFromEarlierRun(): PendingKnsCommit? =
        settings.pendingKnsCommit.first()?.takeUnless { it.commitTxId in savedThisRun }

    suspend fun buildAndSubmitCommit(
        payloadJson: ByteArray,
        commitAmountSompi: Long,
        revealAmountSompi: Long,
        revealTargetAddress: String,
        operationType: String,
        fundingAddress: String,
        fundingPrivateKey: ByteArray,
        ownerPrivateKey: ByteArray,
        /** Where the commit's funding change goes; defaults back to [fundingAddress]. Used when
         *  the primary spending address funds a transfer and its change must land on a fresh
         *  address (see WalletService.transferDomain). */
        changeAddress: String = fundingAddress,
        /** What the reveal will pay (the check below, before anything is spent). */
        revealPriorityFeeSompi: Long = REVEAL_PRIORITY_FEE_SOMPI
    ): CommitResult = mutex.withLock {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")
        // One pending commit at a time: its record is a single slot, so a second commit would
        // overwrite it and strand the first one's funds (domain fee included) for good. Its reveal
        // is retried from the profile screen first.
        if (settings.pendingKnsCommit.first() != null) throw IllegalStateException(PENDING_COMMIT_MESSAGE)

        // The redeem script's embedded pubkey is [ownerPrivateKey]'s (identity), not the funding
        // key's — that's what the KNS indexer treats as the domain/profile's owner, and what the
        // later reveal signature must satisfy. [fundingAddress]/[fundingPrivateKey] only pay for
        // the commit output; they never touch ownership.
        val hrp = fundingAddress.substringBefore(":")
        val xOnlyPubKey = Schnorr.publicKeyXOnly(ownerPrivateKey)
        val redeemScript = KnsInscriptionScript.buildRedeemScript(xOnlyPubKey, "kns", payloadJson)
        val commitAddress = KnsInscriptionScript.commitAddress(redeemScript, hrp)
        val commitScriptPubKeyHex = KaspaAddress.getScriptPublicKey(commitAddress)
        val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress)

        // Node first, REST second - see NodePoolManager.getUtxosByAddress. Never coins another send
        // from this app has just spent (iOS d23e6f7a `unreservedUtxos`).
        val utxos = walletEngine.unreservedUtxos(nodePoolManager.getUtxosByAddress(fundingAddress) ?: api.getUtxos(fundingAddress))
        if (utxos.isEmpty()) throw IllegalStateException("No spendable UTXOs available for KNS inscription")

        // The network minimum, as iOS prices it (KaspaFeePolicy, buildKNSAddProfileCommitTx).
        val feeRateSompiPerGram = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM

        val selection = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos = utxos,
            amountSompi = commitAmountSompi,
            feeRateSompiPerGram = feeRateSompiPerGram,
            payloadBytes = null,
            recipientScriptLen = commitScriptPubKeyHex.length / 2,
            changeScriptLen = changeScriptHex.length / 2
        )
        if (selection.totalSelected < selection.requiredAmount) {
            throw IllegalStateException("Insufficient funds: needed ${selection.requiredAmount}, have ${selection.totalSelected}")
        }

        val outputs = mutableListOf<RawOutputWithVersion>(
            RawOutputWithVersion(amount = commitAmountSompi, scriptPublicKey = ScriptPublicKeyWithVersion(commitScriptPubKeyHex, 0))
        )
        // Change stays when this transaction's KIP-9 storage mass allows it (see
        // KaspaMass.storageMass), the same rule every other builder uses; otherwise it is fee.
        val commitInputs = selection.selectedUtxos.map { it.utxoEntry.amount }
        // Only dust is ever folded into the fee (iOS dd4d977) - see KaspaWalletEngine.
        if (selection.storageMassBlocked || (selection.changeAmount > 0 &&
                !KaspaUtxoSelector.changeIsKeptOrFoldable(commitInputs, listOf(commitAmountSompi), selection.changeAmount))
        ) {
            throw IllegalStateException(KaspaUtxoSelector.SMALL_SEND_MASS_MESSAGE)
        }
        if (selection.changeAmount > 0 && KaspaMass.fitsStorageMass(commitInputs, listOf(commitAmountSompi, selection.changeAmount))) {
            outputs.add(RawOutputWithVersion(amount = selection.changeAmount, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
        }

        val rawTx = RawTransaction(
            inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = outputs
        )
        val signedTx = KaspaTransactionSigner.signTransaction(rawTx, selection.selectedUtxos, fundingPrivateKey)

        // Persist BEFORE submitting, under the id computed locally — if the submit's answer is
        // lost (SendOutcomeUnknownException: the commit may have landed), the reveal fails, or
        // the app dies here, the commit amount is still recoverable from the profile screen
        // instead of silently stuck, and no second paid commit is started over it. changeAddress
        // is needed so a retry (possibly after the active spending address has since rotated)
        // still returns the reveal's leftover change to the same address the commit was funded
        // from. Should the id ever fail to compute, the record waits for the node's id instead.
        fun pendingCommit(commitTxId: String) = PendingKnsCommit(
            commitTxId = commitTxId,
            redeemScriptHex = redeemScript.toHex(),
            commitScriptPubKeyHex = commitScriptPubKeyHex,
            commitAmountSompi = commitAmountSompi,
            revealAmountSompi = revealAmountSompi,
            revealTargetAddress = revealTargetAddress,
            operationType = operationType,
            changeAddress = changeAddress
        )
        val expectedTxId = runCatching { KaspaTransactionId.compute(signedTx) }.getOrNull()?.lowercase()
        if (expectedTxId != null) {
            // Built before the commit goes out: what would stop the reveal stops it all here, with
            // nothing spent (iOS 4f8e14bb).
            buildSignedReveal(
                CommitResult(expectedTxId, redeemScript, commitScriptPubKeyHex, commitAmountSompi, revealAmountSompi),
                revealTargetAddress, changeAddress, ownerPrivateKey, revealPriorityFeeSompi
            )
            savePending(pendingCommit(expectedTxId))
        }

        // A submit error is checked against the network before it counts (audit IOS-014).
        val commitTxId = try {
            nodePoolManager.submitConfirmingKnown(signedTx, api) {
                // moves money: never held in a node's orphan pool (iOS 4f8e14bb)
                nodePoolManager.submitWaitingForParent(signedTx, allowOrphan = false, escalateOrphan = false)
            }
        } catch (unknown: SendOutcomeUnknownException) {
            // It may have landed: the record stays, so the reveal can still be retried. Its coins
            // aren't held, so a resend can only spend the same ones (iOS 4f8e14bb).
            throw unknown
        } catch (e: Exception) {
            // A cancelled submit may have gone out: its record stays, as for an unknown outcome.
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            // The node refused it and the network doesn't have it: nothing is tied up, so there
            // is nothing to recover.
            if (expectedTxId != null) clearPending()
            throw e
        }
        // sent: its coins stay out of every coin pick until a node shows them spent
        walletEngine.reserveSpentOutpoints(selection.selectedUtxos.map { it.outpoint })
        if (commitTxId.lowercase() != expectedTxId) savePending(pendingCommit(commitTxId))

        CommitResult(commitTxId, redeemScript, commitScriptPubKeyHex, commitAmountSompi, revealAmountSompi)
    }

    suspend fun buildAndSubmitReveal(
        commit: CommitResult,
        revealTargetAddress: String,
        changeAddress: String,
        ownerPrivateKey: ByteArray,
        priorityFeeSompi: Long = REVEAL_PRIORITY_FEE_SOMPI
    ): String = mutex.withLock {
        networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")
        val signedTx = buildSignedReveal(commit, revealTargetAddress, changeAddress, ownerPrivateKey, priorityFeeSompi)
        val revealTxId = try {
            submitRevealRetrying(signedTx)
        } catch (unknown: SendOutcomeUnknownException) {
            throw unknown
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.w("KnsInscriptionEngine", "Reveal of ${commit.commitTxId.take(12)} failed, kept for the next launch: ${e.message}")
            throw IllegalStateException(appContext.getString(com.kachat.app.R.string.kns_reveal_will_finish_on_next_open), e)
        }
        clearPending()
        revealTxId
    }

    /**
     * Finishes the reveal of [pending], left by an earlier run. Spending the commit output is the
     * reveal, whatever the outputs (the redeem script holds the inscription), so there is no
     * separate refund: finishing the reveal is what returns the coins, to the address that funded
     * them, minus the fee (iOS 4f8e14bb `KNSRevealRecovery.resume`). True when it needs no further
     * look: finished, or dropped as gone.
     */
    suspend fun resumeReveal(pending: PendingKnsCommit, ownerPrivateKey: ByteArray): Boolean = mutex.withLock {
        val label = pending.commitTxId.take(12)
        try {
            val redeemScript = pending.redeemScriptHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val hrp = pending.revealTargetAddress.substringBefore(":")
            val commitAddress = KnsInscriptionScript.commitAddress(redeemScript, hrp)
            val utxos = nodePoolManager.getUtxosByAddress(commitAddress) ?: return@withLock false
            val isLive = utxos.any { it.outpoint.transactionId.equals(pending.commitTxId, ignoreCase = true) && it.outpoint.index == 0 }
            if (!isLive) {
                // It never landed, or its reveal did.
                if (pendingAgeMs(pending.commitTxId) <= MISSING_COMMIT_LIFETIME_MS) return@withLock false
                Log.i("KnsInscriptionEngine", "Commit $label is gone: nothing to finish")
                clearPending()
                return@withLock true
            }
            val commit = CommitResult(pending.commitTxId, redeemScript, pending.commitScriptPubKeyHex, pending.commitAmountSompi, pending.revealAmountSompi)
            val signedTx = buildSignedReveal(
                commit, pending.revealTargetAddress, pending.changeAddress ?: pending.revealTargetAddress, ownerPrivateKey, REVEAL_PRIORITY_FEE_SOMPI
            )
            val revealTxId = submitRevealRetrying(signedTx)
            Log.i("KnsInscriptionEngine", "Finished commit $label with reveal ${revealTxId.take(12)}")
            clearPending()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Log.w("KnsInscriptionEngine", "Could not finish commit $label: ${e.message}")
            false
        }
    }

    /** A few tries with a pause between: the commit may still be reaching the node (iOS 4f8e14bb
     *  `submitRetrying`). An unknown outcome is passed on at once. */
    private suspend fun submitRevealRetrying(signedTx: RawTransaction): String {
        var last: Exception = IllegalStateException("KNS reveal was not submitted")
        for (attempt in 0 until REVEAL_ATTEMPTS) {
            if (attempt > 0) delay(minOf(attempt * 2, 8) * 1_000L)
            try {
                return submitRevealAllowingOrphan(signedTx)
            } catch (unknown: SendOutcomeUnknownException) {
                throw unknown
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w("KnsInscriptionEngine", "Reveal attempt ${attempt + 1} failed: ${e.message}")
                last = e
            }
        }
        throw last
    }

    private suspend fun submitRevealAllowingOrphan(signedTx: RawTransaction): String =
        nodePoolManager.submitConfirmingKnown(signedTx, networkService.kaspaRestApi.value) {
            try {
                // an orphan rejection first waits for the commit to reach that node (iOS 4eb492f)
                nodePoolManager.submitWaitingForParent(signedTx, allowOrphan = false)
            } catch (e: Exception) {
                if (e.message?.contains("orphan", ignoreCase = true) == true) {
                    Log.w("KnsInscriptionEngine", "Reveal rejected as orphan, retrying with allowOrphan=true", e)
                    nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = true)
                } else {
                    throw e
                }
            }
        }

    /** The reveal of [commit], signed: no network, so it can run before the commit goes out. */
    private fun buildSignedReveal(
        commit: CommitResult,
        revealTargetAddress: String,
        changeAddress: String,
        ownerPrivateKey: ByteArray,
        priorityFeeSompi: Long
    ): RawTransaction {
        val targetScriptHex = KaspaAddress.getScriptPublicKey(revealTargetAddress)
        val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress)
        // The network minimum, the priority fee on top (iOS buildKNSAddProfileRevealTx).
        val feeRateSompiPerGram = KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM

        val recipientOutput = if (commit.revealAmountSompi > 0) {
            RawOutputWithVersion(amount = commit.revealAmountSompi, scriptPublicKey = ScriptPublicKeyWithVersion(targetScriptHex, 0))
        } else null
        val baseOutputs = listOfNotNull(recipientOutput)

        // Signature script is push(sig+hashtype) + push(redeemScript) — much bigger than the
        // standard 66-byte Schnorr push, so mass/fee must account for the real size.
        val sigScriptSize = 66L + KnsInscriptionScript.canonicalPushSize(commit.redeemScript.size)

        require(commit.commitAmountSompi >= commit.revealAmountSompi) {
            "KNS reveal amount cannot exceed the commit amount"
        }

        val massWithChange = KaspaMass.calculateMass(
            numInputs = 1,
            outputScriptLens = (baseOutputs.map { it.scriptPublicKey.scriptPublicKey.length / 2 }) + (changeScriptHex.length / 2),
            payloadSize = 0,
            sigScriptLens = listOf(sigScriptSize)
        )
        val feeWithChange = KaspaMass.calculateFee(massWithChange, feeRateSompiPerGram) + priorityFeeSompi

        val outputs = baseOutputs.toMutableList()
        val availableForChangeAndFee = commit.commitAmountSompi - commit.revealAmountSompi

        require(availableForChangeAndFee >= feeWithChange) { "KNS reveal amount cannot be covered by commit output" }
        val changeWithFee = availableForChangeAndFee - feeWithChange

        // The reveal spends exactly one input, the commit output, so the shape is fully known
        // here: that input against the reveal output (if any) plus this change. Decided by KIP-9
        // storage mass (see KaspaMass.storageMass), not a flat floor.
        val revealInputs = listOf(commit.commitAmountSompi)
        val baseAmounts = baseOutputs.map { it.amount }
        if (changeWithFee > 0 && KaspaMass.fitsStorageMass(revealInputs, baseAmounts + changeWithFee)) {
            outputs.add(RawOutputWithVersion(amount = changeWithFee, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
        } else {
            // Change would not stand on its own — recompute the fee for a transaction with no
            // change output (lower mass, so a lower fee), and see if THAT leaves enough instead.
            val massNoChange = KaspaMass.calculateMass(
                numInputs = 1,
                outputScriptLens = baseOutputs.map { it.scriptPublicKey.scriptPublicKey.length / 2 },
                payloadSize = 0,
                sigScriptLens = listOf(sigScriptSize)
            )
            val feeNoChange = KaspaMass.calculateFee(massNoChange, feeRateSompiPerGram) + priorityFeeSompi
            require(availableForChangeAndFee >= feeNoChange) { "Insufficient commit amount for KNS reveal fee" }
            val changeNoChange = availableForChangeAndFee - feeNoChange
            if (changeNoChange > 0 && KaspaMass.fitsStorageMass(revealInputs, baseAmounts + changeNoChange)) {
                outputs.add(RawOutputWithVersion(amount = changeNoChange, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
            }
        }
        require(outputs.isNotEmpty()) { "KNS reveal transaction has no spendable outputs after fees" }

        val commitUtxo = UtxoEntry(
            address = changeAddress,
            outpoint = Outpoint(transactionId = commit.commitTxId, index = 0),
            utxoEntry = UtxoData(
                amount = commit.commitAmountSompi,
                scriptPublicKey = ScriptPublicKey(commit.commitScriptPubKeyHex),
                blockDaaScore = 0,
                isCoinbase = false
            )
        )

        val rawTx = RawTransaction(
            inputs = listOf(RawInput(previousOutpoint = commitUtxo.outpoint, signatureScript = "")),
            outputs = outputs
        )
        return KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, commit.redeemScript, ownerPrivateKey)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val REVEAL_PRIORITY_FEE_SOMPI = 2_000_000L

        /** The most a transfer's priority fee can be: the reveal pays it out of the 2 KAS commit,
         *  and the rest of that comes back as change (iOS 4f8e14bb). */
        const val MAX_TRANSFER_PRIORITY_FEE_SOMPI = 100_000_000L

        private const val REVEAL_ATTEMPTS = 5
        /** A commit that can't be found is dropped after this: it never landed, or its reveal did
         *  (iOS `missingCommitLifetime`). */
        private const val MISSING_COMMIT_LIFETIME_MS = 24 * 60 * 60 * 1000L
        private const val RECOVERY_PREFS = "kns_reveal_recovery"
        private const val KEY_SAVED_ID = "commit_id"
        private const val KEY_SAVED_AT = "saved_at"

        /** Why a new commit is refused while an earlier one waits for its reveal. */
        const val PENDING_COMMIT_MESSAGE =
            "An earlier KNS inscription hasn't finished. Retry its reveal from your profile before starting another."
    }
}
