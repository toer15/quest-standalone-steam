package com.toer15.queststeam.display

import android.content.Context
import android.view.ViewGroup
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
import com.winlator.widget.XServerView
import com.winlator.winhandler.WinHandler
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xserver.SHMSegmentManager
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The game's screen inside this app: Winlator's X server (Java, LGPL-2.1, com.winlator.*) runs in
 * the app process, Wine draws into it over <rootfs>/tmp/.X11-unix/X0 (where the runtime's libxcb
 * connects), and [view] shows it with OpenGL in the app's own window. MIT-SHM goes through
 * Winlator's SysV shared memory server (the runtime's glibc asks ANDROID_SYSVSHM_SERVER).
 * No second app, no network sockets, no extra copies.
 */
object BuiltInDisplay {
    const val DISPLAY = ":0"
    /** The game's resolution; scaled to the window with the right aspect ratio. */
    const val SCREEN = "1280x720"

    @Volatile var server: XServer? = null; private set
    @Volatile var view: XServerView? = null; private set
    private val _viewState = MutableStateFlow<XServerView?>(null)
    /** The game view while a game uses the built-in display (the screen swaps to it). */
    val viewState: StateFlow<XServerView?> = _viewState
    private var xConnector: XConnectorEpoll? = null
    private var shmConnector: XConnectorEpoll? = null
    private var shm: SysVSharedMemory? = null
    private var vortek: VortekRendererComponent? = null

    private val _gameWindow = MutableStateFlow(false)
    /** True once the game has drawn into an application window (Winlator's own "game started" signal). */
    val gameWindow: StateFlow<Boolean> = _gameWindow

    val isRunning get() = server != null

    /** True when [display] (the Runtime screen setting) means this built-in display. */
    fun isBuiltIn(display: String?) = display == null || display == DISPLAY || display.isBlank()

    /**
     * Starts the X server and the shared-memory server for a game. Returns the environment Wine needs.
     * Must be called on the main thread (creates the game view). With [useVortek] the game's Vulkan
     * runs on the headset's own driver through [VortekRendererComponent] (see [Vortek]).
     */
    @Synchronized
    fun start(ctx: Context, rootfs: File, useVortek: Boolean, log: (String) -> Unit): Map<String, String> {
        stop()
        _gameWindow.value = false
        val xs = XServer(ScreenInfo(SCREEN))
        xs.setWinHandler(WinHandler())
        val v = XServerView(ctx.applicationContext, xs)
        val renderer = v.renderer
        renderer.setCursorVisible(true)
        // Wine's virtual desktop (explorer.exe) stays invisible; only the game's own windows are drawn.
        renderer.setUnviewableWMClasses("explorer.exe")
        xs.setRenderer(renderer)
        xs.windowManager.addOnWindowModificationListener(object : WindowManager.OnWindowModificationListener {
            override fun onUpdateWindowContent(window: Window) {
                if (!_gameWindow.value && window.isApplicationWindow) {
                    _gameWindow.value = true
                    log("Game window is open (${window.className.ifBlank { "window" }}, ${window.width}x${window.height}).")
                }
            }
        })
        val root = rootfs.path
        File(rootfs, "tmp").mkdirs()
        val memory = SysVSharedMemory()
        shmConnector = XConnectorEpoll(
            UnixSocketConfig.createSocket(root, UnixSocketConfig.SYSVSHM_SERVER_PATH),
            SysVSHMConnectionHandler(memory), SysVSHMRequestHandler(),
        ).also { it.start() }
        shm = memory
        xs.setSHMSegmentManager(SHMSegmentManager(memory))
        val xSocket = UnixSocketConfig.createSocket(root, UnixSocketConfig.XSERVER_PATH)
        xConnector = XConnectorEpoll(xSocket, XClientConnectionHandler(xs), XClientRequestHandler()).also {
            it.setInitialInputBufferCapacity(262144)
            it.setCanReceiveAncillaryMessages(true)
            it.start()
        }
        server = xs
        view = v
        _viewState.value = v
        log("Game display ready (built in, $SCREEN, ${xSocket.path}).")
        val env = mutableMapOf(
            "DISPLAY" to DISPLAY,
            "ANDROID_SYSVSHM_SERVER" to File(rootfs, UnixSocketConfig.SYSVSHM_SERVER_PATH).path,
        )
        if (useVortek) {
            try {
                File(ctx.cacheDir, "vortek").mkdirs()
                VortekRendererComponent.logger = java.util.function.Consumer { log(it) }
                val component = VortekRendererComponent(
                    xs, v, UnixSocketConfig.createSocket(root, Vortek.SOCKET),
                    VortekRendererComponent.Options(), ctx.applicationInfo.nativeLibraryDir,
                )
                component.start()
                vortek = component
                env["VK_ICD_FILENAMES"] = Vortek.icd(rootfs).path
                log("GPU: Vortek (the headset's own Vulkan driver).")
            } catch (t: Throwable) {
                log("GPU: Vortek couldn't start (${t.javaClass.simpleName}: ${t.message}); using Turnip.")
            }
        } else log("GPU: Turnip.")
        return env
    }

    @Synchronized
    fun stop() {
        runCatching { vortek?.stop() }
        vortek = null
        runCatching { xConnector?.stop() }
        runCatching { shmConnector?.stop() }
        runCatching { shm?.deleteAll() }
        view?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        xConnector = null; shmConnector = null; shm = null
        server = null; view = null
        _viewState.value = null
        _gameWindow.value = false
    }

    /** View coordinates -> game screen coordinates (the picture is letterboxed to keep its aspect ratio). */
    fun toScreen(x: Float, y: Float): Pair<Int, Int>? {
        val xs = server ?: return null
        val t = view?.renderer?.viewTransformation ?: return null
        if (t.aspect <= 0f) return null
        val sx = ((x - t.viewOffsetX) / t.aspect).toInt().coerceIn(0, xs.screenInfo.width - 1)
        val sy = ((y - t.viewOffsetY) / t.aspect).toInt().coerceIn(0, xs.screenInfo.height - 1)
        return sx to sy
    }
}
