package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kachat.app.services.KnsService
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Who is behind an address being typed - the card Create chat shows, for every other place an
 * address or a `.kas` domain goes in: a withdrawal, a send, a portfolio transaction, a group
 * invite. It resolves on its own - a domain is looked up to its owner first, a valid address
 * fetches its KNS profile - and appears only once the input is something the app is confident
 * about, so a half-typed address gets nothing rather than a card flickering through wrong faces.
 * Mirrors iOS's AddressResolutionCard (ac0ef19, bd0a6f3, 7d4fdd7).
 */
@Composable
fun AddressResolutionCard(
    input: String,
    modifier: Modifier = Modifier,
    viewModel: AddressResolutionViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val trimmed = input.trim()
    var address by remember { mutableStateOf<String?>(null) }
    var domain by remember { mutableStateOf<String?>(null) }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }

    // The lookup runs on what is typed, not on what is already drawn: a card only ever appears
    // once this has an answer (iOS bd0a6f3).
    LaunchedEffect(trimmed) {
        address = null
        domain = null
        avatarUrl = null
        if (trimmed.isEmpty()) {
            looking = false
            return@LaunchedEffect
        }
        val isDomain = KnsService.looksLikeDomain(trimmed)
        if (!isDomain && !KaspaAddress.isValid(trimmed)) {
            looking = false
            return@LaunchedEffect
        }
        looking = true
        // The same 300ms iOS debounces a typed domain by, so a name being typed is not looked up
        // once per keystroke.
        kotlinx.coroutines.delay(300)
        val resolved = if (isDomain) viewModel.resolveDomain(trimmed) else trimmed
        if (resolved == null) {
            looking = false
            return@LaunchedEffect
        }
        address = resolved
        domain = if (isDomain) KnsService.normalizeDomain(trimmed) else null
        val profile = viewModel.profileFor(resolved)
        domain = domain ?: profile?.first
        avatarUrl = profile?.second
        looking = false
    }

    val shown = address ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(imageUrl = avatarUrl, fallbackText = domain ?: shown.takeLast(8), size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                domain ?: if (looking) "Looking up..." else "No KNS domain",
                color = if (domain != null) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shown,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (looking) {
            CircularProgressIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
    }
}

/** The card's own lookups, so any screen can show it without plumbing KNS through its own model. */
@HiltViewModel
class AddressResolutionViewModel @Inject constructor(
    private val knsService: KnsService,
) : ViewModel() {

    suspend fun resolveDomain(domain: String): String? = runCatching { knsService.resolve(domain) }.getOrNull()

    /** The address's primary domain and avatar, as far as KNS knows them. */
    suspend fun profileFor(address: String): Pair<String?, String?>? = runCatching {
        val primary = knsService.getExplicitPrimaryDomain(address)
            ?: knsService.getOwnedDomainsCached(address).firstNotNullOfOrNull { it.asset }
        val assetId = knsService.getOwnedDomainsCached(address)
            .firstOrNull { it.asset == primary }?.assetId
        val avatar = assetId?.let { knsService.getProfile(it)?.avatarUrl }
        primary to avatar
    }.getOrNull()
}
