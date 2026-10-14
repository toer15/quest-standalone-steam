package com.toer15.queststeam

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.toer15.queststeam.steam.KeylessSteam
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.steam.SteamAccount
import com.toer15.queststeam.steam.SteamOpenId
import com.toer15.queststeam.steamclient.GuardPrompt
import com.toer15.queststeam.steamclient.SteamLoginException
import com.toer15.queststeam.steamclient.SteamSession
import com.toer15.queststeam.ui.LocalDeviceUi
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme
import com.toer15.queststeam.ui.steamBackdrop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Sign-in. Primary: the Steam client protocol (JavaSteam), with password + Steam Guard or a QR
 * code scanned in the Steam mobile app. That sign-in syncs the library from Steam's servers and
 * enables downloads. Fallback: Steam's website (OpenID) for the library only.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        CookieManager.getInstance().setAcceptCookie(true)
        val done = { startActivity(Intent(this, MainActivity::class.java)); finish() }
        setContent { SteamTheme { LoginScreen(onSignedIn = done) } }
    }
}

/** A Steam Guard question waiting for the player. */
sealed interface GuardAsk {
    data class Code(val email: String?, val wrong: Boolean, val answer: CompletableDeferred<String>) : GuardAsk
    data class Phone(val answer: CompletableDeferred<Boolean>) : GuardAsk
}

class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val session = SteamAccount.get(app).session
    val status = session.status
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val qrUrl = MutableStateFlow<String?>(null)
    val guard = MutableStateFlow<GuardAsk?>(null)
    val signedIn = MutableStateFlow(false)
    private var job: Job? = null
    /** Set when the player chose "Enter a code instead" on the phone-approval prompt. */
    private var preferCode = false

    private val prompt = object : GuardPrompt {
        override suspend fun deviceCode(previousWasWrong: Boolean) =
            CompletableDeferred<String>().also { guard.value = GuardAsk.Code(null, previousWasWrong, it) }.await().also { guard.value = null }
        override suspend fun emailCode(email: String?, previousWasWrong: Boolean) =
            CompletableDeferred<String>().also { guard.value = GuardAsk.Code(email ?: "", previousWasWrong, it) }.await().also { guard.value = null }
        override suspend fun approveOnPhone(): Boolean {
            if (preferCode) return false
            // Show "approve on your phone" while polling; the dialog can switch to a code.
            guard.value = GuardAsk.Phone(CompletableDeferred())
            return true
        }
    }

    private var lastCredentials: Pair<String, String>? = null

    fun signIn(user: String, password: String, codeInstead: Boolean = false) {
        if (user.isBlank() || password.isEmpty()) { error.value = "Enter your Steam account name and password."; return }
        preferCode = codeInstead
        lastCredentials = user to password
        run { session.signInWithPassword(user, password, prompt) }
    }

    fun startQr() {
        qrUrl.value = null
        run { session.signInWithQr { qrUrl.value = it } }
    }

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        error.value = null
        busy.value = true
        job = viewModelScope.launch {
            try {
                block()
                signedIn.value = true
            } catch (e: SteamLoginException) {
                error.value = e.message
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = SteamSession.friendlyAuthError(e)
            } finally {
                busy.value = false
                guard.value = null
            }
        }
    }

    /** Restart the password sign-in, asking for a Steam Guard code instead of phone approval. */
    fun useCodeInstead() { lastCredentials?.let { (u, p) -> signIn(u, p, codeInstead = true) } }

    fun cancel() { job?.cancel(); busy.value = false; guard.value = null; qrUrl.value = null }
}

private enum class LoginMode { Password, Qr, Website }

@Composable
private fun LoginScreen(onSignedIn: () -> Unit, vm: LoginViewModel = viewModel()) {
    val ui = LocalDeviceUi.current
    var mode by remember { mutableStateOf(if (ui.isQuest) LoginMode.Qr else LoginMode.Password) }
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val qr by vm.qrUrl.collectAsState()
    val guard by vm.guard.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val status by vm.status.collectAsState()
    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }

    if (mode == LoginMode.Website) {
        Column(Modifier.fillMaxSize().steamBackdrop()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { mode = LoginMode.Password }) { Text("Back") }
                Text("Steam website (library only)", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 700.dp
                Box(Modifier.fillMaxSize().padding(if (wide) 28.dp else 0.dp), contentAlignment = Alignment.Center) {
                    val webMod = if (wide) Modifier.widthIn(max = 560.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)) else Modifier.fillMaxSize()
                    SteamSignInWeb(modifier = webMod, onSignedIn = onSignedIn)
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().steamBackdrop(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = if (ui.isQuest) 620.dp else 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (ui.isQuest) "QUEST STEAM · META QUEST 3" else "QUEST STEAM · PHONE", color = SteamColors.Accent, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("Sign in to Steam", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = ui.welcomeText, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your games sync automatically after you sign in, and you can download them to this device.",
                color = SteamColors.Muted, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)).padding(4.dp)) {
                ModeTab("Account & password", mode == LoginMode.Password) { vm.cancel(); mode = LoginMode.Password }
                ModeTab("QR code", mode == LoginMode.Qr) { vm.cancel(); mode = LoginMode.Qr }
            }
            Spacer(Modifier.height(20.dp))
            when (mode) {
                LoginMode.Password -> PasswordForm(busy, status) { u, p -> vm.signIn(u, p) }
                LoginMode.Qr -> QrPanel(qr, busy, onStart = vm::startQr)
                LoginMode.Website -> Unit
            }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = SteamColors.Danger, textAlign = TextAlign.Center, fontSize = 14.sp)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Library only? Sign in on Steam's website",
                color = SteamColors.Muted, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.cancel(); mode = LoginMode.Website }.padding(10.dp),
            )
            Text(
                "Your password goes encrypted straight to Steam and is never stored. The app keeps Steam's sign-in token, like the Steam client's \"Remember me\".",
                color = SteamColors.Muted.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp,
            )
        }
    }

    when (val g = guard) {
        is GuardAsk.Code -> GuardCodeDialog(g, onCancel = vm::cancel)
        is GuardAsk.Phone -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Approve on your phone") },
            text = { Text("Open the Steam app on your phone and approve this sign-in. It continues here automatically.") },
            confirmButton = { TextButton(onClick = vm::useCodeInstead) { Text("Enter a code instead") } },
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        color = if (selected) Color(0xFF0E141B) else SteamColors.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) SteamColors.Accent else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SteamColors.Accent, unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
    focusedTextColor = SteamColors.Text, unfocusedTextColor = SteamColors.Text, cursorColor = SteamColors.Accent,
    focusedLabelColor = SteamColors.Accent, unfocusedLabelColor = SteamColors.Muted,
)

