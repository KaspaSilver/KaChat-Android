package com.kachat.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Photo
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.services.AddressBookEntry
import com.kachat.app.services.AddressBookManager
import com.kachat.app.services.NextcloudService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay

// The Address Book (Kaspa Hub > Address Book): saved Kaspa addresses with a name and a note, per
// wallet. It replaced syncing with the phone's Contacts - see AddressBookManager (iOS
// AddressBookView.swift, 00767a4).

/** This wallet's Address Book entries, sorted by name; re-renders on every change. */
@Composable
fun rememberAddressBookEntries(): List<AddressBookEntry> {
    val book = AddressBookManager.shared ?: return emptyList()
    val entries by book.entries.collectAsState()
    return entries
}

/**
 * Kaspa Hub > Address Book (also a dock tab, if placed there): the list with its search, add, and
 * the entry pages it opens. [onBack] is the Hub's way back to its grid; null as a dock tab.
 */
@Composable
fun AddressBookScreen(
    onBack: (() -> Unit)?,
    onOpenChat: (String) -> Unit,
    walletViewModel: WalletViewModel,
) {
    val entries = rememberAddressBookEntries()
    var openAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val open = openAddress
    if (open != null) {
        AddressBookEntryDetail(
            address = open,
            onBack = { openAddress = null },
            onOpenChat = onOpenChat,
            walletViewModel = walletViewModel,
        )
        return
    }
    if (onBack != null) BackHandler(onBack = onBack)

    val colors = LocalAppColors.current
    var search by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, search) { AddressBookManager.shared?.search(search) ?: emptyList() }
    var showImportExport by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            MainPageHeader(title = stringResource(R.string.ab_address_book), onBack = onBack) {
                // Import or Export (iOS 87b2a0b), beside +.
                IconButton(onClick = { showImportExport = true }) {
                    Icon(
                        Icons.Default.ImportExport,
                        contentDescription = stringResource(R.string.ab_import_export_a11y),
                        tint = KaspaTeal,
                    )
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ab_add_address), tint = KaspaTeal)
                }
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            AddressBookEmptyState(
                body = stringResource(R.string.ab_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ab_add_address), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Always showing: hidden until you pulled the list down, it read as pull-to-refresh.
                AddressBookSearchField(search, { search = it }, Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
                ) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)
                        ) {
                            shown.forEachIndexed { index, entry ->
                                if (index > 0) SettingsDivider(inset = 66.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openAddress = entry.address }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AddressBookRow(entry, Modifier.weight(1f))
                                    IosDisclosureChevron()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddressBookEntryEditor(address = null, onDone = {}, onDismiss = { showAdd = false })
    }
    AddressBookImportExport(show = showImportExport, onDismiss = { showImportExport = false })
}

/** Reaches the app's [NextcloudService] from the Address Book's composables. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AddressBookDeps {
    fun nextcloud(): NextcloudService
}

/**
 * The Address Book's Import or Export half sheet (iOS AddressBookView, 87b2a0b): Import File,
 * Export File, and - with Nextcloud connected - Import from Nextcloud / Export to Nextcloud
 * (else a line saying where to connect it). The file is plain JSON, "KaChat Address Book
 * <time>.json" ([AddressBookManager.exportData]), so it moves both ways between iOS and Android.
 */
