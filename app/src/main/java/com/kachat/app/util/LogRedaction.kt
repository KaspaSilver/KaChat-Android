package com.kachat.app.util

/**
 * The tail of a Kaspa address, for a log line: `…q8x7f2ab`.
 *
 * The diagnostics export bundles a day of log lines and mails them to support, so a full contact
 * address in one of them travels with it — enough to tie a person to their on-chain history. Eight
 * characters are plenty to match a line against an address you already have in front of you, and
 * useless for identifying one you do not. Mirrors iOS's `address.suffix(8)` (f7a207c).
 *
 * Only for Kaspa addresses. Node endpoints (`host:port`) are not identifying and stay whole — a
 * probe failure you cannot attribute to a node is not a diagnostic.
 */
fun String.redactedForLog(): String = if (length <= 8) this else "…" + takeLast(8)
