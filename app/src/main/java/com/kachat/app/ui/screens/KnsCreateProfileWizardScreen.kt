package com.kachat.app.ui.screens

import com.kachat.app.R
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.services.KnsProfileFields
import com.kachat.app.services.WalletService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch
import com.kachat.app.util.showAddressCopiedToast
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Refresh

private enum class KnsWizardStep {
    /** The fork the whole wizard hangs off - see [KnsHaveDomainStep]. */
    HAVE_DOMAIN,
    CHECKING_FUNDS,
    NEEDS_FUNDING,
    DOMAIN,
    TRANSFER_EXISTING_DOMAIN,
    /** Which of the domains now on this address to build the profile around. */
    PICK_DOMAIN,
    DOMAIN_CONFIRMED,
    BANNER,
    AVATAR,
    DETAILS,
    FINISHED;

    /** Where Back goes. Null means this step has no previous - the first screen, and the end. */
    val previous: KnsWizardStep?
        get() = when (this) {
            HAVE_DOMAIN, CHECKING_FUNDS, FINISHED -> null
            NEEDS_FUNDING, DOMAIN, TRANSFER_EXISTING_DOMAIN -> HAVE_DOMAIN
            PICK_DOMAIN -> TRANSFER_EXISTING_DOMAIN
            DOMAIN_CONFIRMED -> PICK_DOMAIN
            BANNER -> DOMAIN_CONFIRMED
            AVATAR -> BANNER
            DETAILS -> AVATAR
        }
}

/** Fixed UX gate, not derived from live KNS fee tiers - deliberately generous relative to a
 *  domain's actual commit+reveal cost so the flow doesn't fail partway through from insufficient
 *  funds once the user's already invested time in it. */
private const val MINIMUM_FUNDING_BALANCE_KAS = 50.0

