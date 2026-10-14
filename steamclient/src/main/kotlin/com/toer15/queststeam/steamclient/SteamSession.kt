package com.toer15.queststeam.steamclient

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture

/** What the Steam client itself keeps after "Remember me": account name + refresh token. Never the password. */
data class SavedLogin(val accountName: String, val refreshToken: String, val steamId: Long)

/** Persists [SavedLogin] (the app stores it in private SharedPreferences). */
interface LoginStore {
    fun load(): SavedLogin?
    fun save(login: SavedLogin?)
}

/** Steam Guard questions, answered by the UI. */
interface GuardPrompt {
    /** Code from the Steam mobile app's authenticator. */
    suspend fun deviceCode(previousWasWrong: Boolean): String
    /** Code Steam emailed to [email]. */
    suspend fun emailCode(email: String?, previousWasWrong: Boolean): String
    /** Steam allows approving the sign-in in the Steam mobile app. Return true to wait for that approval, false to type a code. */
    suspend fun approveOnPhone(): Boolean
}

data class OwnedApp(val appId: Int, val name: String, val playtimeMinutes: Int)

class SteamLoginException(message: String, val needsNewLogin: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/**
 * One Steam CM connection for the whole app: sign-in (QR or password + Steam Guard),
 * token logon, the owned-games list, and the licenses DepotDownloader needs.
 */
class SteamSession(private val store: LoginStore, private val deviceName: String = "Quest Standalone Steam") {

    enum class Status { SignedOut, Offline, Connecting, SigningIn, Online }

    private val _status = MutableStateFlow(if (store.load() != null) Status.Offline else Status.SignedOut)
    val status: StateFlow<Status> = _status
    private val _personaName = MutableStateFlow<String?>(null)
    val personaName: StateFlow<String?> = _personaName

    val savedLogin: SavedLogin? get() = store.load()
    val isSignedIn: Boolean get() = store.load() != null

    /** Last error from a background Steam task (also printed); never thrown, so it can't crash the app. */
    @Volatile var lastBackgroundError: Throwable? = null
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        lastBackgroundError = e
        System.err.println("[steam] background error: $e")
    })
    private val lock = Mutex()
    @Volatile private var client: SteamClient? = null
    private var pump: Job? = null
    private var playerService: Player? = null
    @Volatile private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var loggedOn: CompletableDeferred<LoggedOnCallback>? = null
    @Volatile private var licenses = CompletableDeferred<List<License>>()

    // region connection

    private fun newClient(): SteamClient {
        val c = SteamClient(SteamConfiguration.create { it.withConnectionTimeout(CONNECT_TIMEOUT_MS) })
        val m = CallbackManager(c)
        m.subscribe(ConnectedCallback::class.java) { connected?.complete(Unit) }
        m.subscribe(DisconnectedCallback::class.java) {
            connected?.completeExceptionally(SteamLoginException("Couldn't connect to Steam. Check your internet connection."))
            loggedOn?.completeExceptionally(SteamLoginException("Steam disconnected during sign-in. Try again."))
            if (client === c) _status.value = if (isSignedIn) Status.Offline else Status.SignedOut
        }
        m.subscribe(LoggedOnCallback::class.java) { loggedOn?.complete(it) }
        m.subscribe(LoggedOffCallback::class.java) { if (client === c) _status.value = if (isSignedIn) Status.Offline else Status.SignedOut }
        m.subscribe(LicenseListCallback::class.java) { if (it.result == EResult.OK) licenses.complete(it.licenseList) }
        m.subscribe(AccountInfoCallback::class.java) { _personaName.value = it.personaName }
        pump?.cancel()
        pump = scope.launch {
            while (isActive) {
                // One bad callback must not stop (or crash) the connection's message pump.
                try { m.runWaitCallbackAsync() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    lastBackgroundError = e
                    System.err.println("[steam] callback error: $e")
                }
            }
        }
        return c
    }

    private suspend fun connect(): SteamClient {
        client?.takeIf { it.isConnected }?.let { return it }
        client?.disconnect()
        _status.value = Status.Connecting
        val c = newClient()
        client = c
        playerService = null
        val waiter = CompletableDeferred<Unit>().also { connected = it }
        c.connect()
        try {
            withTimeout(CONNECT_TIMEOUT_MS + 5_000) { waiter.await() }
        } catch (e: Exception) {
            _status.value = if (isSignedIn) Status.Offline else Status.SignedOut
            throw e as? SteamLoginException ?: SteamLoginException("Couldn't connect to Steam. Check your internet connection.", cause = e)
        }
        return c
    }

    private suspend fun logOn(accountName: String, refreshToken: String): SteamClient {
        var attempt = 0
        while (true) {
            val c = connect()
            _status.value = Status.SigningIn
            licenses = CompletableDeferred()
            val waiter = CompletableDeferred<LoggedOnCallback>().also { loggedOn = it }
            c.getHandler(SteamUser::class.java)!!.logOn(
                LogOnDetails(
                    username = accountName,
                    accessToken = refreshToken,
                    shouldRememberPassword = true,
                    machineName = deviceName,
                )
            )
            val cb = withTimeout(LOGON_TIMEOUT_MS) { waiter.await() }
            when (cb.result) {
                EResult.OK -> { _status.value = Status.Online; return c }
                EResult.TryAnotherCM, EResult.ServiceUnavailable, EResult.Timeout -> {
                    c.disconnect()
                    if (++attempt >= 3) throw SteamLoginException("Steam is busy (${cb.result}). Try again in a minute.")
                }
                in EXPIRED_RESULTS -> {
                    store.save(null)
                    _status.value = Status.SignedOut
                    throw SteamLoginException("Your Steam sign-in expired. Sign in again.", needsNewLogin = true)
                }
                else -> {
                    _status.value = Status.Offline
                    throw SteamLoginException("Steam refused the sign-in (${cb.result}).")
                }
            }
        }
    }

    /** Connects and logs on with the saved token if needed. */
    suspend fun ensureOnline(): SteamClient = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = client
            if (c != null && c.isConnected && _status.value == Status.Online) return@withLock c
            if (anonymous) throw SteamLoginException("Anonymous Steam session was disconnected.")
            val login = store.load() ?: throw SteamLoginException("Sign in to Steam first.", needsNewLogin = true)
            logOn(login.accountName, login.refreshToken)
        }
    }

    /**
     * Anonymous logon (like `steamcmd +login anonymous`): no account, only free/anonymous content.
     * Used by the live download test in CI.
     */
    suspend fun signInAnonymous(): SteamClient = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = connect()
            _status.value = Status.SigningIn
            licenses = CompletableDeferred(emptyList())
            val waiter = CompletableDeferred<LoggedOnCallback>().also { loggedOn = it }
            c.getHandler(SteamUser::class.java)!!.logOnAnonymous()
            val cb = withTimeout(LOGON_TIMEOUT_MS) { waiter.await() }
            if (cb.result != EResult.OK) throw SteamLoginException("Anonymous logon failed (${cb.result})")
            anonymous = true
            _status.value = Status.Online
            c
        }
    }

    @Volatile private var anonymous = false

    // endregion

    // region interactive sign-in

    /**
     * QR sign-in: [onQrUrl] receives the URL to show as a QR code (it changes every ~30 s).
     * The player scans it in the Steam mobile app (Steam Guard tab → scan QR). No password is typed here.
     */
    suspend fun signInWithQr(onQrUrl: (String) -> Unit): SavedLogin = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = connect()
            val details = AuthSessionDetails().apply {
                deviceFriendlyName = deviceName
                persistentSession = true
            }
            val qr = c.authentication.beginAuthSessionViaQR(details).await()
            qr.challengeUrlChanged = IChallengeUrlChanged { s -> s?.let { onQrUrl(it.challengeUrl) } }
            onQrUrl(qr.challengeUrl)
            val result = coroutineScope { qr.pollingWaitForResult(this).await() }
            finish(result)
        }
    }

    /** Password sign-in. The password goes RSA-encrypted straight to Steam and is never stored. */
    suspend fun signInWithPassword(username: String, password: String, guard: GuardPrompt): SavedLogin = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = connect()
            val result = coroutineScope {
                val details = AuthSessionDetails().apply {
                    this.username = username.trim()
                    this.password = password
                    deviceFriendlyName = deviceName
                    persistentSession = true
                    authenticator = guard.asAuthenticator(this@coroutineScope)
                }
                val session = try {
                    c.authentication.beginAuthSessionViaCredentials(details, this).await()
                } catch (e: Exception) {
                    throw SteamLoginException(friendlyAuthError(e), cause = e)
                }
                session.pollingWaitForResult(this).await()
            }
            finish(result)
        }
    }

    private suspend fun finish(result: AuthPollResult): SavedLogin {
        val c = logOn(result.accountName, result.refreshToken)
        val saved = SavedLogin(result.accountName, result.refreshToken, c.steamID?.convertToUInt64() ?: 0L)
        store.save(saved)
        return saved
    }

    fun signOut() {
        store.save(null)
        _personaName.value = null
        scope.launch {
            lock.withLock {
                runCatching { client?.getHandler(SteamUser::class.java)?.logOff() }
                runCatching { client?.disconnect() }
                client = null
                _status.value = Status.SignedOut
            }
        }
    }

    // endregion

    // region library

    /** The account's licenses (needed by DepotDownloader to find depot access). */
    suspend fun licenses(): List<License> {
        ensureOnline()
        return withTimeout(LOGON_TIMEOUT_MS) { licenses.await() }
    }

    /**
     * Every game on the account, straight from Steam (works for private profiles).
     * Uses the Player.GetOwnedGames service; falls back to licenses + PICS app info.
     */
    suspend fun ownedGames(): List<OwnedApp> = withContext(Dispatchers.IO) {
        val c = ensureOnline()
        val steamId = c.steamID ?: throw SteamLoginException("Not signed in to Steam.", needsNewLogin = true)
        val fromService = runCatching {
            val player = playerService ?: c.getHandler(SteamUnifiedMessages::class.java)!!.createService(Player::class.java).also { playerService = it }
            val req = CPlayer_GetOwnedGames_Request.newBuilder()
                .setSteamid(steamId.convertToUInt64())
                .setIncludeAppinfo(true)
                .setIncludePlayedFreeGames(true)
                .setLanguage("english")
                .build()
            val resp = withTimeout(LOGON_TIMEOUT_MS) { player.getOwnedGames(req).await() }
            if (resp.result != EResult.OK) emptyList()
            else resp.body.gamesList.map { g -> OwnedApp(g.appid, g.name.ifBlank { "App ${g.appid}" }, g.playtimeForever) }
        }.getOrDefault(emptyList())
        fromService.ifEmpty { ownedFromLicenses(c) }
    }

    /** The Windows launch option Steam uses for [appId] (executable + arguments), from PICS app info. */
    suspend fun launchOption(appId: Int): PicsParse.Launch? = withContext(Dispatchers.IO) {
        val c = ensureOnline()
        val apps = c.getHandler(SteamApps::class.java)!!
        val token = runCatching { withTimeout(PICS_TIMEOUT_MS) { apps.picsGetAccessTokens(listOf(appId), emptyList()).await() }.appTokens[appId] }.getOrNull() ?: 0L
        val rs = withTimeout(PICS_TIMEOUT_MS) { apps.picsGetProductInfo(listOf(PICSRequest(appId, token)), emptyList()).await() }
        rs.results.firstNotNullOfOrNull { it.apps[appId] }?.let { PicsParse.windowsLaunch(it.keyValues) }
    }

    private suspend fun ownedFromLicenses(c: SteamClient): List<OwnedApp> {
        val apps = c.getHandler(SteamApps::class.java)!!
        val lic = licenses()
        val appIds = LinkedHashSet<Int>()
        for (chunk in lic.map { PICSRequest(it.packageID, it.accessToken) }.chunked(PICS_BATCH)) {
            val rs = withTimeout(PICS_TIMEOUT_MS) { apps.picsGetProductInfo(emptyList(), chunk).await() }
            rs.results.forEach { cb -> cb.packages.values.forEach { p -> appIds += PicsParse.packageAppIds(p.keyValues) } }
        }
        if (appIds.isEmpty()) return emptyList()
        val tokens = withTimeout(PICS_TIMEOUT_MS) { apps.picsGetAccessTokens(appIds, emptyList()).await() }.appTokens
        val out = ArrayList<OwnedApp>()
        for (chunk in appIds.map { PICSRequest(it, tokens[it] ?: 0L) }.chunked(PICS_BATCH)) {
            val rs = withTimeout(PICS_TIMEOUT_MS) { apps.picsGetProductInfo(chunk, emptyList()).await() }
            rs.results.forEach { cb -> cb.apps.values.forEach { a -> PicsParse.playableApp(a.id, a.keyValues)?.let(out::add) } }
        }
        return out
    }

    // endregion

    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val LOGON_TIMEOUT_MS = 45_000L
        const val PICS_TIMEOUT_MS = 60_000L
        const val PICS_BATCH = 200
        private val EXPIRED_RESULTS = setOf(
            EResult.InvalidPassword, EResult.AccessDenied, EResult.Expired, EResult.Revoked,
            EResult.InvalidSignature, EResult.AccountLogonDenied, EResult.InvalidLoginAuthCode,
        )

        fun friendlyAuthError(e: Throwable): String {
            val m = (e.cause ?: e).message.orEmpty()
            return when {
                m.contains("InvalidPassword", true) -> "Wrong Steam account name or password."
                m.contains("RateLimitExceeded", true) -> "Too many sign-in attempts. Wait a while and try again."
                m.contains("must be connected", true) -> "Couldn't connect to Steam. Check your internet connection."
                m.isNotBlank() -> "Steam sign-in failed: $m"
                else -> "Steam sign-in failed."
            }
        }
    }
}

