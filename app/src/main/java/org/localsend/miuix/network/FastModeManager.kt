package org.localsend.miuix.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.localsend.miuix.core.NetworkConstants

/**
 * 极速模式的传输参数开关：开启后使用更大的读写缓冲并允许多文件并行上传。
 * 关闭（默认）时数值与原有行为完全一致。
 */
object FastModeTuning {
    private const val NORMAL_BUFFER = 128 * 1024
    private const val FAST_BUFFER = 512 * 1024
    private const val FAST_PARALLEL_UPLOADS = 3

    @Volatile
    var enabled: Boolean = false

    val bufferSize: Int
        get() = if (enabled) FAST_BUFFER else NORMAL_BUFFER

    val parallelUploads: Int
        get() = if (enabled) FAST_PARALLEL_UPLOADS else 1
}

enum class FastModePhase { Off, Starting, Searching, Connecting, Connected, Error }

enum class FastModeError { NoPermission, P2pUnsupported, P2pDisabled, ConnectFailed, Failed }

data class FastPeer(
    val name: String,
    val address: String,
)

data class FastModeState(
    val phase: FastModePhase = FastModePhase.Off,
    val peers: List<FastPeer> = emptyList(),
    val peerName: String? = null,
    val error: FastModeError? = null,
)

/**
 * 极速模式：通过 Wi-Fi Direct（点对点直连，优先 5GHz）在两台手机间建立不经过路由器的高速链路，
 * 同时启用高性能 Wi-Fi 锁与更大的传输缓冲。链路建立后，对端会出现在“附近设备”中，
 * 之后沿用原有的 LocalSend 协议传输。点击“关闭极速模式”会拆除直连组、释放所有锁并恢复原有网络。
 */