@Composable
private fun AddressBookImportExport(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val nextcloud = remember {
        runCatching {
            dagger.hilt.android.EntryPointAccessors
                .fromApplication(context.applicationContext, AddressBookDeps::class.java).nextcloud()
        }.getOrNull()
    }
    val ncAccount by (nextcloud?.account ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val connected = ncAccount != null
    var showNextcloudImporter by remember { mutableStateOf(false) }

    fun runImport(data: ByteArray) {
        scope.launch {
            val book = AddressBookManager.shared ?: return@launch
            try {
                val result = withContext(Dispatchers.IO) { book.importExport(data) }
                var message = if (result.added + result.updated == 0) context.getString(R.string.ab_already_up_to_date)
                else context.getString(R.string.ab_imported_counts, result.added, result.updated)
                // other-network entries are skipped, and said so (iOS 218dc42, IOS-063)
                if (result.skipped > 0) message += " " + context.getString(R.string.ab_import_skipped_other_network, result.skipped)
                IosToasts.show(message)
            } catch (e: AddressBookManager.ImportException) {
                IosToasts.error(
                    context.getString(
                        when (e.reason) {
                            AddressBookManager.ImportError.NOT_AN_ADDRESS_BOOK -> R.string.ab_err_not_an_export
                            AddressBookManager.ImportError.EMPTY -> R.string.ab_err_export_empty
                            AddressBookManager.ImportError.OTHER_NETWORK ->
                                if (com.kachat.app.util.KaspaNetwork.isTestnet) R.string.ab_err_import_all_mainnet
                                else R.string.ab_err_import_all_testnet
                        }
                    )
                )
            } catch (e: AddressBookManager.SaveException) {
                IosToasts.error(context.getString(R.string.ab_err_no_wallet))
            }
        }
    }

    /** The export's bytes, or null (with a toast) when there is nothing to write or it fails. */
    fun exportBytes(): ByteArray? {
        val book = AddressBookManager.shared
        if (book == null || book.entries.value.isEmpty()) {
            IosToasts.error(context.getString(R.string.ab_nothing_to_export))
            return null
        }
        return runCatching { book.exportData() }.getOrElse {
            IosToasts.error(context.getString(R.string.ab_export_failed))
            null
        }
    }

    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (data == null) IosToasts.error(context.getString(R.string.ab_couldnt_read_file))
            else runImport(data)
        }
    }

    fun exportToFile() {
        val data = exportBytes() ?: return
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "address_book_exports").apply { mkdirs() }
                    val file = java.io.File(dir, AddressBookManager.exportFileName()).apply { writeBytes(data) }
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            }
            if (uri == null) {
                IosToasts.error(context.getString(R.string.ab_export_failed))
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(uri.lastPathSegment, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(intent, null))
            } catch (e: android.content.ActivityNotFoundException) {
                IosToasts.error(context.getString(R.string.ab_export_failed))
            }
        }
    }

    fun exportToNextcloud() {
        val service = nextcloud ?: return
        val data = exportBytes() ?: return
        scope.launch {
            try {
                val path = service.uploadToKaChatFolder(
                    data, AddressBookManager.exportFileName(), "application/json", keepSpaces = true
                )
                IosToasts.show(context.getString(R.string.pnc_saved_to, path))
            } catch (e: Exception) {
                IosToasts.error(
                    context.getString(R.string.pnc_export_failed, com.kachat.app.util.UserFacingError.message(e, "Please try again"))
                )
            }
        }
    }

    if (show) {
        ActionSheetContainer(
            title = stringResource(R.string.ab_import_or_export),
            subtitle = null,
            onDismiss = onDismiss,
        ) {
            ActionSheetRow(
                icon = Icons.Default.FileDownload,
                title = stringResource(R.string.ab_import_file),
                subtitle = stringResource(R.string.ab_import_file_sub),
            ) {
                onDismiss()
                fileImporter.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
            }
            ActionSheetRow(
                icon = Icons.Default.FileUpload,
                title = stringResource(R.string.ab_export_file),
                subtitle = stringResource(R.string.ab_export_file_sub),
            ) {
                onDismiss()
                exportToFile()
            }
            if (connected) {
                ActionSheetRow(
                    icon = Icons.Default.CloudDownload,
                    title = stringResource(R.string.pnc_import),
                    subtitle = stringResource(R.string.ab_nc_import_sub),
                ) {
                    onDismiss()
                    showNextcloudImporter = true
                }
                ActionSheetRow(
                    icon = Icons.Default.CloudUpload,
                    title = stringResource(R.string.pnc_export),
                    subtitle = stringResource(R.string.ab_nc_export_sub),
                ) {
                    onDismiss()
                    exportToNextcloud()
                }
            } else {
                Text(
                    stringResource(R.string.ab_nc_connect_hint),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    if (showNextcloudImporter && nextcloud != null) {
        NextcloudFileSelectDialog(
            service = nextcloud,
            allowedExtensions = setOf("json"),
            onDismiss = { showNextcloudImporter = false },
            onPick = { file ->
                showNextcloudImporter = false
                scope.launch {
                    try {
                        runImport(nextcloud.downloadFile(file.path, maxBytes = 50_000_000L))
                    } catch (e: Exception) {
                        IosToasts.error(
                            context.getString(R.string.pnc_import_failed, com.kachat.app.util.UserFacingError.message(e, "Please try again"))
                        )
                    }
                }
            },
        )
    }
}

/** The search field the Address Book and its picker keep at the top, always visible. */
@Composable
private fun AddressBookSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(22.dp)),
        placeholder = { Text(stringResource(R.string.ab_search_prompt), color = colors.textSecondary) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

/** "No saved addresses", what the book is for, and [action]. */
@Composable
private fun AddressBookEmptyState(
    body: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
    iconTint: Color = KaspaTeal,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.Book, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
        Text(stringResource(R.string.no_saved_addresses), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(body, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/**
 * An Address Book picture: the photo you assigned to the entry, else exactly the avatar the
 * address set on its own profile, else the person glyph. Never a photo carried for a chat contact
 * (iOS `AddressBookAvatar`).
 */
@Composable
fun AddressBookAvatar(
    address: String,
    size: Dp = 44.dp,
    /** The editor's not-yet-saved choice: a new photo... */
    pendingBitmap: android.graphics.Bitmap? = null,
    /** ...or "removed". Neither: what is saved. */
    pendingRemoved: Boolean = false,
) {
    val book = AddressBookManager.shared
    val version = book?.photoVersion ?: 0
    val saved = remember(address, version) { book?.photo(address) }
    val assigned = when {
        pendingBitmap != null -> pendingBitmap
        pendingRemoved -> null
        else -> saved
    }
    if (assigned != null) {
        androidx.compose.foundation.Image(
            bitmap = assigned.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        // Exactly the avatar the address set on its own profile - no backup photo of the chat
        // contact (iOS `includeBackupPhoto: false`).
        ContactAvatar(imageUrl = null, fallbackText = "", size = size, address = address)
    }
}

/** One saved address in a list: avatar, name, short address. */
@Composable
fun AddressBookRow(entry: AddressBookEntry, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(modifier = modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AddressBookAvatar(entry.address, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                KaspaAddress.shortDisplay(entry.address),
                color = colors.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/** A row in one of the detail page's sections: an icon and a label in [tint]. */
@Composable
private fun AddressBookActionRow(icon: ImageVector, label: String, tint: Color = KaspaTeal, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (enabled) tint else LocalAppColors.current.textTertiary
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A saved address: send to it, message it, copy or share it, edit or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookEntryDetail(
    address: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    walletViewModel: WalletViewModel,
) {
    BackHandler(onBack = onBack)
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val entries = rememberAddressBookEntries()
    val entry = remember(entries, address) { AddressBookManager.shared?.entry(address) }
    val walletAddress by walletViewModel.address.collectAsState()
    val balanceSompi by walletViewModel.balanceSompi.collectAsState()
    val isOwnAddress = AddressBookManager.normalize(walletAddress.orEmpty()) == AddressBookManager.normalize(address)
    // Why this entry can't be paid or messaged here: it's the other network's address (saved
    // before IOS-063, or restored from a backup; iOS 218dc42).
    val otherNetwork = KaspaAddress.otherNetworkMessageRes(address)?.let { stringResource(it) }
    var showEdit by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(entry?.name.orEmpty(), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = KaspaTeal) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        if (entry == null) {
            // Deleted (here or by a backup restore) while open.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ab_not_in_book), color = colors.textSecondary)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SettingsSection(title = null) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AddressBookAvatar(entry.address, size = 76.dp)
                    Text(entry.name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, textAlign = TextAlign.Center)
                    if (entry.note.isNotEmpty()) {
                        Text(entry.note, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.address)) {
                SelectionContainer {
                    Text(
                        entry.address,
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                SettingsDivider()
                AddressBookActionRow(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    stringResource(if (copied) R.string.ab_copied else R.string.copy_address),
                ) {
                    clipboard.setText(AnnotatedString(entry.address))
                    copied = true
                }
                SettingsDivider()
                AddressBookActionRow(Icons.Default.Share, stringResource(R.string.ab_share_address)) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.address)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }

            Column {
                SettingsSection(title = null) {
                    AddressBookActionRow(
                        Icons.Default.Send, stringResource(R.string.send_kas_title),
                        enabled = walletAddress != null && otherNetwork == null,
                    ) {
                        showSend = true
                    }
                    if (!isOwnAddress) {
                        SettingsDivider()
                        AddressBookActionRow(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.message), enabled = otherNetwork == null) {
                            if (KaspaAddress.isValidOnActiveNetwork(entry.address)) onOpenChat(entry.address)
                        }
                    }
                }
                if (otherNetwork != null) SettingsFooter(otherNetwork)
            }

            SettingsSection(title = null) {
                AddressBookActionRow(Icons.Default.Edit, stringResource(R.string.ab_edit)) { showEdit = true }
                SettingsDivider()
                AddressBookActionRow(Icons.Default.Delete, stringResource(R.string.ab_delete_from), tint = colors.danger) {
                    confirmDelete = true
                }
            }
        }
    }

    if (showEdit && entry != null) {
        AddressBookEntryEditor(
            address = entry.address,
            onDone = { result -> if (result == AddressBookEditResult.REMOVED) onBack() },
            onDismiss = { showEdit = false },
        )
    }
    if (showSend && entry != null) {
        IosFullSheet(onDismissed = { showSend = false }, swipeToDismiss = false) { close ->
            SpendingAddressSendFlow(
                fromAddress = walletAddress.orEmpty(),
                balanceSompi = balanceSompi,
                title = "Send Kaspa",
                viewModel = walletViewModel,
                onDone = close,
                presentedAsSheet = true,
                prefillAddress = entry.address,
            )
        }
    }
    if (confirmDelete && entry != null) {
        com.kachat.app.ui.theme.IosAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ab_delete_confirm, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    AddressBookManager.shared?.remove(entry.address)
                    onBack()
                }) { Text(stringResource(R.string.delete), color = colors.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel), color = KaspaTeal) }
            },
        )
    }
}

enum class AddressBookEditResult { SAVED, REMOVED }

/** A form section's text field: no box of its own, the section's card is the box. */
@Composable
private fun AddressBookFormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    monospace: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    val colors = LocalAppColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.textTertiary, fontFamily = if (monospace) FontFamily.Monospace else null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrect = !monospace,
            keyboardType = if (monospace) KeyboardType.Uri else KeyboardType.Text,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = KaspaTeal,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Add or edit one entry. With an [address] it edits that address's entry (or adds it, with
 * [suggestedName] filled in - User Info's "Add to Address Book"); without one the address is
 * typed, pasted or scanned (iOS `AddressBookEntryEditor`).
 */
@Composable
fun AddressBookEntryEditor(
    address: String?,
    suggestedName: String = "",
    onDone: (AddressBookEditResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val book = AddressBookManager.shared ?: return
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val entries = rememberAddressBookEntries()
    var addressInput by remember { mutableStateOf("") }
    // A typed domain: what it resolved to (.kachat first) and every service's answer (iOS 6ac48a7).
    val resolver: AddressResolutionViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    var resolvedAddress by remember { mutableStateOf<String?>(null) }
    var resolvedName by remember { mutableStateOf<String?>(null) }
    var nameResolutions by remember { mutableStateOf<List<com.kachat.app.services.NameResolution>>(emptyList()) }
    var selectedTld by remember { mutableStateOf<com.kachat.app.services.NameServiceTLD?>(null) }
    var isResolving by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    // The address being saved: the one this editor was opened for, else what the typed domain
    // resolved to, else what was typed.
    val enteredAddress = address ?: resolvedAddress ?: addressInput
    val effectiveAddress = AddressBookManager.normalize(enteredAddress)
    val existing = remember(entries, effectiveAddress) { book.entry(effectiveAddress) }
    // Filled once, from the saved entry or the suggestion.
    val initial = remember { book.entry(address) }
    var name by remember { mutableStateOf(initial?.name ?: suggestedName) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    // null: keep what is saved; a photo: use it; removed: remove the photo.
    var pendingBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pendingJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var pendingRemoved by remember { mutableStateOf(false) }
    val showsAssignedPhoto = when {
        pendingBitmap != null -> true
        pendingRemoved -> false
        else -> book.photoVersion.let { book.hasPhoto(enteredAddress) }
    }
    val context = LocalContext.current
    val selectResolution: (com.kachat.app.services.NameResolution) -> Unit = { resolution ->
        resolution.address?.let {
            resolvedAddress = it
            resolvedName = resolution.display
            selectedTld = resolution.tld
            lookupError = null
            // the name they're known by, unless one was typed already
            if (name.isBlank()) name = resolution.display
        }
    }
    // A typed name resolves on every service, .kachat first; the entry saves the address.
    LaunchedEffect(addressInput) {
        resolvedAddress = null
        resolvedName = null
        nameResolutions = emptyList()
        selectedTld = null
        lookupError = null
        isResolving = false
        val typed = addressInput.trim()
        if (address != null || typed.isEmpty() || !com.kachat.app.services.NameServicesClient.looksLikeName(typed)) return@LaunchedEffect
        isResolving = true
        kotlinx.coroutines.delay(300)
        val results = resolver.resolveEverywhere(typed)
        nameResolutions = results
        isResolving = false
        val primary = com.kachat.app.services.NameServicesClient.primary(results, typed)
        if (primary != null) selectResolution(primary) else lookupError = noDomainFoundMessage(context, typed)
    }
    val scope = rememberCoroutineScope()
    val photoFailed = stringResource(R.string.ab_photo_failed)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { AddressBookManager.preparedPhoto(context, uri) }
            val shown = jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (jpeg == null || shown == null) {
                error = photoFailed
                return@launch
            }
            pendingBitmap = shown
            pendingJpeg = jpeg
            pendingRemoved = false
        }
    }
    val errNoWallet = stringResource(R.string.ab_err_no_wallet)
    val errEmptyName = stringResource(R.string.ab_err_empty_name)
    val errInvalid = stringResource(R.string.ab_err_invalid_address)

    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = true, grouped = true) { close ->
        BackHandler(onBack = close)
        Column(Modifier.fillMaxSize()) {
            IosSheetNavBar(
                title = stringResource(if (existing == null) R.string.ab_add_to_address_book else R.string.ab_edit_address),
                leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = close) },
                trailing = {
                    IosBarTextButton(
                        stringResource(R.string.save),
                        bold = true,
                        enabled = name.isNotBlank() && effectiveAddress.isNotEmpty(),
                        onClick = {
                            try {
                                val photo = when {
                                    pendingJpeg != null -> AddressBookManager.PhotoChange.Set(pendingJpeg!!)
                                    pendingRemoved -> AddressBookManager.PhotoChange.Removed
                                    else -> AddressBookManager.PhotoChange.Unchanged
                                }
                                book.save(effectiveAddress, name, note, photo)
                                onDone(AddressBookEditResult.SAVED)
                                close()
                            } catch (e: AddressBookManager.SaveException) {
                                error = when (e.messageKey) {
                                    AddressBookManager.SaveError.NO_WALLET -> errNoWallet
                                    AddressBookManager.SaveError.EMPTY_NAME -> errEmptyName
                                    AddressBookManager.SaveError.INVALID_ADDRESS -> errInvalid
                                    // the usual reason (iOS 218dc42, IOS-063)
                                    AddressBookManager.SaveError.OTHER_NETWORK ->
                                        KaspaAddress.otherNetworkMessageRes(effectiveAddress)?.let { context.getString(it) } ?: errInvalid
                                }
                            }
                        },
                    )
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Column {
                    SettingsSection(title = null) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AddressBookAvatar(effectiveAddress, size = 84.dp, pendingBitmap = pendingBitmap, pendingRemoved = pendingRemoved)
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                CreateChatActionItem(
                                    Icons.Default.Photo,
                                    stringResource(if (showsAssignedPhoto) R.string.ab_change_photo else R.string.choose_photo),
                                ) {
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                if (showsAssignedPhoto) {
                                    Row(
                                        modifier = Modifier.clickable {
                                            pendingRemoved = true
                                            pendingBitmap = null
                                            pendingJpeg = null
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = colors.danger, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.ab_remove_photo), color = colors.danger, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    SettingsFooter(stringResource(R.string.ab_photo_footer))
                }

                SettingsSection(title = stringResource(R.string.name)) {
                    AddressBookFormField(name, { name = it }, stringResource(R.string.name), capitalization = KeyboardCapitalization.Words)
                }

                SettingsSection(title = stringResource(R.string.address)) {
                    if (address != null) {
                        Text(
                            address,
                            color = colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    } else {
                        AddressBookFormField(
                            addressInput, { addressInput = it }, stringResource(R.string.kaspa_qr_or_domain),
                            singleLine = false, maxLines = 3, monospace = true,
                            capitalization = KeyboardCapitalization.None,
                        )
                        if (isResolving || resolvedAddress != null || lookupError != null || nameResolutions.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                                when {
                                    isResolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        com.kachat.app.ui.theme.IosActivityIndicator(modifier = Modifier.size(14.dp), color = colors.textSecondary, strokeWidth = 2.dp)
                                        Text(stringResource(R.string.looking_up_domain), color = colors.textSecondary, fontSize = 12.sp)
                                    }
                                    resolvedAddress != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(14.dp))
                                            Text(stringResource(R.string.resolved_name, resolvedName ?: ""), color = colors.success, fontSize = 12.sp)
                                        }
                                        MiddleEllipsisText(
                                            resolvedAddress ?: "",
                                            color = colors.textSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    lookupError != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.danger, modifier = Modifier.size(14.dp))
                                        Text(lookupError ?: "", color = colors.danger, fontSize = 12.sp)
                                    }
                                }
                                if (!isResolving) {
                                    OtherDomainsDropdown(nameResolutions, selectedTld, selectResolution)
                                }
                            }
                        }
                        SettingsDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CreateChatActionItem(Icons.Default.ContentPaste, stringResource(R.string.paste)) {
                                clipboard.getText()?.text?.let { addressInput = it.trim() }
                            }
                            Spacer(Modifier.weight(1f))
                            CreateChatActionItem(Icons.Default.QrCodeScanner, stringResource(R.string.scan_qr)) { showScanner = true }
                        }
                    }
                }

                Column {
                    SettingsSection(title = null) {
                        AddressBookFormField(
                            note, { note = it }, stringResource(R.string.ab_note_optional),
                            singleLine = false, minLines = 1, maxLines = 4,
                        )
                    }
                    SettingsFooter(stringResource(R.string.ab_editor_footer))
                }

                error?.let { message ->
                    SettingsSection(title = null) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.danger, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(message, color = colors.danger, fontSize = 13.sp)
                        }
                    }
                }

                if (address != null && existing != null) {
                    SettingsSection(title = null) {
                        AddressBookActionRow(Icons.Default.Delete, stringResource(R.string.ab_remove_from), tint = colors.danger) {
                            book.remove(effectiveAddress)
                            onDone(AddressBookEditResult.REMOVED)
                            close()
                        }
                    }
                }
            }
        }
        if (showScanner) {
            QrScannerSheet(
                onScanned = { code ->
                    var scanned = code.trim()
                    val q = scanned.indexOf('?')
                    if (q >= 0) scanned = scanned.substring(0, q)
                    addressInput = scanned
                    showScanner = false
                },
                onDismiss = { showScanner = false },
            )
        }
    }
}

