package com.kachat.app.util

/**
 * Limits on the fee a user can type for a message (the fee pill in 1:1 and group chats).
 * Messages don't ask for a fingerprint or PIN, so the custom fee is capped, and a fee far above
 * what a message needs asks for a confirm first. Port of iOS `MessageFeeLimit`
 * (KaChatTransactionBuilder.swift).
 */
object MessageFeeLimit {
    /** 1 KAS. Above this the fee editor refuses, and the builder clamps as a backstop. */
    const val MAX_SOMPI: Long = 100_000_000L

    /** 0.01 KAS, roughly 100x a typical message fee. Above this the editor asks to confirm. */
    const val WARN_SOMPI: Long = 1_000_000L

    /**
     * The fee a message actually pays: a custom fee wins over the estimate, but never goes below
     * what the network needs ([minimumSompi], or the send is rejected) or above [MAX_SOMPI].
     */
    fun clamp(customSompi: Long?, minimumSompi: Long): Long {
        if (customSompi == null) return minimumSompi
        return maxOf(minOf(customSompi, MAX_SOMPI), minimumSompi)
    }
}
