package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.PendingKnsCommit
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KnsInscriptionScript
import com.kachat.app.util.Schnorr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

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
    private val settings: AppSettingsRepository
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
        changeAddress: String = fundingAddress
    ): CommitResult = mutex.withLock {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")

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

        // Node first, REST second - see NodePoolManager.getUtxosByAddress.
        val utxos = nodePoolManager.getUtxosByAddress(fundingAddress) ?: api.getUtxos(fundingAddress)
        if (utxos.isEmpty()) throw IllegalStateException("No spendable UTXOs available for KNS inscription")

        val feeRateSompiPerGram = fetchFeeRate(api)

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
        if (selection.changeAmount > 0 && KaspaMass.fitsStorageMass(commitInputs, listOf(commitAmountSompi, selection.changeAmount))) {
            outputs.add(RawOutputWithVersion(amount = selection.changeAmount, scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)))
        }

        val rawTx = RawTransaction(
            inputs = selection.selectedUtxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = outputs
        )
        val signedTx = KaspaTransactionSigner.signTransaction(rawTx, selection.selectedUtxos, fundingPrivateKey)
        val commitTxId = nodePoolManager.getBroadcastConnection().submitTransaction(signedTx, allowOrphan = false)

        val result = CommitResult(commitTxId, redeemScript, commitScriptPubKeyHex, commitAmountSompi, revealAmountSompi)

        // Persist BEFORE attempting reveal — if reveal fails or the app dies here, the commit
        // amount is still recoverable on next launch instead of silently stuck. changeAddress is
        // needed so a retry (possibly after the active spending address has since rotated) still
        // returns the reveal's leftover change to the same address the commit was funded from.
        settings.setPendingKnsCommit(
            PendingKnsCommit(
                commitTxId = commitTxId,
                redeemScriptHex = redeemScript.toHex(),
                commitScriptPubKeyHex = commitScriptPubKeyHex,
                commitAmountSompi = commitAmountSompi,
                revealAmountSompi = revealAmountSompi,
                revealTargetAddress = revealTargetAddress,
                operationType = operationType,
                changeAddress = changeAddress
            )
        )

        result
    }

    suspend fun buildAndSubmitReveal(
        commit: CommitResult,
        revealTargetAddress: String,
        changeAddress: String,
        ownerPrivateKey: ByteArray,
        priorityFeeSompi: Long = REVEAL_PRIORITY_FEE_SOMPI
    ): String = mutex.withLock {
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")

        val targetScriptHex = KaspaAddress.getScriptPublicKey(revealTargetAddress)
        val changeScriptHex = KaspaAddress.getScriptPublicKey(changeAddress)
        val feeRateSompiPerGram = fetchFeeRate(api)

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
        val signedTx = KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, commit.redeemScript, ownerPrivateKey)

        val connection = nodePoolManager.getBroadcastConnection()
        val revealTxId = try {
            connection.submitTransaction(signedTx, allowOrphan = false)
        } catch (e: Exception) {
            if (e.message?.contains("orphan", ignoreCase = true) == true) {
                Log.w("KnsInscriptionEngine", "Reveal rejected as orphan, retrying with allowOrphan=true", e)
                connection.submitTransaction(signedTx, allowOrphan = true)
            } else {
                throw e
            }
        }

        settings.clearPendingKnsCommit()
        revealTxId
    }

    private suspend fun fetchFeeRate(api: KaspaRestApi): Long {
        return try {
            val estimate = api.getFeeEstimate()
            val quoted = estimate.normalBuckets.firstOrNull()?.feerate ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
            ceil(quoted).toLong().coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
        } catch (e: Exception) {
            Log.w("KnsInscriptionEngine", "Failed to fetch fee estimate, using network minimum", e)
            KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val REVEAL_PRIORITY_FEE_SOMPI = 2_000_000L
    }
}
