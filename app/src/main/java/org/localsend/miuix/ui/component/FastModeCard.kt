package org.localsend.miuix.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.localsend.miuix.R
import org.localsend.miuix.manager.LocalSendManager
import org.localsend.miuix.model.TransferStatus
import org.localsend.miuix.network.FastModeError
import org.localsend.miuix.network.FastModeManager
import org.localsend.miuix.network.FastModePhase
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 极速模式卡片（发送页 / 接收页共用）。
 * 开启后通过 Wi-Fi Direct 直连两台手机；传输完成后点击“关闭极速模式”即可拆除直连并恢复正常网络。
 *
 * @param showPeers 发送页为 true：列出附近可直连的设备供选择；接收页为 false：仅等待对方连接。
 */
@Composable
fun FastModeCard(
    manager: LocalSendManager,
    showPeers: Boolean,
    modifier: Modifier = Modifier,
) {
    val fastMode = manager.fastMode
    val state by fastMode.state.collectAsState()
    val activeSessions by manager.activeSessions.collectAsState()
    val transferRunning = activeSessions.any { it.status == TransferStatus.InProgress }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                fastMode.enable()
            } else {
                fastMode.reportPermissionDenied()
            }
        }

    val isOn = state.phase != FastModePhase.Off && state.phase != FastModePhase.Error
    val peerName = state.peerName.orEmpty()
    val summary =
        when (state.phase) {
            FastModePhase.Off -> stringResource(R.string.fast_mode_summary_off)
            FastModePhase.Starting -> stringResource(R.string.fast_mode_summary_starting)
            FastModePhase.Searching ->
                if (showPeers) {
                    stringResource(R.string.fast_mode_summary_searching_send)
                } else {
                    stringResource(R.string.fast_mode_summary_searching_receive)
                }
            FastModePhase.Connecting -> stringResource(R.string.fast_mode_summary_connecting, peerName)
            FastModePhase.Connected ->
                if (peerName.isEmpty()) {
                    stringResource(R.string.fast_mode_summary_connected_unknown)
                } else {
                    stringResource(R.string.fast_mode_summary_connected, peerName)
                }
            FastModePhase.Error -> stringResource(R.string.fast_mode_summary_error)
        }

    Card(modifier = modifier.fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.fast_mode_title),
            summary = summary,
            checked = isOn,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    fastMode.disable()
                } else if (fastMode.hasRequiredPermissions()) {
                    fastMode.enable()
                } else {
                    permissionLauncher.launch(FastModeManager.requiredPermissions())
                }
            },
        )

        val errorText =
            when (state.error) {
                FastModeError.NoPermission -> stringResource(R.string.fast_mode_error_permission)
                FastModeError.P2pUnsupported -> stringResource(R.string.fast_mode_error_unsupported)
                FastModeError.P2pDisabled -> stringResource(R.string.fast_mode_error_disabled)
                FastModeError.ConnectFailed -> stringResource(R.string.fast_mode_error_connect)
                FastModeError.Failed -> stringResource(R.string.fast_mode_error_generic)
                null -> null
            }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (showPeers && (state.phase == FastModePhase.Searching || state.phase == FastModePhase.Connecting)) {
            if (state.peers.isEmpty()) {
                Text(
                    text = stringResource(R.string.fast_mode_no_peers),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.peers.forEach { peer ->
                ArrowPreference(
                    title = peer.name,
                    summary = stringResource(R.string.fast_mode_peer_summary),
                    onClick = { fastMode.connect(peer) },
                )
            }
        }

        if (isOn) {
            Text(
                text =
                    when {
                        transferRunning -> stringResource(R.string.fast_mode_hint_transferring)
                        state.phase == FastModePhase.Connected -> stringResource(R.string.fast_mode_hint_done)
                        else -> stringResource(R.string.fast_mode_hint_close)
                    },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Button(
                onClick = { fastMode.disable() },
                colors = if (transferRunning) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.fast_mode_btn_close))
            }
        }
    }
}