@Composable
private fun PasswordForm(busy: Boolean, status: SteamSession.Status, onSubmit: (String, String) -> Unit) {
    val ui = LocalDeviceUi.current
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    OutlinedTextField(
        value = user, onValueChange = { user = it }, singleLine = true, enabled = !busy,
        label = { Text("Steam account name") }, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = pass, onValueChange = { pass = it }, singleLine = true, enabled = !busy,
        label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(16.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(user, pass) }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(ui.buttonHeight + 4.dp), shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF0E141B), strokeWidth = 2.dp)
            Text("  " + statusText(status), fontWeight = FontWeight.Bold)
        } else Text("Sign in", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

private fun statusText(s: SteamSession.Status) = when (s) {
    SteamSession.Status.Connecting -> "Connecting to Steam…"
    SteamSession.Status.SigningIn -> "Signing in…"
    SteamSession.Status.Online -> "Signed in"
    else -> "Waiting for Steam…"
}

@Composable
private fun QrPanel(url: String?, busy: Boolean, onStart: () -> Unit) {
    val ui = LocalDeviceUi.current
    if (!busy && url == null) {
        Text(
            "Scan a QR code with the Steam app on your phone: open Steam → Steam Guard (shield) → scan a QR code.",
            color = SteamColors.Text, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart, modifier = Modifier.fillMaxWidth().height(ui.buttonHeight + 4.dp), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
        ) { Text("Show QR code", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        return
    }
    val side = if (ui.isQuest) 300.dp else 240.dp
    Box(Modifier.size(side + 28.dp).clip(RoundedCornerShape(24.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (url == null) CircularProgressIndicator(color = SteamColors.Bg)
        else QrCode(url, Modifier.size(side))
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "In the Steam app: Steam Guard (shield) → scan this code → approve. The code refreshes by itself.",
        color = SteamColors.Muted, textAlign = TextAlign.Center, fontSize = 14.sp, lineHeight = 20.sp,
    )
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun GuardCodeDialog(ask: GuardAsk.Code, onCancel: () -> Unit) {
    var code by remember(ask) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Steam Guard code") },
        text = {
            Column {
                Text(
                    when {
                        ask.wrong -> "That code didn't work. Try the newest one."
                        ask.email != null -> "Enter the code Steam emailed to ${ask.email.ifBlank { "you" }}."
                        else -> "Enter the code from the Steam app on your phone (Steam Guard)."
                    },
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().take(8) }, singleLine = true,
                    label = { Text("Code") }, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { ask.answer.complete(code) }, enabled = code.length >= 5) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SteamSignInWeb(modifier: Modifier, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(SteamColors.Bg),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF0E141B.toInt())
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var done = false

                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val url = req.url.toString()
                            if (!url.startsWith(SteamOpenId.RETURN_TO)) return false
                            scope.launch {
                                val id = SteamOpenId.verify(req.url)
                                if (id != null) {
                                    SessionStore(ctx).steamId = id
                                    finishing = true
                                    view.loadUrl(KeylessSteam.GAMES_PAGE)
                                } else {
                                    Toast.makeText(ctx, "Steam login could not be verified", Toast.LENGTH_LONG).show()
                                    view.loadUrl(SteamOpenId.loginUrl())
                                }
                            }
                            return true
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!finishing || done || !request.isForMainFrame) return
                            finishing = false
                            Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                            view.loadUrl(SteamOpenId.loginUrl())
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!finishing || done || url.contains("openid")) return
                            val path = android.net.Uri.parse(url).path ?: return
                            if (path.contains("login")) {
                                finishing = false
                                Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                                view.loadUrl(SteamOpenId.loginUrl())
                                return
                            }
                            if (!path.contains("games")) return
                            val cm = CookieManager.getInstance()
                            cm.flush()
                            val cookie = cm.getCookie("https://steamcommunity.com")
                            if (KeylessSteam.hasSession(cookie)) {
                                done = true
                                onSignedIn()
                            } else {
                                finishing = false
                                Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                                view.loadUrl(SteamOpenId.loginUrl())
                            }
                        }
                    }
                    loadUrl(SteamOpenId.loginUrl())
                }
            },
        )
        if (finishing) Box(Modifier.fillMaxSize().background(Color(0xF00E141B)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = SteamColors.Accent)
                Spacer(Modifier.height(16.dp))
                Text("Signing you in…", color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}