/**
 * Pick from the Address Book, full screen everywhere (iOS 1be4f6e's full-screen cover; here a
 * full-height sheet only Cancel closes): one address (New Chat, the Send screens) - [onSelect],
 * tapping a row picks it and closes - or, with [onDoneMultiple], several at once with ticks (New
 * Group), starting from [preselected]; "Add (n)" hands back every ticked entry. [excluding] is
 * never offered (your own address, in a group). iOS `AddressBookPickerSheet`.
 */
@Composable
fun AddressBookPickerSheet(
    onDismiss: () -> Unit,
    onSelect: ((AddressBookEntry) -> Unit)? = null,
    preselected: Set<String> = emptySet(),
    excluding: String? = null,
    onDoneMultiple: ((List<AddressBookEntry>) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    val entries = rememberAddressBookEntries()
    val isMultiple = onDoneMultiple != null
    val excluded = excluding?.let(AddressBookManager::normalize)
    var search by remember { mutableStateOf("") }
    var ticked by remember {
        val saved = entries.map { AddressBookManager.normalize(it.address) }.toSet()
        mutableStateOf(preselected.map(AddressBookManager::normalize).toSet().intersect(saved))
    }
    val shown = remember(entries, search, excluded) {
        (AddressBookManager.shared?.search(search) ?: emptyList()).filter { AddressBookManager.normalize(it.address) != excluded }
    }

    IosFullSheet(onDismissed = onDismiss, swipeToDismiss = false) { close ->
        BackHandler(onBack = close)
        Column(Modifier.fillMaxSize()) {
            IosSheetNavBar(
                title = stringResource(R.string.ab_address_book),
                leading = { IosBarTextButton(stringResource(R.string.cancel), onClick = close) },
                trailing = {
                    if (isMultiple) {
                        IosBarTextButton(
                            if (ticked.isEmpty()) stringResource(R.string.done) else stringResource(R.string.ab_add_count, ticked.size),
                            bold = true,
                            onClick = {
                                onDoneMultiple?.invoke(entries.filter {
                                    AddressBookManager.normalize(it.address) in ticked && KaspaAddress.isValidOnActiveNetwork(it.address)
                                })
                                close()
                            },
                        )
                    }
                },
            )
            if (entries.isEmpty()) {
                AddressBookEmptyState(
                    body = stringResource(R.string.ab_picker_empty_body),
                    iconSize = 36.dp,
                    iconTint = colors.textSecondary,
                )
            } else {
                // Always showing: hidden until you pulled the list down, it read as pull-to-refresh.
                AddressBookSearchField(search, { search = it }, Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                ) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.surface)) {
                            shown.forEachIndexed { index, entry ->
                                if (index > 0) SettingsDivider(inset = 66.dp)
                                val key = AddressBookManager.normalize(entry.address)
                                // the other network's address can't be chatted with or added to a group (iOS 218dc42, IOS-063)
                                val otherNetwork = KaspaAddress.otherNetworkMessageRes(entry.address)?.let { stringResource(it) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (otherNetwork != null) 0.5f else 1f)
                                        .clickable(enabled = otherNetwork == null) {
                                            if (!KaspaAddress.isValidOnActiveNetwork(entry.address)) return@clickable
                                            if (isMultiple) {
                                                ticked = if (key in ticked) ticked - key else ticked + key
                                            } else {
                                                onSelect?.invoke(entry)
                                                close()
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        AddressBookRow(entry)
                                        if (otherNetwork != null) {
                                            Text(otherNetwork, color = colors.warning, fontSize = 12.sp)
                                        }
                                    }
                                    if (isMultiple) {
                                        Spacer(Modifier.width(8.dp))
                                        val on = key in ticked
                                        Icon(
                                            if (on) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                            contentDescription = null,
                                            tint = if (on) KaspaTeal else colors.textSecondary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
