package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.AddressActivityNotifier
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.FormatQuote

/**
 * The frame every "..." menu in the app now uses: a half sheet with a title, a line of context,
 * and a stack of [ActionSheetRow]s.
 *
 * These were popup menus of bare labels, which have room for a verb and nothing else. A sheet has
 * room for each option to say what it does, and it keeps the thing being acted on on screen while
 * you choose. Mirrors iOS's `.sheet(item:)` menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheetContainer(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    /** Extra line under the subtitle - the txid on the transaction menu, for instance. */
    detail: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.background,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

/** One option in an [ActionSheetContainer]. Same shape as iOS's `ActionSheetRow`. */
@Composable
fun ActionSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = KaspaTeal,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * The rename half of an [ActionSheetContainer] that renames something in place: a field, a Cancel
 * that walks back to the options, and a Save that commits and closes.
 */
@Composable
fun ColumnScope.ActionSheetRenameFields(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    label: String = stringResource(R.string.name),
) {
    val colors = LocalAppColors.current
    val focusRequester = remember { FocusRequester() }
    // The keyboard should be up the moment the field appears - the tap that revealed it was
    // already the decision to type.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSave() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = KaspaTeal,
            unfocusedBorderColor = colors.textSecondary,
            focusedLabelColor = KaspaTeal,
            unfocusedLabelColor = colors.textSecondary,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
        ) {
            Text(stringResource(R.string.cancel), fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onSave,
            enabled = value.isNotBlank(),
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
        ) {
            Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * What to do with a transaction you tapped in an address history: look at it, or record it.
 *
 * Shared by cold storage history, the spending-address history and the chatting address, all
 * three of which used to raise their own identical popup menu.
 */
@Composable
fun TransactionActionsSheet(
    tx: ColdStorageAddressDiscovery.AddressTransaction,
    onOpenExplorer: () -> Unit,
    onAddToPortfolio: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheetContainer(
        title = "Transaction",
        subtitle = summary(tx),
        detail = AddressActivityNotifier.shortAddress(tx.txId),
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = "Open in Explorer",
            subtitle = "Opens this transaction on the block explorer.",
            onClick = onOpenExplorer,
        )
        ActionSheetRow(
            icon = Icons.Default.PieChart,
            title = "Add to Portfolio",
            subtitle = "Records it as a buy or a sell in a portfolio of your choosing.",
            onClick = onAddToPortfolio,
        )
    }
}

/** "Sent 12.5 KAS on 3 Sep 2026, 14:02" - what is about to be acted on, in one line. */
private fun summary(tx: ColdStorageAddressDiscovery.AddressTransaction): String {
    val direction = if (tx.sent) "Sent" else "Received"
    val amount = AddressActivityNotifier.formatKas(tx.amountSompi)
    val time = tx.blockTimeMillis?.let {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
    }
    return if (time == null) "$direction $amount KAS" else "$direction $amount KAS on $time"
}

/**
 * Who reacted to one message, and with what. Shared by 1:1, group and broadcast bubbles.
 *
 * The pill on a bubble shows which emoji are on it and nothing else - not how many of each, and
 * not from whom. Mirrors iOS's `ReactionsSheet`.
 */
@Composable
fun ChatReactionsSheet(
    reactions: List<com.kachat.app.models.ReactionEntity>,
    isMe: (String) -> Boolean,
    nameFor: (String) -> String,
    onDismiss: () -> Unit,
    /** KNS avatar for a reactor, when one is cached. A face is how you recognise someone in a
     *  list of names you may not have saved. */
    avatarFor: (String) -> String? = { null },
) {
    val colors = LocalAppColors.current
    // Grouped by emoji, most-reacted first, so "12 people" reads before the individual names.
    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }.entries.sortedByDescending { it.value.size }
    }
    ActionSheetContainer(
        title = "Reactions",
        subtitle = if (reactions.size == 1) "1 reaction" else "${reactions.size} reactions",
        onDismiss = onDismiss,
    ) {
        grouped.forEach { (emoji, rows) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (rows.size == 1) "1 person" else "${rows.size} people",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                rows.forEach { row ->
                    Spacer(Modifier.height(8.dp))
                    val label = if (isMe(row.reactorAddress)) "You" else nameFor(row.reactorAddress)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(
                            imageUrl = avatarFor(row.reactorAddress),
                            fallbackText = label,
                            size = 28.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}


/**
 * What to do with a link someone sent: the half-sheet form of the old alert dialog.
 *
 * A dialog of bare text buttons could show the verb and nothing else, and squeezed the URL into
 * its title. As a sheet the link itself gets room to be read - which matters, because deciding
 * whether to open a link IS reading it. Mirrors iOS's `LinkActionsSheet`.
 */
@Composable
fun LinkActionsSheet(
    url: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    /** Null where replying makes no sense (a KaPost, a broadcast you cannot reply into). */
    onReply: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    ActionSheetContainer(title = "Link", subtitle = null, onDismiss = onDismiss) {
        Text(
            url,
            color = colors.textSecondary,
            fontSize = 13.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        ActionSheetRow(
            icon = Icons.Default.Public,
            title = "Open Link",
            subtitle = "Opens in your browser.",
        ) { onDismiss(); onOpen() }
        ActionSheetRow(
            icon = Icons.Default.ContentCopy,
            title = "Copy Link",
            subtitle = "Copies the address to your clipboard.",
        ) { onDismiss(); onCopy() }
        if (onReply != null) {
            ActionSheetRow(
                icon = Icons.AutoMirrored.Filled.Reply,
                title = "Reply",
                subtitle = "Reply to this message instead.",
            ) { onDismiss(); onReply() }
        }
    }
}

/** Repost as-is, or quote it with your own words. Mirrors iOS's `RepostActionsSheet`. */
@Composable
fun RepostActionsSheet(
    isReposted: Boolean,
    onDismiss: () -> Unit,
    onRepost: () -> Unit,
    onQuote: () -> Unit,
) {
    ActionSheetContainer(
        title = if (isReposted) "Reposted" else "Repost",
        subtitle = null,
        onDismiss = onDismiss,
    ) {
        ActionSheetRow(
            icon = if (isReposted) Icons.AutoMirrored.Filled.Undo else Icons.Default.Repeat,
            title = if (isReposted) "Undo Repost" else "Repost",
            subtitle = if (isReposted) {
                "Removes it from your profile."
            } else {
                "Shares it to your followers as-is."
            },
            tint = if (isReposted) Color(0xFFFF3B30) else KaspaTeal,
        ) { onDismiss(); onRepost() }
        ActionSheetRow(
            icon = Icons.Default.FormatQuote,
            title = "Quote",
            subtitle = "Adds your own words above it.",
        ) { onDismiss(); onQuote() }
    }
}

/**
 * A yes/no confirmation, in a half sheet.
 *
 * The app's confirmations were AlertDialogs - a stack of bare verbs with the reason squeezed into
 * a small line above them. As a sheet the consequence gets a full row of its own next to the
 * action it belongs to, which is what someone about to log out or delete something is actually
 * reading for.
 */
@Composable
fun ConfirmActionSheet(
    title: String,
    confirmTitle: String,
    /** What actually happens if they go ahead - the row's second line. */
    confirmSubtitle: String,
    confirmIcon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = true,
) {
    ActionSheetContainer(title = title, subtitle = null, onDismiss = onDismiss) {
        ActionSheetRow(
            icon = confirmIcon,
            title = confirmTitle,
            subtitle = confirmSubtitle,
            tint = if (isDestructive) Color(0xFFFF3B30) else KaspaTeal,
            onClick = { onDismiss(); onConfirm() },
        )
        ActionSheetRow(
            icon = Icons.Default.Close,
            title = "Cancel",
            subtitle = "Leave everything as it is.",
            onClick = onDismiss,
        )
    }
}
