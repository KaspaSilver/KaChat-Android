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
import com.kachat.app.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.first
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Who is behind the address a screen has resolved - the card Create chat shows, for every other
 * place an address or a domain goes in: a withdrawal, a send, the portfolio, a group invite. The
 * SCREEN does the resolving (.kachat first, see [com.kachat.app.services.NameServicesClient.resolveEverywhere]);
 * this card takes the outcome and shows the face and the name: the [domain] that was typed, else
 * the address's own .kachat name. Null address, no card - a half-typed address gets nothing rather
 * than a card flickering through wrong faces. Mirrors iOS's AddressResolutionCard (6ac48a7).
 */
@Composable
fun AddressResolutionCard(
    address: String?,
    domain: String? = null,
    modifier: Modifier = Modifier,
    viewModel: AddressResolutionViewModel = hiltViewModel(),
) {
    val colors = LocalAppColors.current
    val shown = address?.trim()?.takeIf { it.isNotEmpty() }
    var profileName by remember { mutableStateOf<String?>(null) }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }

    LaunchedEffect(shown) {
        profileName = null
        avatarUrl = null
        if (shown == null || !KaspaAddress.isValid(shown)) {
            looking = false
            return@LaunchedEffect
        }
        looking = true
        val profile = viewModel.profileFor(shown)
        profileName = profile?.first
        avatarUrl = profile?.second
        looking = false
    }

    if (shown == null) return
    // The domain typed, else the address's .kachat name (snapshot state: it recomposes when the
    // identity lands), else what KNS knows.
    val name = domain
        ?: com.kachat.app.services.kachatnames.KachatNamesRegistry.kachatName(shown)
        ?: profileName
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(imageUrl = avatarUrl, fallbackText = name ?: shown.takeLast(8), size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name ?: if (looking) stringResource(R.string.looking_up) else stringResource(R.string.no_domain),
                color = if (name != null) colors.textPrimary else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiddleEllipsisText(
                shown,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        if (looking) {
            com.kachat.app.ui.theme.IosActivityIndicator(color = KaspaTeal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
    }
}

/** The card's own lookups, so any screen can show it without plumbing KNS through its own model. */
@HiltViewModel
class AddressResolutionViewModel @Inject constructor(
    private val knsService: KnsService,
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val nameServices: com.kachat.app.services.NameServicesClient,
) : ViewModel() {

    /** What a typed name points to on every service, .kachat first (iOS 6ac48a7) - for a field
     *  that has no model of its own (the Address Book editor). */
    suspend fun resolveEverywhere(input: String): List<com.kachat.app.services.NameResolution> =
        runCatching { nameServices.resolveEverywhere(input) }.getOrDefault(emptyList())

    /** The contact saved for [address] on this account, if there is one. */
    suspend fun contactFor(address: String): com.kachat.app.models.ContactEntity? = runCatching {
        chatRepository.getContacts().first().firstOrNull { it.id.equals(address, ignoreCase = true) }
    }.getOrNull()

    /** The address's primary domain and avatar, as far as KNS knows them. */
    suspend fun profileFor(address: String): Pair<String?, String?>? = runCatching {
        // A .kas name labels nobody since 5.2 (KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY).
        if (!com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY) return@runCatching null to null
        val primary = knsService.getExplicitPrimaryDomain(address)
            ?: knsService.getOwnedDomainsCached(address).firstNotNullOfOrNull { it.asset }
        val assetId = knsService.getOwnedDomainsCached(address)
            .firstOrNull { it.asset == primary }?.assetId
        val avatar = assetId?.let { knsService.getProfile(it)?.avatarUrl }
        primary to avatar
    }.getOrNull()
}