/**
 * Guided, step-by-step flow for a wallet that doesn't own a KNS domain yet: fund-check gate ->
 * register a domain (real on-chain commit/reveal inscription) -> optional banner -> optional
 * avatar -> optional bio/social details -> finished. Every write here goes through the same
 * [WalletViewModel] functions the existing (edit-an-existing-profile) `EditKnsProfileScreen` uses
 * (`inscribeDomain`, `saveKnsProfile`) - this screen is purely new orchestration/UX around
 * already-working inscription machinery, not a new protocol implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnsCreateProfileWizardScreen(viewModel: WalletViewModel, onFinished: () -> Unit) {
    var step by remember { mutableStateOf(KnsWizardStep.HAVE_DOMAIN) }
    var domainName by remember { mutableStateOf<String?>(null) }
    var currentBalanceKas by remember { mutableStateOf(0.0) }
    // Domains found on the chatting address, refreshed by the transfer screen's Refresh button.
    var foundDomains by remember { mutableStateOf(emptyList<com.kachat.app.services.KnsAsset>()) }
    var isScanningDomains by remember { mutableStateOf(false) }

    val chattingAddress by viewModel.address.collectAsState()
    val activeProfileDomainName by viewModel.activeProfileDomainName.collectAsState()
    val knsProfile by viewModel.knsProfile.collectAsState()
    val scope = rememberCoroutineScope()

    suspend fun checkFunding() {
        step = KnsWizardStep.CHECKING_FUNDS
        viewModel.refreshBalanceAndAwait()
        val kas = viewModel.balanceSompi.value / 100_000_000.0
        currentBalanceKas = kas
        step = if (kas >= MINIMUM_FUNDING_BALANCE_KAS) KnsWizardStep.DOMAIN else KnsWizardStep.NEEDS_FUNDING
    }

    // Re-reads the domains sitting on the chatting address, bypassing the cache: the whole
    // point of the Refresh button is to see a transfer that just landed.
    suspend fun scanForDomains() {
        isScanningDomains = true
        try {
            viewModel.refreshOwnedDomainsAndAwait()
            foundDomains = viewModel.ownedDomainAssets.value
        } finally {
            isScanningDomains = false
        }
    }

    // No funding check on open: only the "I need one" branch needs funds, so that check moved
    // into it. Someone bringing a domain they already own was being gated on a balance that
    // transferring one never touches.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_kns_profile), color = LocalAppColors.current.textPrimary) },
                navigationIcon = {
                    // Close only. Step navigation is the Previous/Next bar at the bottom of every
                    // step, where a wizard's navigation belongs.
                    IconButton(onClick = onFinished) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = LocalAppColors.current.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        containerColor = LocalAppColors.current.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    KnsWizardStep.HAVE_DOMAIN -> {
                        KnsHaveDomainStep(
                            onYes = {
                                step = KnsWizardStep.TRANSFER_EXISTING_DOMAIN
                                scope.launch { scanForDomains() }
                            },
                            onNo = { scope.launch { checkFunding() } },
                        )
                    }
                    KnsWizardStep.PICK_DOMAIN -> {
                        KnsPickDomainStep(
                            domains = foundDomains,
                            onPick = { domain ->
                                domainName = domain.asset
                                step = KnsWizardStep.DOMAIN_CONFIRMED
                            },
                        )
                    }
                    KnsWizardStep.CHECKING_FUNDS -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = KaspaTeal)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.checking_your_chatting_address_balance), color = LocalAppColors.current.textSecondary)
                        }
                    }
                    KnsWizardStep.NEEDS_FUNDING -> {
                        KnsFundingGateStep(
                            balanceKas = currentBalanceKas,
                            chattingAddress = chattingAddress,
                            existingDomain = activeProfileDomainName,
                            onCheckAgain = { scope.launch { checkFunding() } },
                            // Joins the Yes branch rather than the creation screen: someone with a
                            // domain elsewhere needs the transfer flow, not a purchase they cannot
                            // afford - which is the very screen they are looking at.
                            onAlreadyHaveDomain = {
                                step = KnsWizardStep.TRANSFER_EXISTING_DOMAIN
                                scope.launch { scanForDomains() }
                            }
                        )
                    }
                    KnsWizardStep.DOMAIN -> {
                        KnsDomainCreationStep(
                            viewModel = viewModel,
                            existingDomain = activeProfileDomainName,
                            onSkipped = { domain ->
                                domainName = domain
                                step = KnsWizardStep.DOMAIN_CONFIRMED
                            },
                            onCreated = { result ->
                                domainName = result.domain
                                step = KnsWizardStep.DOMAIN_CONFIRMED
                            },
                            // Someone can answer No and only then remember they own one, so this
                            // joins the Yes branch rather than being a dead end.
                            onTransferExisting = {
                                step = KnsWizardStep.TRANSFER_EXISTING_DOMAIN
                                scope.launch { scanForDomains() }
                            }
                        )
                    }
                    KnsWizardStep.TRANSFER_EXISTING_DOMAIN -> {
                        KnsTransferExistingDomainStep(
                            chattingAddress = chattingAddress,
                            foundDomainCount = foundDomains.size,
                            isScanning = isScanningDomains,
                            onRefresh = { scope.launch { scanForDomains() } }
                        )
                    }
                    KnsWizardStep.DOMAIN_CONFIRMED -> {
                        // No button of its own - the bar below carries Next, so there is one place
                        // to go forward rather than two.
                        KnsSimpleConfirmStep(
                            message = "You are now known as ${domainName.orEmpty()}",
                            buttonLabel = null,
                            onContinue = {}
                        )
                    }
                    KnsWizardStep.BANNER -> {
                        KnsImageInscribeStep(
                            viewModel = viewModel,
                            title = "Let's set up a profile banner",
                            subtitle = "Add a banner image to your profile, or skip for now.",
                            isBanner = true,
                            existingImageUrl = knsProfile?.bannerUrl,
                            onDone = { step = KnsWizardStep.AVATAR }
                        )
                    }
                    KnsWizardStep.AVATAR -> {
                        KnsImageInscribeStep(
                            viewModel = viewModel,
                            title = "Let's inscribe your avatar photo",
                            subtitle = "Add a profile photo, or skip for now.",
                            isBanner = false,
                            existingImageUrl = knsProfile?.avatarUrl,
                            onDone = { step = KnsWizardStep.DETAILS }
                        )
                    }
                    KnsWizardStep.DETAILS -> {
                        KnsDetailsStep(viewModel = viewModel, existingProfile = knsProfile, onDone = { step = KnsWizardStep.FINISHED })
                    }
                    KnsWizardStep.FINISHED -> {
                        KnsSimpleConfirmStep(
                            message = "You have now finished your KNS profile creation!",
                            buttonLabel = "Done",
                            onContinue = onFinished
                        )
                    }
                }
            }

            // One bar, one place, on every step. `forwardAction` is null where the step advances
            // by choosing something rather than by a Next - the button renders disabled there so
            // it never moves between steps.
            val forwardAction: (() -> Unit)? = when (step) {
                KnsWizardStep.TRANSFER_EXISTING_DOMAIN -> ({ step = KnsWizardStep.PICK_DOMAIN })
                KnsWizardStep.DOMAIN_CONFIRMED -> ({ step = KnsWizardStep.BANNER })
                KnsWizardStep.BANNER -> ({ step = KnsWizardStep.AVATAR })
                KnsWizardStep.AVATAR -> ({ step = KnsWizardStep.DETAILS })
                KnsWizardStep.DETAILS -> ({ step = KnsWizardStep.FINISHED })
                else -> null
            }
            if (step != KnsWizardStep.FINISHED && step != KnsWizardStep.CHECKING_FUNDS) {
                KnsWizardBottomBar(
                    onBack = step.previous?.let { previous -> { step = previous } },
                    onNext = forwardAction,
                )
            }
        }
    }
}

@Composable
private fun KnsFundingGateStep(
    balanceKas: Double,
    chattingAddress: String?,
    existingDomain: String?,
    onCheckAgain: () -> Unit,
    onAlreadyHaveDomain: () -> Unit
) {
    var showQr by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            "Please fund your chatting address with at least ${formatKasWizard(MINIMUM_FUNDING_BALANCE_KAS)} Kaspa to continue.",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.current_balance), style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.textSecondary)
        Text(
            "${formatKasWizard(balanceKas)} KAS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary
        )
        if (chattingAddress != null) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.chatting_address_2), style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.textSecondary)
            Text(
                chattingAddress,
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(chattingAddress))
                        showAddressCopiedToast(context, chattingAddress)
                    }
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            // Reuses the same full white-background QR overlay every other "receive" surface in
            // the app uses (Profile screen's Accept Kaspa/Chatting Address buttons), so someone
            // with a second device - or helping in person - can scan and send right from here.
            TextButton(onClick = { showQr = true }) {
                Icon(Icons.Default.QrCode, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.show_qr_code), color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onCheckAgain,
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.check_again), color = Color.Black, fontWeight = FontWeight.Bold)
        }
        // Escape hatch for someone re-entering via "Setup Guide" whose domain is already
        // registered - the funding gate exists to protect a *new* domain inscription, which they
        // don't need, so this skips straight past it to the domain step (which itself offers
        // "Skip - Continue with <existing domain>" once there). Only shown when a domain is
        // already known: the fresh "Create KNS Profile" entry point never has one, since that
        // button itself only appears when there's no domain yet.
        if (existingDomain != null) {
            TextButton(onClick = onAlreadyHaveDomain) {
                Text(stringResource(R.string.it_s_ok_i_already_have), color = LocalAppColors.current.textSecondary)
            }
        }
    }

    if (showQr && chattingAddress != null) {
        QrCodeOverlay(
            value = chattingAddress,
            onDismiss = { showQr = false },
            message = "Send around ${formatKasWizard(MINIMUM_FUNDING_BALANCE_KAS)} Kaspa to this address to have enough for full KNS profile creation and chatting for a while",
            borderColor = KaspaTeal,
            borderWidth = 4.dp
        )
    }
}

/** Reached from the domain step's "I already have a domain somewhere else" button - for a domain
 *  already registered on another wallet/service, there's nothing this wizard itself can do here
 *  (no inscribe, no polling for a transfer that happens entirely off-app), so this just shows the
 *  address to transfer to, then continues the wizard on to the banner/avatar/details steps rather
 *  than ending it - the domain being handled off-app doesn't mean the rest of the profile isn't
 *  still worth setting up. */
