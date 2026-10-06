package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.UtxoEntry

/**
 * Coin control's selection rules, kept out of the screen so they can be tested.
 *
 * A pick can never hold more coins than one transaction may spend: the hot wallet refuses more
 * than [KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION] inputs, and KasSigner signs at most
 * [KsptCodec.MAX_INPUTS]. A bigger pick used to be accepted here and only refused at send, after
 * the amount and fee had been filled in (audit AND-012 follow-up). So adding a coin past the cap
 * is refused, and Select All takes the largest coins up to it.
 */
object CoinControlSelection {

    /**
     * [selected] with [outpoint] toggled. Removing always works; adding works only while fewer
     * than [maxSelection] coins are picked. Null means the add was refused for the cap.
     */
    fun toggle(selected: Set<Outpoint>, outpoint: Outpoint, maxSelection: Int): Set<Outpoint>? = when {
        outpoint in selected -> selected - outpoint
        selected.size >= maxSelection -> null
        else -> selected + outpoint
    }

    /** Select All: every coin, or the [maxSelection] largest when there are more. */
    fun selectAll(utxos: List<UtxoEntry>, maxSelection: Int): Set<Outpoint> =
        largest(utxos, maxSelection).map { it.outpoint }.toSet()

    /**
     * The coins of [utxos] whose outpoints are in [keys], at most [maxSelection] of them (the
     * largest). A selection carried in from before the cap existed, or from a different screen,
     * is brought under it this way rather than handed to a send that would refuse it.
     */
    fun resolve(utxos: List<UtxoEntry>, keys: Set<Outpoint>, maxSelection: Int): List<UtxoEntry> =
        largest(utxos.filter { it.outpoint in keys }, maxSelection)

    /** Whether the cap is what stops the pick from growing: it is full and more coins exist. */
    fun isAtCap(selectedCount: Int, availableCount: Int, maxSelection: Int): Boolean =
        selectedCount >= maxSelection && availableCount > maxSelection

    private fun largest(utxos: List<UtxoEntry>, maxSelection: Int): List<UtxoEntry> =
        if (utxos.size > maxSelection) {
            utxos.sortedByDescending { it.utxoEntry.amount }.take(maxSelection.coerceAtLeast(0))
        } else {
            utxos
        }
}