class FastModeManager(
    context: Context,
    private val onLinkReady: () -> Unit,
    private val onLinkClosed: () -> Unit,
) {
    companion object {
        private const val TAG = "FastMode"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** Android 13+ 使用 NEARBY_WIFI_DEVICES；更低版本的 Wi-Fi Direct 发现需要定位权限。 */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(FastModeState())
    val state: StateFlow<FastModeState> = _state.asStateFlow()

    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    fun reportPermissionDenied() {
        _state.value = FastModeState(phase = FastModePhase.Error, error = FastModeError.NoPermission)
    }

    private fun isActive(): Boolean = _state.value.phase != FastModePhase.Off && _state.value.phase != FastModePhase.Error

    /** 开启极速模式：申请锁、注册直连广播并开始发现附近设备。 */
    @SuppressLint("MissingPermission")
    fun enable() {
        if (isActive()) return
        if (!hasRequiredPermissions()) {
            reportPermissionDenied()
            return
        }
        val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null || !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            _state.value = FastModeState(phase = FastModePhase.Error, error = FastModeError.P2pUnsupported)
            return
        }
        val newChannel =
            manager.initialize(appContext, Looper.getMainLooper()) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected")
                fail(FastModeError.Failed)
            }
        if (newChannel == null) {
            _state.value = FastModeState(phase = FastModePhase.Error, error = FastModeError.P2pUnsupported)
            return
        }
        p2pManager = manager
        channel = newChannel
        FastModeTuning.enabled = true
        acquireLocks()
        registerReceiver()
        _state.value = FastModeState(phase = FastModePhase.Starting)
        discoverPeers()
    }

    /** 关闭极速模式：拆除直连组、停止发现、释放锁，网络与传输参数恢复正常。 */
    fun disable() {
        val wasActive = isActive()
        teardown()
        _state.value = FastModeState()
        if (wasActive) onLinkClosed()
    }

    private fun fail(error: FastModeError) {
        teardown()
        _state.value = FastModeState(phase = FastModePhase.Error, error = error)
        onLinkClosed()
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        unregisterReceiver()
        val manager = p2pManager
        val ch = channel
        if (manager != null && ch != null) {
            try {
                manager.cancelConnect(ch, null)
                manager.stopPeerDiscovery(ch, null)
                manager.removeGroup(ch, null)
            } catch (e: Exception) {
                Log.w(TAG, "teardown: ${e.message}")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ch.close()
            } catch (_: Exception) {
            }
        }
        channel = null
        p2pManager = null
        releaseLocks()
        FastModeTuning.enabled = false
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        val manager = p2pManager ?: return
        val ch = channel ?: return
        try {
            manager.discoverPeers(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _state.update { if (it.phase == FastModePhase.Starting) it.copy(phase = FastModePhase.Searching) else it }
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverPeers failed: $reason")
                        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                            fail(FastModeError.P2pUnsupported)
                        } else {
                            // BUSY/ERROR 多为瞬时状态，保持搜索态，广播到来时仍可刷新
                            _state.update { if (it.phase == FastModePhase.Starting) it.copy(phase = FastModePhase.Searching) else it }
                        }
                    }
                },
            )
        } catch (e: SecurityException) {
            fail(FastModeError.NoPermission)
        }
    }

    /** 向选中的对端发起直连；优先请求 5GHz，失败后自动回退到系统默认频段。 */
    @SuppressLint("MissingPermission")
    fun connect(peer: FastPeer) {
        val manager = p2pManager ?: return
        val ch = channel ?: return
        _state.update { it.copy(phase = FastModePhase.Connecting, peerName = peer.name, error = null) }
        val prefer5Ghz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        connectWith(manager, ch, peer, prefer5Ghz)
    }

    @SuppressLint("MissingPermission")
    private fun connectWith(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        peer: FastPeer,
        use5Ghz: Boolean,
    ) {
        val config =
            try {
                buildConfig(peer.address, use5Ghz)
            } catch (e: Exception) {
                _state.update { it.copy(phase = FastModePhase.Searching, error = FastModeError.ConnectFailed) }
                return
            }
        try {
            manager.connect(
                ch,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        // 等待系统广播确认直连组已形成
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "connect failed (5GHz=$use5Ghz): $reason")
                        if (use5Ghz) {
                            connectWith(manager, ch, peer, use5Ghz = false)
                        } else {
                            _state.update { it.copy(phase = FastModePhase.Searching, error = FastModeError.ConnectFailed) }
                        }
                    }
                },
            )
        } catch (e: SecurityException) {
            fail(FastModeError.NoPermission)
        }
    }

    private fun buildConfig(
        address: String,
        use5Ghz: Boolean,
    ): WifiP2pConfig =
        if (use5Ghz && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiP2pConfig
                .Builder()
                .setDeviceAddress(MacAddress.fromString(address))
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
        } else {
            WifiP2pConfig().apply {
                deviceAddress = address
                wps.setup = WpsInfo.PBC
            }
        }

    private fun registerReceiver() {
        if (receiver != null) return
        val filter =
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_STATE_CHANGE_ACTION)
            }
        val newReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) fail(FastModeError.P2pDisabled)
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                        WifiP2pManager.WIFI_P2P_CONNECTION_STATE_CHANGE_ACTION -> refreshConnection()
                    }
                }
            }
        ContextCompat.registerReceiver(appContext, newReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = newReceiver
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    private fun refreshPeers() {
        val manager = p2pManager ?: return
        val ch = channel ?: return
        try {
            manager.requestPeers(ch) { list ->
                val peers =
                    list.deviceList
                        .filter { !it.deviceAddress.isNullOrEmpty() }
                        .map { FastPeer(name = it.deviceName.orEmpty().ifEmpty { it.deviceAddress }, address = it.deviceAddress) }
                        .sortedBy { it.name.lowercase() }
                _state.update { it.copy(peers = peers) }
            }
        } catch (e: SecurityException) {
            fail(FastModeError.NoPermission)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnection() {
        val manager = p2pManager ?: return
        val ch = channel ?: return
        try {
            manager.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    val wasConnected = _state.value.phase == FastModePhase.Connected
                    _state.update { it.copy(phase = FastModePhase.Connected, error = null) }
                    resolvePeerName(manager, ch)
                    if (!wasConnected) onLinkReady()
                } else if (_state.value.phase == FastModePhase.Connected) {
                    // 对端断开直连组：回到搜索态，保持极速模式开启直至用户手动关闭
                    _state.update { it.copy(phase = FastModePhase.Searching, peerName = null) }
                    discoverPeers()
                }
            }
        } catch (e: SecurityException) {
            fail(FastModeError.NoPermission)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolvePeerName(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ) {
        try {
            manager.requestGroupInfo(ch) { group ->
                val name =
                    if (group == null) {
                        null
                    } else if (group.isGroupOwner) {
                        group.clientList.firstOrNull()?.deviceName
                    } else {
                        group.owner?.deviceName
                    }
                if (!name.isNullOrEmpty()) _state.update { it.copy(peerName = name) }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wifiLock == null) {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock =
                    wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "${NetworkConstants.WIFI_LOCK_TAG}:fast")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            if (wakeLock == null) {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock =
                    powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${NetworkConstants.WAKE_LOCK_TAG}:fast")?.apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "acquireLocks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        wakeLock = null
    }
}