/** Adapts the suspend [GuardPrompt] to JavaSteam's future-based [IAuthenticator]. */
internal fun GuardPrompt.asAuthenticator(scope: CoroutineScope): IAuthenticator = object : IAuthenticator {
    override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        scope.future { deviceCode(previousCodeWasIncorrect).trim() }
    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> =
        scope.future { emailCode(email, previousCodeWasIncorrect).trim() }
    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> =
        scope.future { approveOnPhone() }
}

/** PICS KeyValues → app ids / playable apps. Pure so it can be unit-tested. */
object PicsParse {
    private val PLAYABLE_TYPES = setOf("game", "application", "demo")

    fun packageAppIds(pkg: KeyValue): List<Int> =
        pkg["appids"].children.mapNotNull { it.asInteger().takeIf { id -> id > 0 } }

    fun playableApp(appId: Int, app: KeyValue): OwnedApp? {
        val common = app["common"]
        val type = common["type"].asString()?.lowercase() ?: return null
        val name = common["name"].asString()
        if (type !in PLAYABLE_TYPES || name.isNullOrBlank()) return null
        return OwnedApp(appId, name, 0)
    }

    data class Launch(val executable: String, val arguments: String, val workingDir: String)

    /**
     * Picks the launch entry Steam would use on Windows: config/launch/<n> with oslist containing
     * "windows" (or no oslist), preferring the default type and skipping betas and tool entries.
     */
    fun windowsLaunch(app: KeyValue): Launch? {
        val entries = app["config"]["launch"].children.mapNotNull { e ->
            val exe = e["executable"].asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val os = e["config"]["oslist"].asString().orEmpty()
            if (os.isNotBlank() && !os.split(',').any { it.trim() == "windows" }) return@mapNotNull null
            if (e["config"]["betakey"].asString()?.isNotBlank() == true) return@mapNotNull null
            if (!exe.endsWith(".exe", true) && !exe.endsWith(".bat", true)) return@mapNotNull null
            val type = e["type"].asString().orEmpty().lowercase()
            val rank = when (type) { "", "default", "none" -> 0; "option1" -> 1; "vr" -> 2; else -> 3 }
            rank to Launch(exe.replace('\\', '/'), e["arguments"].asString().orEmpty(), e["workingdir"].asString().orEmpty().replace('\\', '/'))
        }
        return entries.minByOrNull { it.first }?.second
    }
}
