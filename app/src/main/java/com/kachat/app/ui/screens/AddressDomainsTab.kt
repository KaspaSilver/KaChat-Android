package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kachat.app.R
import com.kachat.app.services.KnsService
import com.kachat.app.services.NameServiceTLD
import com.kachat.app.services.NameServicesClient
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One address's names on every service, for its Domains tab (iOS `AddressDomainsTab`, fe52376).
 * Held here rather than in the tab so a service's own screen (pushed over the tab) reads the same
 * answers: both are composed under the address screen's back-stack entry.
 */
@HiltViewModel
class AddressDomainsViewModel @Inject constructor(
    private val nameServices: NameServicesClient,
    private val knsService: KnsService,
) : ViewModel() {
    data class State(
        val address: String = "",
        /** A service's names; a service missing here is still on the way, or failed. */
        val names: Map<NameServiceTLD, List<String>> = emptyMap(),
        val failed: Set<NameServiceTLD> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Every service at once, for this address only (Your Domains' own lists are left alone). */
    suspend fun load(address: String) {
        val normalized = address.trim().lowercase()
        if (_state.value.address != normalized) _state.value = State(address = normalized)
        if (normalized.isEmpty()) return
        coroutineScope {
            launch {
                val kachat = try {
                    nameServices.kachatNames(listOf(normalized))[normalized].orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                set(normalized, NameServiceTLD.KACHAT, kachat.map { it.display })
            }
            launch {
                val byService = nameServices.ownedNamesByService(normalized)
                for (tld in listOf(NameServiceTLD.K, NameServiceTLD.KASPA)) {
                    val list = byService[tld]
                    if (list != null) set(normalized, tld, list.map { it.display }) else fail(normalized, tld)
                }
            }
            launch {
                val kns = knsService.getOwnedDomainsOrNull(normalized)
                if (kns != null) set(normalized, NameServiceTLD.KAS, kns.mapNotNull { it.asset }) else fail(normalized, NameServiceTLD.KAS)
            }
        }
    }

    private fun set(address: String, tld: NameServiceTLD, list: List<String>) {
        val current = _state.value
        if (current.address != address) return
        _state.value = current.copy(names = current.names + (tld to list), failed = current.failed - tld)
    }

    private fun fail(address: String, tld: NameServiceTLD) {
        val current = _state.value
        if (current.address != address) return
        _state.value = current.copy(failed = current.failed + tld)
    }

    companion object {
        /** The order the cards are listed in. */
        val order = listOf(NameServiceTLD.KACHAT, NameServiceTLD.K, NameServiceTLD.KASPA, NameServiceTLD.KAS)
    }
}

/**
 * The Domains tab of an address screen (the chatting address, a spending address, a KasSigner
 * address): one card per name service - .kachat, .k, .kaspa, .kas - each with how many names this
 * address holds there, or a warning when that service couldn't be reached. Tapping a card opens
 * [AddressDomainServiceScreen] through [onOpenService] (iOS fe52376, 0b68567).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressDomainsTab(
    address: String,
    onOpenService: (NameServiceTLD) -> Unit,
    /** Extra room at the scroll's end for what floats over the page's foot (its bar, the dock). */
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    vm: AddressDomainsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(address) { vm.load(address) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm.load(address)
            pullState.endRefresh()
        }
    }
    val current = state.takeIf { it.address == address.trim().lowercase() } ?: AddressDomainsViewModel.State()
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AddressDomainsViewModel.order.forEach { tld ->
                AddressDomainServiceCard(
                    tld = tld,
                    count = current.names[tld]?.size,
                    failed = tld in current.failed,
                    onClick = { onOpenService(tld) },
                )
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun AddressDomainServiceCard(tld: NameServiceTLD, count: Int?, failed: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the same globe on every card: the suffix beside it says which service it is (iOS 0b68567)
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tld.suffix, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(tld.serviceName, color = colors.textSecondary, fontSize = 15.sp)
        }
        Spacer(Modifier.width(14.dp))
        when {
            count != null -> {
                val label = if (count == 1) stringResource(R.string.address_domains_one)
                else stringResource(R.string.address_domains_count, count)
                Text(
                    "$count",
                    color = if (count > 0) Color.Black else colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .semantics { contentDescription = label }
                        .clip(RoundedCornerShape(50))
                        .background(if (count > 0) KaspaTeal else colors.textSecondary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            failed -> Icon(
                Icons.Filled.WarningAmber,
                contentDescription = stringResource(R.string.address_domains_couldnt_load),
                tint = colors.warning,
                modifier = Modifier.size(20.dp),
            )
            else -> com.kachat.app.ui.theme.IosActivityIndicator(color = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A service's names on one address, opened from its Domains card: .kachat opens its cards and
 * detail screens as before ([KachatAddressDomainsList]); the others list the names (iOS fe52376).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressDomainServiceScreen(
    address: String,
    tld: NameServiceTLD,
    onBack: () -> Unit,
    onOpenKachat: (com.kachat.app.services.kachatnames.NameInfo) -> Unit,
    vm: AddressDomainsViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(tld.suffix, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBackIosNew, "Back", tint = LocalAppColors.current.textPrimary) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tld == NameServiceTLD.KACHAT) {
                // the .kachat cards and their detail screens, as before
                KachatAddressDomainsList(address.takeIf { it.isNotEmpty() }, onOpen = onOpenKachat)
            } else {
                AddressDomainServiceNames(address, tld, vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressDomainServiceNames(address: String, tld: NameServiceTLD, vm: AddressDomainsViewModel) {
    val colors = LocalAppColors.current
    val state by vm.state.collectAsState()
    // Opened before the tab's answer landed (or for another address): ask again.
    LaunchedEffect(address) {
        if (vm.state.value.address != address.trim().lowercase()) vm.load(address)
    }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            vm.load(address)
            pullState.endRefresh()
        }
    }
    val list = state.names[tld].orEmpty()
    val failed = tld in state.failed
    Box(Modifier.fillMaxSize().nestedScroll(pullState.nestedScrollConnection)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (list.isEmpty()) {
                Text(
                    if (failed) stringResource(R.string.could_not_reach_service, tld.serviceName)
                    else stringResource(R.string.address_domains_none, tld.suffix),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else {
                list.forEach { name -> DomainNameCard(title = name) }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}
