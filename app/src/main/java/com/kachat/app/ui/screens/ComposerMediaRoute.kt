package com.kachat.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaTeal

/**
 * What a "+"-sheet media row captures. With a Nextcloud server linked, each of these asks where
 * the media goes - on chain or via Nextcloud - every time, which is what replaced the old "Send
 * Media via Nextcloud" setting. Without one, the row goes straight to the on-chain path. Mirrors
 * iOS's `ComposerMediaKind` (8b13460).
 */
enum class ComposerMediaKind { CAMERA, PHOTO, VOICE }

/**
 * The "+" sheet's second step for Camera / Photo / Voice Message: on chain, or via Nextcloud.
 * Shown only while a Nextcloud server is connected - 1:1 and group chats show it after their
 * options, public chats' mic shows it on its own. Mirrors iOS's `ComposerMediaRouteStep`
 * (8b13460, a890102).
 *
 * Android's camera is the system photo capture, which takes stills only, so "Take Photo via
 * Nextcloud" says "Full quality." where iOS's own camera adds "or a video" - a video goes via
 * Nextcloud from Photo instead ("Send Photo or Video via Nextcloud").
 */
@Composable
fun ComposerMediaRouteSheet(
    kind: ComposerMediaKind,
    onChoose: (viaNextcloud: Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (kind) {
        ComposerMediaKind.CAMERA -> R.string.camera
        ComposerMediaKind.PHOTO -> R.string.photo
        ComposerMediaKind.VOICE -> R.string.composer_voice_message
    }
    val onChainTitle = when (kind) {
        ComposerMediaKind.CAMERA -> R.string.media_take_on_chain_photo
        ComposerMediaKind.PHOTO -> R.string.media_send_photo_on_chain
        ComposerMediaKind.VOICE -> R.string.media_record_on_chain
    }
    val onChainSubtitle = when (kind) {
        ComposerMediaKind.CAMERA -> R.string.media_take_on_chain_photo_subtitle
        ComposerMediaKind.PHOTO -> R.string.media_send_photo_on_chain_subtitle
        ComposerMediaKind.VOICE -> R.string.media_record_on_chain_subtitle
    }
    val nextcloudTitle = when (kind) {
        ComposerMediaKind.CAMERA -> R.string.media_take_photo_via_nextcloud
        ComposerMediaKind.PHOTO -> R.string.media_send_photo_or_video_via_nextcloud
        ComposerMediaKind.VOICE -> R.string.media_record_via_nextcloud
    }
    val nextcloudSubtitle = when (kind) {
        ComposerMediaKind.CAMERA -> R.string.media_take_photo_via_nextcloud_subtitle
        ComposerMediaKind.PHOTO -> R.string.media_photo_or_video_via_nextcloud_subtitle
        ComposerMediaKind.VOICE -> R.string.media_record_via_nextcloud_subtitle
    }
    ActionSheetContainer(title = stringResource(title), subtitle = null, onDismiss = onDismiss) {
        ActionSheetRow(
            icon = Icons.Default.Link,
            title = stringResource(onChainTitle),
            subtitle = stringResource(onChainSubtitle),
        ) { onChoose(false) }
        ActionSheetRow(
            icon = Icons.Default.Cloud,
            title = stringResource(nextcloudTitle),
            subtitle = stringResource(nextcloudSubtitle),
        ) { onChoose(true) }
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back), color = KaspaTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