@Composable
private fun KnsTransferExistingDomainStep(
    chattingAddress: String?,
    foundDomainCount: Int,
    isScanning: Boolean,
    onRefresh: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Send your domain here",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.transfer_your_domain_to_this_address),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAppColors.current.textSecondary,
            textAlign = TextAlign.Center
        )
        if (chattingAddress != null) {
            Spacer(Modifier.height(18.dp))
            // The QR is the point of this screen, so it is here at a size you can scan from
            // another device rather than behind a "Show QR Code" button.
            Image(
                painter = rememberQrBitmapPainter(chattingAddress),
                contentDescription = null,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.chatting_address_2), style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.textSecondary)
            Text(
                chattingAddress,
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(chattingAddress))
                        showAddressCopiedToast(context, chattingAddress)
                    }
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(10.dp))
            // A transfer lands whenever it lands - this is how you find out without leaving the
            // wizard and coming back.
            TextButton(onClick = onRefresh, enabled = !isScanning) {
                if (isScanning) {
                    CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isScanning) "Checking..." else "Refresh", color = KaspaTeal, fontWeight = FontWeight.Bold)
            }
            Text(
                if (foundDomainCount == 0) {
                    "No domains on this address yet."
                } else {
                    "$foundDomainCount domain${if (foundDomainCount == 1) "" else "s"} found on this address."
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textSecondary,
            )
        }
    }


}

/** Mirrors `KnsDomainsScreen`'s inscribe `AlertDialog` availability-check/fee/submit logic
 *  (Screens.kt), restyled as a full-screen wizard step instead of a dialog. */
@Composable
private fun KnsDomainCreationStep(
    viewModel: WalletViewModel,
    existingDomain: String?,
    onSkipped: (String) -> Unit,
    onCreated: (WalletService.DomainInscribeResult) -> Unit,
    onTransferExisting: () -> Unit
) {
    var domainLabelInput by remember { mutableStateOf("") }
    val domainPreview by viewModel.domainPreview.collectAsState()
    val knsInscribeState by viewModel.knsInscribeState.collectAsState()

    LaunchedEffect(knsInscribeState.status) {
        if (knsInscribeState.status == WalletViewModel.KnsInscribeUiStatus.SUCCESS) {
            knsInscribeState.result?.let(onCreated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetKnsInscribeState()
            viewModel.clearDomainPreview()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            stringResource(R.string.first_let_s_create_your_identity),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary
        )
        TextButton(onClick = onTransferExisting, contentPadding = PaddingValues(0.dp)) {
            Text(
                stringResource(R.string.i_already_have_a_domain_somewhere),
                color = KaspaTeal,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(20.dp))

        when (knsInscribeState.status) {
            WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                OutlinedTextField(
                    value = domainLabelInput,
                    onValueChange = {
                        domainLabelInput = it
                        viewModel.checkDomainLabel(it)
                    },
                    label = { Text(stringResource(R.string.domain_name)) },
                    suffix = { Text(stringResource(R.string.kas)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                domainPreview?.let { preview ->
                    when {
                        preview.checking -> Text(stringResource(R.string.checking_availability), color = LocalAppColors.current.textSecondary)
                        preview.errorMessage != null -> Text(preview.errorMessage, color = Color(0xFFFF3B30))
                        preview.available == false -> Text("${preview.label}.kas is not available", color = Color(0xFFFF3B30))
                        preview.available == true && preview.isReserved -> {
                            Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.reserved_domain_no_registration_fee_only),
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        preview.available == true -> {
                            Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            val revealKas = preview.revealKas ?: 0.0
                            Text(
                                "Registration fee: ${"%.2f".format(revealKas)} KAS",
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.inscribeDomain(domainPreview?.label ?: domainLabelInput) },
                    enabled = domainPreview?.available == true,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.inscribe_domain), color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (existingDomain != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { onSkipped(existingDomain) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Skip - Continue with $existingDomain", color = LocalAppColors.current.textSecondary)
                    }
                }
            }
            WalletViewModel.KnsInscribeUiStatus.CHECKING_AVAILABILITY -> KnsInscribeProgressRow(stringResource(R.string.checking_availability))
            WalletViewModel.KnsInscribeUiStatus.FETCHING_FEE -> KnsInscribeProgressRow(stringResource(R.string.calculating_fee))
            WalletViewModel.KnsInscribeUiStatus.SUBMITTING_COMMIT -> KnsInscribeProgressRow(stringResource(R.string.submitting_commit_transaction_this_could_take))
            WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL -> KnsInscribeProgressRow(stringResource(R.string.submitting_reveal_transaction_this_could_take))
            WalletViewModel.KnsInscribeUiStatus.VERIFYING -> KnsInscribeProgressRow(stringResource(R.string.verifying_on_chain_this_could_take))
            WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                Text(knsInscribeState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.resetKnsInscribeState() },
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.try_again), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            WalletViewModel.KnsInscribeUiStatus.SUCCESS -> {}
        }
    }
}

@Composable
private fun KnsInscribeProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = LocalAppColors.current.textPrimary)
    }
}

/** Shared shape for both the banner and avatar steps - pick a photo, inscribe it (upload + write
 *  the `avatarUrl`/`bannerUrl` profile field via commit/reveal, reusing `saveKnsProfile`'s
 *  existing staged-pending-image path), or skip. [existingImageUrl], if given (re-entering on a
 *  profile that already has one), is shown as the starting preview so the user is reviewing/
 *  replacing rather than starting blank - it's display-only, the "Inscribe" button stays gated on
 *  a *new* pending pick, so just viewing it without changing it never triggers a resubmission. */
@Composable
private fun KnsImageInscribeStep(
    viewModel: WalletViewModel,
    title: String,
    subtitle: String,
    isBanner: Boolean,
    existingImageUrl: String?,
    onDone: () -> Unit
) {
    val pendingAvatarUri by viewModel.pendingAvatarUri.collectAsState()
    val pendingBannerUri by viewModel.pendingBannerUri.collectAsState()
    val editProfileState by viewModel.editProfileState.collectAsState()
    val pendingUri = if (isBanner) pendingBannerUri else pendingAvatarUri
    val previewModel: Any? = pendingUri ?: existingImageUrl?.takeIf { it.isNotBlank() }
    val isSubmitting = editProfileState.step != WalletViewModel.EditProfileStep.IDLE &&
        editProfileState.step != WalletViewModel.EditProfileStep.SUCCESS &&
        editProfileState.step != WalletViewModel.EditProfileStep.PARTIAL_FAILURE &&
        editProfileState.step != WalletViewModel.EditProfileStep.FAILED

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (isBanner) viewModel.setPendingBanner(uri) else viewModel.setPendingAvatar(uri)
        }
    }

    // A single image upload never lands in PARTIAL_FAILURE (that's only possible with multiple
    // fields, like the details step's several text fields) - only SUCCESS should advance here.
    LaunchedEffect(editProfileState.step) {
        if (editProfileState.step == WalletViewModel.EditProfileStep.SUCCESS) {
            onDone()
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.resetEditProfileState() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))

        if (isBanner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LocalAppColors.current.surface)
                    .clickable(enabled = !isSubmitting) { picker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (previewModel != null) {
                    SubcomposeAsyncImage(
                        model = previewModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Photo, contentDescription = stringResource(R.string.choose_photo), tint = KaspaTeal, modifier = Modifier.size(32.dp))
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(LocalAppColors.current.surface)
                    .clickable(enabled = !isSubmitting) { picker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (previewModel != null) {
                    SubcomposeAsyncImage(
                        model = previewModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Icon(Icons.Default.Photo, contentDescription = stringResource(R.string.choose_photo), tint = KaspaTeal, modifier = Modifier.size(32.dp))
                }
            }
        }

        if (editProfileState.step == WalletViewModel.EditProfileStep.FAILED ||
            editProfileState.step == WalletViewModel.EditProfileStep.PARTIAL_FAILURE
        ) {
            Spacer(Modifier.height(12.dp))
            Text(editProfileState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
        }

        if (isSubmitting) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.this_could_take_a_few_minutes),
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { viewModel.saveKnsProfile(emptyMap()) },
            enabled = pendingUri != null && !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.inscribe), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun KnsDetailsStep(viewModel: WalletViewModel, existingProfile: KnsProfileFields?, onDone: () -> Unit) {
    var bio by remember { mutableStateOf(existingProfile?.bio.orEmpty()) }
    var website by remember { mutableStateOf(existingProfile?.website.orEmpty()) }
    var x by remember { mutableStateOf(existingProfile?.x.orEmpty()) }
    var telegram by remember { mutableStateOf(existingProfile?.telegram.orEmpty()) }
    var discord by remember { mutableStateOf(existingProfile?.discord.orEmpty()) }
    var github by remember { mutableStateOf(existingProfile?.github.orEmpty()) }
    var contactEmail by remember { mutableStateOf(existingProfile?.contactEmail.orEmpty()) }

    val editProfileState by viewModel.editProfileState.collectAsState()
    val isSubmitting = editProfileState.step != WalletViewModel.EditProfileStep.IDLE &&
        editProfileState.step != WalletViewModel.EditProfileStep.SUCCESS &&
        editProfileState.step != WalletViewModel.EditProfileStep.PARTIAL_FAILURE &&
        editProfileState.step != WalletViewModel.EditProfileStep.FAILED

    LaunchedEffect(editProfileState.step) {
        if (editProfileState.step == WalletViewModel.EditProfileStep.SUCCESS ||
            editProfileState.step == WalletViewModel.EditProfileStep.PARTIAL_FAILURE
        ) {
            onDone()
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.resetEditProfileState() }
    }

    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            stringResource(R.string.let_s_add_more_details_about),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary
        )
        Text(
            "You need at least ${formatKasWizard(COST_PER_FIELD_KAS)} KAS to fill in all fields.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalAppColors.current.textSecondary
        )
        Spacer(Modifier.height(16.dp))

        KnsDetailField(stringResource(R.string.bio), "bio", editProfileState, bio, singleLine = false) { bio = it }
        KnsDetailField(stringResource(R.string.website), "website", editProfileState, website) { website = it }
        KnsDetailField(stringResource(R.string.x_twitter), "x", editProfileState, x) { x = it }
        KnsDetailField(stringResource(R.string.telegram), "telegram", editProfileState, telegram) { telegram = it }
        KnsDetailField(stringResource(R.string.discord), "discord", editProfileState, discord) { discord = it }
        KnsDetailField(stringResource(R.string.github), "github", editProfileState, github) { github = it }
        KnsDetailField(stringResource(R.string.contact_email), "contactEmail", editProfileState, contactEmail) { contactEmail = it }

        if (editProfileState.step == WalletViewModel.EditProfileStep.FAILED) {
            Spacer(Modifier.height(8.dp))
            Text(editProfileState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
        }

        if (isSubmitting) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.this_could_take_a_few_minutes),
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textSecondary
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.saveKnsProfile(
                    mapOf(
                        "bio" to bio,
                        "website" to website,
                        "x" to x,
                        "telegram" to telegram,
                        "discord" to discord,
                        "github" to github,
                        "contactEmail" to contactEmail
                    )
                )
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.done), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Flat per-field commit cost - matches [WalletService]'s `PROFILE_COMMIT_SOMPI`, which every
 *  field/image write uses regardless of key. */
private const val COST_PER_FIELD_KAS = 2.0

@Composable
private fun KnsDetailField(
    label: String,
    fieldKey: String,
    editProfileState: WalletViewModel.EditProfileUiState,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    val isSubmittingThisField = editProfileState.step == WalletViewModel.EditProfileStep.SUBMITTING_FIELD &&
        editProfileState.currentFieldLabel == fieldKey
    val isDoneThisField = editProfileState.fieldResults.any { it.fieldKey == fieldKey && it.success }

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.textSecondary,
                modifier = Modifier.weight(1f)
            )
            when {
                isSubmittingThisField -> CircularProgressIndicator(
                    color = KaspaTeal,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                isDoneThisField -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.done),
                    tint = Color(0xFF4CD964),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = LocalAppColors.current.textPrimary,
                unfocusedTextColor = LocalAppColors.current.textPrimary,
                focusedBorderColor = KaspaTeal,
                unfocusedBorderColor = LocalAppColors.current.textSecondary,
                focusedLabelColor = KaspaTeal,
                unfocusedLabelColor = LocalAppColors.current.textSecondary
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KnsSimpleConfirmStep(message: String, buttonLabel: String?, onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            message,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LocalAppColors.current.textPrimary,
            textAlign = TextAlign.Center
        )
        if (buttonLabel != null) {
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatKasWizard(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.4f".format(java.util.Locale.US, value)
    }
}


/**
 * The wizard's fork. Two buttons, no default: guessing wrong here sends someone down a funding
 * gate they do not need, or a purchase flow for a domain they already own.
 */
@Composable
private fun KnsHaveDomainStep(onYes: () -> Unit, onNo: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            "Do you already have a domain?",
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "A KNS domain is your name on Kaspa. If you own one already, you can bring it here instead of buying another.",
            color = colors.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onYes,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
        ) {
            Text("Yes, I have one", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onNo,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = KaspaTeal.copy(alpha = 0.15f),
                contentColor = KaspaTeal,
            ),
        ) {
            Text("No, I need one", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Which domain to build the profile around. Only useful with domains in hand; the Refresh on the
 * previous screen is what puts them there.
 */
@Composable
private fun KnsPickDomainStep(
    domains: List<com.kachat.app.services.KnsAsset>,
    onPick: (com.kachat.app.services.KnsAsset) -> Unit,
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "Which domain should this profile use?",
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        if (domains.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No domains on this address yet", color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Go back and use Refresh once the transfer lands.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(domains, key = { it.assetId ?: it.asset.orEmpty() }) { domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .clickable { onPick(domain) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            domain.asset.orEmpty(),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}


/**
 * The wizard's fixed two-button footer: Previous left, Next right, same place on every step so it
 * can be tapped through without chasing buttons. Matches the KaChat setup guide's bar.
 *
 * A disabled Previous renders dimmed rather than vanishing, so Next never moves between steps.
 */
@Composable
fun KnsWizardBottomBar(
    onBack: (() -> Unit)?,
    onNext: (() -> Unit)?,
    nextLabel: String = "Next",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { onBack?.invoke() },
            enabled = onBack != null,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalAppColors.current.surface,
                contentColor = LocalAppColors.current.textPrimary,
            ),
        ) {
            Text("Previous", fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = { onNext?.invoke() },
            enabled = onNext != null,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black),
        ) {
            Text(nextLabel, fontWeight = FontWeight.Bold)
        }
    }
}
