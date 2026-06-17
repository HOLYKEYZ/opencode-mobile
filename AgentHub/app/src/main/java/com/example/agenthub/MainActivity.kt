package com.example.agenthub

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.agenthub.theme.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AgentInfo(val id: String, val name: String)
data class LogLine(val id: Long, val text: String, val type: String = "append")
data class RemoteSession(val agent: String, val id: String, val title: String, val subtitle: String, val updatedAt: Long = 0)
data class PendingAttachment(val name: String, val mime: String, val base64: String, val size: Int)

val AGENT_NAMES = mapOf("opencode" to "OpenCode", "devin" to "Devin", "system" to "system")
const val MAX_DETAIL_MESSAGES_ON_PHONE = 40
const val MAX_DETAIL_MESSAGE_CHARS = 3000
const val MAX_DETAIL_TOTAL_CHARS = 60000
const val MAX_RENDERED_LOG_LINES = 90
const val MAX_PERSISTED_TRANSCRIPT_CHARS = 60000

fun parseRemoteSessions(raw: String): List<RemoteSession> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            RemoteSession(
                agent = obj.optString("agent"),
                id = id,
                title = obj.optString("title", id),
                subtitle = obj.optString("subtitle", obj.optString("directory", "")),
                updatedAt = obj.optLong("updatedAt", 0)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun remoteSessionsToJson(sessions: List<RemoteSession>): String {
    val arr = JSONArray()
    sessions.forEach { session ->
        arr.put(JSONObject().apply {
            put("agent", session.agent)
            put("id", session.id)
            put("title", session.title)
            put("subtitle", session.subtitle)
            put("updatedAt", session.updatedAt)
        })
    }
    return arr.toString()
}

class MainActivity : ComponentActivity() {
    private val deepLinkState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        deepLinkState.value = intent?.data?.toString() ?: ""
        setContent {
            AgentHubTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = OpenCodeBlack) {
                    AgentHubScreen(initialDeepLink = deepLinkState.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkState.value = intent.data?.toString() ?: ""
    }
}

@Composable
fun CrashFallback(message: String) {
    Box(modifier = Modifier.fillMaxSize().background(OpenCodeBlack).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("OC-mob hit an error", color = OpenCodeTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color(0xFFEF4444), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Text("Close and reopen the app, then use Settings to reconnect.", color = OpenCodeTextSecondary, fontSize = 13.sp)
        }
    }
}

object OkHttpAgent {
    val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHubScreen(initialDeepLink: String = "") {
    val context = LocalContext.current
    val rootView = LocalView.current
    val prefs = remember { context.getSharedPreferences("OCmobPrefs", Context.MODE_PRIVATE) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(rootView, context) {
        rootView.keepScreenOn = true
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            rootView.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    var input by remember { mutableStateOf("") }
    var logs by remember {
        mutableStateOf(
            restoreTranscriptLogs(prefs.getString("LAST_TRANSCRIPT", "").orEmpty())
        )
    }
    var wsStatus by remember { mutableStateOf("connecting") }
    var webSocket by remember { mutableStateOf<WebSocket?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var relayOnline by remember { mutableStateOf(false) }
    var currentAgent by remember { mutableStateOf(prefs.getString("CURRENT_AGENT", "opencode") ?: "opencode") }
    var availableAgents by remember { mutableStateOf(listOf<String>()) }
    var sessions by remember { mutableStateOf(parseRemoteSessions(prefs.getString("LAST_SESSIONS", "") ?: "")) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsNotice by remember { mutableStateOf("") }
    var selectedSessionId by remember { mutableStateOf(prefs.getString("SELECTED_SESSION_ID", "") ?: "") }
    var selectedSessionTitle by remember { mutableStateOf(prefs.getString("SELECTED_SESSION_TITLE", "") ?: "") }
    var selectedSessionSubtitle by remember { mutableStateOf("") }
    var agentModels by remember { mutableStateOf(listOf<String>()) }
    var currentModel by remember { mutableStateOf("") }
    var tokenUsage by remember { mutableStateOf(prefs.getString("TOKEN_USAGE", "") ?: "") }
    var connectionSeq by remember { mutableStateOf(0L) }
    var hasPausedOnce by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<PendingAttachment>()) }
    var promptRunning by remember { mutableStateOf(false) }
    var promptInFlight by remember { mutableStateOf(false) }
    var lastConnectAttemptAt by remember { mutableStateOf(0L) }
    var showTechnicalEvents by remember { mutableStateOf(prefs.getBoolean("SHOW_TECHNICAL_EVENTS", false)) }
    var stickToBottom by remember { mutableStateOf(true) }
    var viewMode by remember { mutableStateOf("list") } // "list" or "chat"

    var sessionCode by remember { mutableStateOf(prefs.getString("SESSION_CODE", "") ?: "") }
    var serverUrl by remember { mutableStateOf(prefs.getString("SERVER_URL", "wss://agent-hub-backend-wk48.onrender.com") ?: "wss://agent-hub-backend-wk48.onrender.com") }

    val agentName = AGENT_NAMES[currentAgent] ?: currentAgent
    val scrollScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = listOf(input, spoken).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    fun displayNameForUri(uri: android.net.Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: uri.lastPathSegment ?: "upload"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "upload"
        }
    }

    fun readUriBytesBounded(uri: android.net.Uri, maxBytes: Int): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalArgumentException("${displayNameForUri(uri)} is over ${maxBytes / 1024 / 1024} MB")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        throw IllegalArgumentException("Could not open ${displayNameForUri(uri)}")
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val added = mutableListOf<PendingAttachment>()
        for (uri in uris.take(10)) {
            try {
                val bytes = readUriBytesBounded(uri, 8 * 1024 * 1024)
                added += PendingAttachment(
                    name = displayNameForUri(uri),
                    mime = context.contentResolver.getType(uri) ?: "application/octet-stream",
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    size = bytes.size
                )
            } catch (e: Exception) {
                logs = logs + LogLine(System.currentTimeMillis(), "Error: Could not attach ${uri.lastPathSegment ?: "file"} (${e.message})")
            }
        }
        if (added.isNotEmpty()) {
            attachments = (attachments + added).takeLast(10)
            logs = logs + LogLine(System.currentTimeMillis(), "Attached ${added.size} file(s) from phone", "file")
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Prompt $agentName")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            logs = logs + LogLine(System.currentTimeMillis(), "Error: Voice input unavailable (${e.message})")
        }
    }

    fun stripAnsi(str: String): String {
        return str
            .replace(Regex("\\u001B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))"), "")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .replace("\r", "")
    }

    fun requestSessions(agent: String = "", socket: WebSocket? = webSocket) {
        val j = JSONObject()
        j.put("type", "session_list")
        if (agent.isNotBlank()) j.put("agent", agent)
        sessionsLoading = true
        sessionsNotice = "Loading chats..."
        if (socket?.send(j.toString()) != true) {
            sessionsLoading = false
            sessionsNotice = "Could not request chats. Reconnect the relay."
        }
    }

    fun requestSessionDetail(session: RemoteSession, socket: WebSocket? = webSocket) {
        if (session.id.isBlank()) return
        val j = JSONObject()
        j.put("type", "session_detail")
        j.put("agent", session.agent)
        j.put("sessionId", session.id)
        socket?.send(j.toString())
    }

    fun parseSessions(arr: JSONArray): List<RemoteSession> {
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            RemoteSession(
                agent = obj.optString("agent"),
                id = id,
                title = obj.optString("title", id),
                subtitle = obj.optString("subtitle", obj.optString("directory", "")),
                updatedAt = obj.optLong("updatedAt", 0)
            )
        }
    }

    fun consolidatedLogs(): List<LogLine> = consolidateLogs(logs)
    fun visibleTranscript(): String = visibleTranscriptFrom(logs)
    fun appendLog(line: LogLine) { logs = appendBoundedLog(logs, line) }

    fun statusLogType(text: String): String {
        val value = text.trim()
        return when {
            value.startsWith("tokens:") -> "status"
            value.startsWith("command:") || value.startsWith("tool:") || value.startsWith("web_search") ||
                value.startsWith("tool_search") || value.startsWith("mcp_") || value.startsWith("patch_") ||
                value.startsWith("thinking") || value.startsWith("browser/search") ||
                value.startsWith("command output:") -> "tool"
            value.startsWith("file:") || value.startsWith("files:") || value.startsWith("file diff:") -> "file"
            else -> "status"
        }
    }

    fun afterPrefix(raw: String): String = raw.substringAfter(':', raw).trim()

    fun compactPathList(raw: String): String {
        val items = raw.lines()
            .map { it.substringAfter(':', it).trim() }
            .filter { it.isNotBlank() }
            .map { it.replace("\\", "/").substringAfterLast("/") }
            .distinct()
            .take(6)
        return items.joinToString(", ")
    }

    fun detailExtraLogs(detail: JSONObject?, startId: Long): List<LogLine> {
        if (detail == null) return emptyList()
        val out = mutableListOf<LogLine>()
        var next = startId
        val commands = detail.optJSONArray("commands")
        if (commands != null && commands.length() > 0) {
            out += LogLine(next++, "latest turn commands: ${commands.length()}", "tool")
        }
        val tools = detail.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            val names = (0 until tools.length()).mapNotNull { i ->
                tools.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }.distinct().take(6)
            out += LogLine(next++, if (names.isEmpty()) "latest turn tools: ${tools.length()}" else "latest turn tools: ${names.joinToString(", ")}", "tool")
        }
        val files = detail.optJSONArray("files")
        if (files != null && files.length() > 0) {
            out += LogLine(next++, "latest turn files: ${files.length()}", "file")
        }
        val diff = detail.optJSONArray("diff")
        if (diff != null && diff.length() > 0) {
            out += LogLine(next++, "files changed: ${diff.length()} diff item(s)", "file")
        }
        val todo = detail.optJSONArray("todo")
        if (todo != null && todo.length() > 0) {
            out += LogLine(next++, "todo: ${todo.length()} item(s)", "tool")
        }
        return out
    }

    fun statusForPhone(content: String): LogLine? {
        val raw = content.trim()
        if (raw.isBlank()) return null
        val type = statusLogType(raw)
        if (showTechnicalEvents) return LogLine(System.currentTimeMillis(), raw, type)
        val lower = raw.lowercase()
        val summary = when {
            lower.startsWith("command output:") -> null
            lower.startsWith("tokens:") -> raw
            lower.startsWith("command:") -> "Command: ${afterPrefix(raw).take(180)}"
            lower.startsWith("tool:") -> "Tool: ${afterPrefix(raw).take(140)}"
            lower.startsWith("mcp_") || lower.startsWith("patch_") -> "Tool: ${raw.take(140)}"
            lower.startsWith("web_search") || lower.startsWith("tool_search") || lower.contains("browser") -> "Using browser/search"
            lower.startsWith("file diff:") || lower.startsWith("file:") || lower.startsWith("files:") -> {
                val files = compactPathList(raw)
                if (files.isBlank()) "Editing files" else "Files: $files"
            }
            lower.startsWith("thinking") -> "Thinking"
            lower.startsWith("sending to opencode session") -> "Sending to selected OpenCode session"
            lower.startsWith("opencode accepted prompt") -> "OpenCode accepted prompt"
            lower.startsWith("using most recent") -> "Using most recent chat"
            lower.startsWith("created new opencode session") -> "Created new OpenCode session"
            lower.startsWith("starting new") -> "Starting new session"
            else -> raw
        } ?: return null
        return LogLine(System.currentTimeMillis(), summary, type)
    }

    fun openUpdatePage() {
        val base = serverUrl.trim()
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')
        if (base.isBlank()) {
            appendLog(LogLine(System.currentTimeMillis(), "Error: Server URL is empty"))
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/download")))
        } catch (e: Exception) {
            appendLog(LogLine(System.currentTimeMillis(), "Error: Could not open update page (${e.message})"))
        }
    }

    fun copyVisibleTranscript() {
        val text = visibleTranscript()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OC-mob transcript", text))
        logs = logs + LogLine(System.currentTimeMillis(), "Copied visible transcript")
    }

    fun compactLocalPaths(text: String): String {
        return text.replace(Regex("[A-Za-z]:\\\\\\\\(?:[^\\\\\\\\n]+\\\\\\\\)+([^\\\\\\\\n)]+)")) {
            it.groupValues[1]
        }
    }

    fun hideAgentDirectives(text: String): String {
        return text.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("::") && trimmed.contains("{") && trimmed.endsWith("}")
            }
            .joinToString("\n")
            .trim()
    }

    fun compactChatText(text: String): String {
        val cleaned = hideAgentDirectives(compactLocalPaths(stripAnsi(text).trim()))
        val looksLikeTerminalPaste = cleaned.contains("Windows PowerShell") ||
            cleaned.contains("node relay.js") ||
            cleaned.contains("════════") ||
            cleaned.contains("Relay session:")
        if (looksLikeTerminalPaste && cleaned.length > 1200) {
            return "[long terminal paste hidden in chat view; see the desktop agent chat for the original paste]"
        }
        if (cleaned.length <= MAX_DETAIL_MESSAGE_CHARS) return cleaned
        return cleaned.take(MAX_DETAIL_MESSAGE_CHARS) + "\n\n[message truncated on phone]"
    }

    fun detailMessageLogs(messages: JSONArray?): List<LogLine> {
        if (messages == null || messages.length() == 0) return emptyList()
        val start = maxOf(0, messages.length() - MAX_DETAIL_MESSAGES_ON_PHONE)
        val out = mutableListOf<LogLine>()
        var totalChars = 0
        if (start > 0) {
            out += LogLine(System.currentTimeMillis(), "Showing latest ${messages.length() - start} messages. Older history is hidden on phone to keep the app responsive.", "status")
        }
        for (i in start until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "message")
            if (role != "user" && role != "assistant") continue
            val text = compactChatText(m.optString("text", ""))
            if (text.isBlank()) continue
            totalChars += text.length
            if (totalChars > MAX_DETAIL_TOTAL_CHARS) {
                out += LogLine(System.currentTimeMillis() + i, "More history hidden on phone to keep the app responsive.", "status")
                break
            }
            out += LogLine(System.currentTimeMillis() + i, text, role)
        }
        return out
    }

    fun formatRelativeTime(updatedAt: Long): String {
        if (updatedAt <= 0) return ""
        val diff = System.currentTimeMillis() - updatedAt
        return when {
            diff < 60_000 -> "now"
            diff < 3_600_000 -> "${diff / 60_000}m"
            diff < 86_400_000 -> "${diff / 3_600_000}h"
            diff < 604_800_000 -> "${diff / 86_400_000}d"
            else -> "${diff / 604_800_000}w"
        }
    }

    val connectWs = connectWs@{ urlOverride: String?, codeOverride: String? ->
        val targetServerUrl = (urlOverride ?: serverUrl).trim()
        val targetSessionCode = codeOverride ?: sessionCode
        val now = System.currentTimeMillis()
        if (targetServerUrl.isBlank()) {
            wsStatus = "disconnected"
            logs = logs + LogLine(System.currentTimeMillis(), "Error: Server URL is empty")
            return@connectWs
        }
        if (wsStatus == "connecting" && now - lastConnectAttemptAt < 2500) return@connectWs
        lastConnectAttemptAt = now
        connectionSeq += 1
        val seq = connectionSeq
        wsStatus = "connecting"
        webSocket?.close(1000, "Reconnecting")
        val request = try {
            Request.Builder().url(targetServerUrl).build()
        } catch (e: Exception) {
            wsStatus = "disconnected"
            logs = logs + LogLine(System.currentTimeMillis(), "Error: Invalid server URL (${e.message})")
            null
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onUi {
                    if (seq != connectionSeq) return@onUi
                    webSocket = ws
                    wsStatus = "connected"
                    logs = logs + LogLine(System.currentTimeMillis(), "Connected to server")
                    if (targetSessionCode.isNotBlank()) {
                        val j = JSONObject(); j.put("type", "join_session"); j.put("code", targetSessionCode)
                        ws.send(j.toString())
                    } else {
                        logs = logs + LogLine(System.currentTimeMillis(), "Enter a session code in Settings")
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    onUi {
                        if (seq != connectionSeq) return@onUi
                        when (json.optString("type")) {
                            "session_joined" -> {
                                relayOnline = json.optBoolean("relay_online", false)
                                val agents = json.optJSONArray("available_agents")
                                availableAgents = if (agents != null) (0 until agents.length()).map { agents.getString(it) } else emptyList()
                                if (currentAgent.isBlank() && availableAgents.isNotEmpty()) {
                                    currentAgent = availableAgents.first()
                                    prefs.edit().putString("CURRENT_AGENT", currentAgent).apply()
                                }
                                if (currentAgent.isNotBlank()) {
                                    appendLog(LogLine(System.currentTimeMillis(),
                                        if (relayOnline) "$agentName ready (relay code $targetSessionCode)" else "$agentName waiting for desktop relay on code $targetSessionCode"))
                                    val m = json.optJSONObject("agent_model")
                                    if (m != null && m.has(currentAgent)) currentModel = m.getString(currentAgent)
                                    val models = json.optJSONObject("available_models")
                                    if (models != null && models.has(currentAgent)) {
                                        val arr = models.optJSONArray(currentAgent)
                                        if (arr != null) agentModels = (0 until arr.length()).map { arr.getString(it) }
                                    }
                                } else {
                                    appendLog(LogLine(System.currentTimeMillis(), "Connected (scan QR or set agent in settings)"))
                                }
                                if (relayOnline) {
                                    mainHandler.postDelayed({
                                        onUi {
                                            if (seq == connectionSeq && relayOnline) requestSessions("", webSocket)
                                        }
                                    }, 600)
                                }
                            }
                            "sessions" -> {
                                val rawArr = json.optJSONArray("sessions") ?: JSONArray()
                                sessions = parseSessions(rawArr)
                                sessionsLoading = false
                                sessionsNotice = if (sessions.isEmpty()) "No saved chats found." else ""
                                prefs.edit().putString("LAST_SESSIONS", remoteSessionsToJson(sessions.take(200))).apply()
                                sessions.firstOrNull { it.id == selectedSessionId }?.let {
                                    selectedSessionTitle = it.title
                                    selectedSessionSubtitle = it.subtitle
                                    prefs.edit().putString("SELECTED_SESSION_TITLE", it.title).apply()
                                }
                            }
                            "session_detail" -> {
                                val detail = json.optJSONObject("detail")
                                val messages = detail?.optJSONArray("messages")
                                val chatLogs = detailMessageLogs(messages)
                                val extras = if (showTechnicalEvents) detailExtraLogs(detail, System.currentTimeMillis() + 10000) else emptyList()
                                if (chatLogs.isNotEmpty() || extras.isNotEmpty()) {
                                    logs = chatLogs + extras
                                    viewMode = "chat"
                                }
                                val detailActive = detail?.optString("status") == "active"
                                promptRunning = detailActive || promptInFlight
                            }
                            "config_updated" -> {
                                val cfg = json.optJSONObject("config")
                                if (cfg != null && currentAgent.isNotBlank()) {
                                    val modelKey = currentAgent.uppercase() + "_MODEL"
                                    if (cfg.has(modelKey)) currentModel = cfg.getString(modelKey)
                                }
                            }
                            "stream" -> {
                                promptRunning = true
                                logs = logs + LogLine(System.currentTimeMillis(), compactChatText(json.optString("content")), "assistant")
                            }
                            "replace_stream" -> {
                                promptRunning = true
                                logs = logs + LogLine(System.currentTimeMillis(), compactChatText(json.optString("content")), "replace")
                            }
                            "status", "system" -> {
                                val content = json.optString("content")
                                val lower = content.lowercase(Locale.ROOT)
                                if (lower.startsWith("tokens:")) {
                                    tokenUsage = content.trim()
                                    prefs.edit().putString("TOKEN_USAGE", tokenUsage).apply()
                                }
                                if (lower.contains("starting") || lower.contains("steering") || lower.contains("working") || lower.contains("accepted prompt") || lower.contains("devin") || lower.contains("opencode")) {
                                    promptRunning = true
                                } else if (lower.contains("finished") || lower.contains("completed") || lower.contains("exited") || lower.contains("done")) {
                                    if (!promptInFlight) promptRunning = false
                                }
                                statusForPhone(content)?.let { appendLog(it) }
                            }
                            "done" -> {
                                promptInFlight = false
                                promptRunning = false
                                val c = json.optString("content"); if (c.isNotBlank()) logs = logs + LogLine(System.currentTimeMillis(), c)
                                if (relayOnline && viewMode == "list") requestSessions("", webSocket)
                            }
                            "error" -> {
                                promptInFlight = false
                                promptRunning = false
                                val content = json.optString("content")
                                logs = logs + LogLine(System.currentTimeMillis(), "Error: $content")
                                if (content.contains("Session expired", ignoreCase = true) && sessionCode.isNotBlank()) {
                                    wsStatus = "disconnected"
                                    try { webSocket?.close(1000, "Session expired; retrying") } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    onUi { appendLog(LogLine(System.currentTimeMillis(), "Msg parse error: ${e.message}")) }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) { onUi { if (seq == connectionSeq) wsStatus = "disconnected" } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onUi {
                    if (seq != connectionSeq) return@onUi
                    wsStatus = "disconnected"
                    logs = logs + LogLine(System.currentTimeMillis(), "Error: ${t.message ?: "Connection failed"}")
                }
            }
        }
        if (request != null) webSocket = OkHttpAgent.client.newWebSocket(request, listener)
    }

    DisposableEffect(Unit) {
        if (initialDeepLink.isBlank()) connectWs(null, null)
        onDispose { webSocket?.close(1000, "Closing") }
    }

    DisposableEffect(lifecycleOwner, serverUrl, sessionCode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                hasPausedOnce = true
            } else if (event == Lifecycle.Event.ON_RESUME && hasPausedOnce && sessionCode.isNotBlank()) {
                if (wsStatus == "connected") {
                    if (relayOnline && viewMode == "list") requestSessions("", webSocket)
                    sessions.firstOrNull { it.id == selectedSessionId }?.let { requestSessionDetail(it, webSocket) }
                } else {
                    connectWs(null, null)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3
        }
    }

    LaunchedEffect(isNearBottom) { stickToBottom = isNearBottom }

    LaunchedEffect(logs) {
        if (logs.size > MAX_RENDERED_LOG_LINES * 2) {
            logs = logs.takeLast(MAX_RENDERED_LOG_LINES * 2)
            return@LaunchedEffect
        }
        val text = visibleTranscript()
        prefs.edit().putString("LAST_TRANSCRIPT", text.takeLast(MAX_PERSISTED_TRANSCRIPT_CHARS)).apply()
        val renderedCount = consolidatedLogs().size
        if (renderedCount > 0 && stickToBottom) {
            try { listState.scrollToItem(renderedCount - 1) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(relayOnline, viewMode, webSocket) {
        val socket = webSocket
        if (relayOnline && viewMode == "list" && socket != null) {
            delay(600)
            if (sessions.isEmpty()) requestSessions("", socket)
            delay(2400)
            if (sessions.isEmpty()) requestSessions("", socket)
        }
    }

    LaunchedEffect(wsStatus, sessionCode, serverUrl) {
        if (wsStatus == "disconnected" && sessionCode.isNotBlank()) {
            val seqAtSchedule = connectionSeq
            delay(3000)
            if (connectionSeq != seqAtSchedule) return@LaunchedEffect
            if (wsStatus == "disconnected") connectWs(null, null)
        }
    }

    val sendMsg = {
        if ((input.isNotBlank() || attachments.isNotEmpty()) && wsStatus == "connected" && currentAgent.isNotBlank()) {
            val promptText = input.ifBlank { "Please inspect the attached file(s)." }
            logs = logs + LogLine(System.currentTimeMillis(), promptText, "user")
            val j = JSONObject(); j.put("agent", currentAgent); j.put("prompt", promptText)
            if (selectedSessionId.isNotBlank()) j.put("sessionId", selectedSessionId)
            if (currentModel.isNotBlank()) j.put("model", currentModel)
            if (attachments.isNotEmpty()) {
                val arr = JSONArray()
                attachments.forEach { file ->
                    arr.put(JSONObject().apply {
                        put("name", file.name)
                        put("mime", file.mime)
                        put("base64", file.base64)
                        put("size", file.size)
                    })
                }
                j.put("attachments", arr)
            }
            if (webSocket?.send(j.toString()) == true) {
                promptInFlight = true
                promptRunning = true
                input = ""
                attachments = emptyList()
            } else {
                logs = logs + LogLine(System.currentTimeMillis(), "Error: Could not send prompt. Reconnect the relay.")
            }
        }
    }

    val onQrScanned = { raw: String ->
        showQrScanner = false
        try {
            val uri = java.net.URI(if (raw.startsWith("ws") || raw.startsWith("wss")) raw else "ws://x/$raw")
            val query = uri.query ?: ""
            val params = query.split("&").filter { it.isNotBlank() }.associate { kv ->
                kv.split("=", limit=2).let {
                    java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8")
                }
            }
            val nextSessionCode = params["code"] ?: sessionCode
            val nextAgent = params["agent"] ?: currentAgent
            var nextServerUrl = serverUrl
            if (params.containsKey("code")) sessionCode = nextSessionCode
            if (params.containsKey("agent")) currentAgent = nextAgent
            selectedSessionId = ""
            selectedSessionTitle = ""
            selectedSessionSubtitle = ""
            if (raw.startsWith("ws") || raw.startsWith("wss")) {
                val port = if (uri.port > 0) ":${uri.port}" else ""
                nextServerUrl = "${uri.scheme}://${uri.host}$port"
                serverUrl = nextServerUrl
            }
            prefs.edit().putString("SESSION_CODE", sessionCode).putString("SERVER_URL", serverUrl)
                .putString("CURRENT_AGENT", currentAgent).putString("SELECTED_SESSION_ID", "").putString("SELECTED_SESSION_TITLE", "").apply()
            connectWs(nextServerUrl, nextSessionCode)
        } catch (_: Exception) {
            sessionCode = raw
            prefs.edit().putString("SESSION_CODE", raw).apply(); connectWs(null, raw)
        }
    }

    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink.isNotBlank()) onQrScanned(initialDeepLink)
    }

    val isConnected = wsStatus == "connected"
    val canPrompt = isConnected && currentAgent.isNotBlank()
    val hasDraft = input.isNotBlank() || attachments.isNotEmpty()
    val canSubmit = canPrompt && hasDraft

    if (showQrScanner) { QrScanner(onScan = onQrScanned, onCancel = { showQrScanner = false }); return }

    if (showSettings) {
        SettingsDialog(
            sessionCode = sessionCode,
            onSessionCodeChange = { sessionCode = it },
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            currentAgent = currentAgent,
            onCurrentAgentChange = { currentAgent = it },
            tokenUsage = tokenUsage,
            showTechnicalEvents = showTechnicalEvents,
            onShowTechnicalEventsChange = {
                showTechnicalEvents = it
                prefs.edit().putBoolean("SHOW_TECHNICAL_EVENTS", it).apply()
            },
            onScanQr = { showSettings = false; showQrScanner = true },
            onOpenUpdatePage = { openUpdatePage() },
            onSave = {
                prefs.edit().putString("SESSION_CODE", sessionCode).putString("SERVER_URL", serverUrl)
                    .putString("CURRENT_AGENT", currentAgent).putBoolean("SHOW_TECHNICAL_EVENTS", showTechnicalEvents).apply()
                showSettings = false; connectWs(null, null)
            },
            onCancel = { showSettings = false }
        )
    }

    if (showModelPicker && agentModels.isNotEmpty()) {
        ModelPickerDialog(
            models = agentModels,
            currentModel = currentModel,
            onSelect = { model ->
                val j = JSONObject(); j.put("type", "select_model")
                j.put("agent", currentAgent); j.put("model", model)
                currentModel = model
                webSocket?.send(j.toString()); showModelPicker = false
            },
            onCancel = { showModelPicker = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(OpenCodeBlack)) {
        when {
            !isConnected -> ConnectScreen(
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                sessionCode = sessionCode,
                onSessionCodeChange = { sessionCode = it },
                onConnect = { connectWs(null, null) },
                onScanQr = { showQrScanner = true }
            )
            viewMode == "list" -> ChatListScreen(
                agentName = agentName,
                relayOnline = relayOnline,
                sessions = sessions,
                sessionsLoading = sessionsLoading,
                sessionsNotice = sessionsNotice,
                currentAgent = currentAgent,
                onSettings = { showSettings = true },
                onRefresh = { requestSessions("") },
                onNewChat = {
                    selectedSessionId = ""
                    selectedSessionTitle = ""
                    selectedSessionSubtitle = ""
                    logs = emptyList()
                    viewMode = "chat"
                },
                onSelectSession = { session ->
                    currentAgent = session.agent
                    selectedSessionId = session.id
                    selectedSessionTitle = session.title
                    selectedSessionSubtitle = session.subtitle
                    prefs.edit().putString("CURRENT_AGENT", session.agent).putString("SELECTED_SESSION_ID", session.id)
                        .putString("SELECTED_SESSION_TITLE", session.title).putString("LAST_TRANSCRIPT", "").apply()
                    logs = emptyList()
                    viewMode = "chat"
                    requestSessionDetail(session)
                },
                formatRelativeTime = { formatRelativeTime(it) }
            )
            else -> ChatScreen(
                title = selectedSessionTitle.ifBlank { agentName },
                subtitle = selectedSessionSubtitle.ifBlank { if (relayOnline) "Connected" else "Relay offline" },
                agentName = agentName,
                currentModel = currentModel,
                agentModels = agentModels,
                onModelPicker = { showModelPicker = true },
                logs = logs,
                listState = listState,
                isNearBottom = isNearBottom,
                onScrollToBottom = {
                    scrollScope.launch {
                        val count = consolidatedLogs().size
                        stickToBottom = true
                        if (count > 0) {
                            listState.scrollToItem(count - 1)
                            delay(80)
                            listState.animateScrollToItem(count - 1)
                        }
                    }
                },
                onCopyTranscript = { copyVisibleTranscript() },
                onBack = {
                    viewMode = "list"
                    if (relayOnline) requestSessions("")
                },
                onSettings = { showSettings = true },
                input = input,
                onInputChange = { input = it },
                attachments = attachments,
                onAttach = { fileLauncher.launch("*/*") },
                onVoice = { startVoiceInput() },
                canPrompt = canPrompt,
                canSubmit = canSubmit,
                promptRunning = promptRunning,
                onSend = { sendMsg() }
            )
        }
    }
}

@Composable
fun ConnectScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    sessionCode: String,
    onSessionCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    onScanQr: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(OpenCodeBlack).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(OpenCodeSurface),
            contentAlignment = Alignment.Center
        ) {
            Text("OC", color = OpenCodeGreen, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text("OC-mob", color = OpenCodeTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Remote OpenCode from your phone", color = OpenCodeTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("wss://host:port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OpenCodeGreen,
                focusedLabelColor = OpenCodeGreen,
                unfocusedBorderColor = OpenCodeBorder,
                unfocusedTextColor = OpenCodeTextPrimary,
                focusedTextColor = OpenCodeTextPrimary
            )
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = sessionCode,
            onValueChange = onSessionCodeChange,
            label = { Text("Session Code") },
            placeholder = { Text("e.g. Xk3mR9aB2q") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OpenCodeGreen,
                focusedLabelColor = OpenCodeGreen,
                unfocusedBorderColor = OpenCodeBorder,
                unfocusedTextColor = OpenCodeTextPrimary,
                focusedTextColor = OpenCodeTextPrimary
            )
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = OpenCodeGreen)
        ) {
            Text("Connect", color = OpenCodeBlack, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(OpenCodeBorder)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OpenCodeTextPrimary)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = OpenCodeGreen)
            Spacer(Modifier.width(8.dp))
            Text("Scan QR Code", color = OpenCodeTextPrimary)
        }
    }
}

@Composable
fun ChatListScreen(
    agentName: String,
    relayOnline: Boolean,
    sessions: List<RemoteSession>,
    sessionsLoading: Boolean,
    sessionsNotice: String,
    currentAgent: String,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (RemoteSession) -> Unit,
    formatRelativeTime: (Long) -> String,
) {
    Column(modifier = Modifier.fillMaxSize().background(OpenCodeBlack).padding(top = 44.dp, bottom = 8.dp, start = 16.dp, end = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(OpenCodeSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("OC", color = OpenCodeGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("OC-mob", color = OpenCodeTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (relayOnline) "$agentName ready" else "Relay offline",
                        color = if (relayOnline) OpenCodeGreen else Color(0xFFEF4444),
                        fontSize = 11.sp
                    )
                }
            }
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = OpenCodeTextSecondary)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = OpenCodeTextSecondary)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Chats", color = OpenCodeTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Recent $agentName conversations", color = OpenCodeTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (sessionsLoading) "Loading chats..." else sessionsNotice.ifBlank { "No chats yet" },
                        color = OpenCodeTextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sessions.take(200)) { session ->
                    ChatListItem(
                        session = session,
                        isSelected = false,
                        currentAgent = currentAgent,
                        formatRelativeTime = formatRelativeTime,
                        onClick = { onSelectSession(session) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(OpenCodeSurface)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = OpenCodeTextMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search chats", color = OpenCodeTextMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onNewChat,
                modifier = Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = OpenCodeGreen),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = OpenCodeBlack, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Chat", color = OpenCodeBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ChatListItem(
    session: RemoteSession,
    isSelected: Boolean,
    currentAgent: String,
    formatRelativeTime: (Long) -> String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) OpenCodeSurfaceElevated else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(OpenCodeSurface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                session.title.take(1).uppercase(),
                color = OpenCodeGreen,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title, color = OpenCodeTextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (session.subtitle.isNotBlank()) {
                Text(
                    session.subtitle.replace("\\", "/").take(60),
                    color = OpenCodeTextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatRelativeTime(session.updatedAt), color = OpenCodeTextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                AGENT_NAMES[session.agent] ?: session.agent,
                color = OpenCodeGreenLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatScreen(
    title: String,
    subtitle: String,
    agentName: String,
    currentModel: String,
    agentModels: List<String>,
    onModelPicker: () -> Unit,
    logs: List<LogLine>,
    listState: LazyListState,
    isNearBottom: Boolean,
    onScrollToBottom: () -> Unit,
    onCopyTranscript: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    canPrompt: Boolean,
    canSubmit: Boolean,
    promptRunning: Boolean,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(OpenCodeBlack).padding(top = 44.dp, bottom = 8.dp)) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = OpenCodeTextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = OpenCodeTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        color = OpenCodeTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = OpenCodeTextSecondary)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Model / permission pills
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillButton(
                label = currentModel.ifBlank { "Select model" },
                icon = null,
                onClick = onModelPicker,
                enabled = agentModels.isNotEmpty(),
                containerColor = OpenCodeSurface,
                contentColor = OpenCodeTextPrimary
            )
            PillButton(
                label = "Full access",
                icon = Icons.Default.CheckCircle,
                onClick = { /* toggle would go here; relay uses bypass mode */ },
                enabled = true,
                containerColor = OpenCodeGreen.copy(alpha = 0.15f),
                contentColor = OpenCodeGreen
            )
        }

        Spacer(Modifier.height(8.dp))

        // Chat area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (logs.isNotEmpty()) {
                IconButton(
                    onClick = { onCopyTranscript() },
                    modifier = Modifier.align(Alignment.TopEnd).size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy transcript", tint = OpenCodeTextMuted)
                }
                if (!isNearBottom) {
                    FloatingActionButton(
                        onClick = onScrollToBottom,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(44.dp),
                        containerColor = OpenCodeSurfaceElevated,
                        contentColor = OpenCodeGreen,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest")
                    }
                }
            }
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No messages yet", color = OpenCodeTextSecondary, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Send a prompt to $agentName", color = OpenCodeTextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(consolidateLogs(logs)) { log ->
                        if (log.text.isNotBlank()) {
                            ChatBubble(log = log)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Input area
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (attachments.isNotEmpty()) {
                Text(
                    attachments.joinToString("  ") { "${it.name} (${it.size / 1024} KB)" },
                    color = OpenCodeGreenLight,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 50.dp, bottom = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 140.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = canPrompt,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(OpenCodeSurface)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Attach file", tint = if (canPrompt) OpenCodeGreen else OpenCodeTextMuted)
                }
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).heightIn(min = 52.dp, max = 140.dp),
                    enabled = canPrompt,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = OpenCodeSurface,
                        unfocusedContainerColor = OpenCodeSurface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = OpenCodeTextPrimary,
                        unfocusedTextColor = OpenCodeTextPrimary,
                        disabledContainerColor = OpenCodeSurfaceElevated,
                        disabledTextColor = OpenCodeTextMuted
                    ),
                    placeholder = {
                        Text(
                            if (!canPrompt) "Connect to send prompts"
                            else if (promptRunning) "Steer running turn..."
                            else "Ask $agentName...",
                            color = OpenCodeTextMuted
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onVoice,
                    enabled = canPrompt,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(OpenCodeSurface)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice prompt", tint = if (canPrompt) OpenCodeGreen else OpenCodeTextMuted)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(
                        if (canSubmit) OpenCodeGreen else if (promptRunning && canPrompt) OpenCodeGreenDark else OpenCodeSurface
                    ).clickable(enabled = canSubmit) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    if (promptRunning && !canSubmit) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = OpenCodeTextPrimary)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (promptRunning) "Steer turn" else "Send",
                            tint = if (canSubmit) OpenCodeBlack else OpenCodeTextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(log: LogLine) {
    val isUser = log.type == "user"
    val isStatus = log.type == "status" || log.text.startsWith("Error")
    val isTool = log.type == "tool"
    val isFile = log.type == "file"
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (isUser) 48.dp else 0.dp,
            end = if (isUser) 0.dp else 48.dp
        ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = when {
                isStatus -> Color.Transparent
                isTool -> OpenCodeSurface
                isFile -> OpenCodeGreen.copy(alpha = 0.08f)
                isUser -> OpenCodeGreen
                else -> OpenCodeSurfaceElevated
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            )
        ) {
            Text(
                log.text,
                color = when {
                    log.text.startsWith("Error") -> Color(0xFFEF4444)
                    isStatus -> OpenCodeTextSecondary
                    isTool -> OpenCodeGreenLight
                    isFile -> OpenCodeGreen
                    isUser -> OpenCodeBlack
                    else -> OpenCodeTextPrimary
                },
                fontFamily = if (isStatus || isTool || isFile) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (isStatus || isTool || isFile) 11.sp else 14.sp,
                lineHeight = if (isStatus || isTool || isFile) 16.sp else 20.sp,
                modifier = Modifier.padding(horizontal = if (isStatus) 0.dp else 14.dp, vertical = if (isStatus) 3.dp else 10.dp)
            )
        }
    }
}

@Composable
fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(17.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    sessionCode: String,
    onSessionCodeChange: (String) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    currentAgent: String,
    onCurrentAgentChange: (String) -> Unit,
    tokenUsage: String,
    showTechnicalEvents: Boolean,
    onShowTechnicalEventsChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onOpenUpdatePage: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Settings", color = OpenCodeTextPrimary, fontWeight = FontWeight.Bold) },
        containerColor = OpenCodeSurface,
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item {
                    Text("Connect", fontWeight = FontWeight.Bold, color = OpenCodeGreen, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onScanQr,
                        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeGreen)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = OpenCodeBlack)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR Code", color = OpenCodeBlack, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sessionCode,
                        onValueChange = onSessionCodeChange,
                        label = { Text("Session Code") },
                        placeholder = { Text("e.g. Xk3mR9aB2q") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpenCodeGreen,
                            focusedLabelColor = OpenCodeGreen,
                            unfocusedBorderColor = OpenCodeBorder,
                            unfocusedTextColor = OpenCodeTextPrimary,
                            focusedTextColor = OpenCodeTextPrimary
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = currentAgent,
                        onValueChange = onCurrentAgentChange,
                        label = { Text("Agent") },
                        placeholder = { Text("opencode / devin") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpenCodeGreen,
                            focusedLabelColor = OpenCodeGreen,
                            unfocusedBorderColor = OpenCodeBorder,
                            unfocusedTextColor = OpenCodeTextPrimary,
                            focusedTextColor = OpenCodeTextPrimary
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Server URL") },
                        placeholder = { Text("wss://host:port") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpenCodeGreen,
                            focusedLabelColor = OpenCodeGreen,
                            unfocusedBorderColor = OpenCodeBorder,
                            unfocusedTextColor = OpenCodeTextPrimary,
                            focusedTextColor = OpenCodeTextPrimary
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Usage", fontWeight = FontWeight.Bold, color = OpenCodeGreen, fontSize = 14.sp)
                    Text(
                        tokenUsage.removePrefix("tokens:").trim().ifBlank { "No token usage reported yet" },
                        color = if (tokenUsage.isBlank()) OpenCodeTextSecondary else OpenCodeGreenLight,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show command details", color = OpenCodeTextPrimary, fontSize = 13.sp)
                            Text("Off hides terminal output and paths", color = OpenCodeTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = showTechnicalEvents,
                            onCheckedChange = onShowTechnicalEventsChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = OpenCodeTextPrimary, checkedTrackColor = OpenCodeGreen)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onOpenUpdatePage,
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = OpenCodeGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("Open App Update Page", color = OpenCodeTextPrimary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancel", color = OpenCodeTextSecondary) }
                TextButton(onClick = onSave) { Text("Save", color = OpenCodeGreen) }
            }
        }
    )
}

@Composable
fun ModelPickerDialog(
    models: List<String>,
    currentModel: String,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Select model", color = OpenCodeTextPrimary, fontWeight = FontWeight.Bold) },
        containerColor = OpenCodeSurface,
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(models) { model ->
                    val isCurrent = model == currentModel
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(model) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isCurrent,
                            onClick = { onSelect(model) },
                            colors = RadioButtonDefaults.colors(selectedColor = OpenCodeGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(model, color = if (isCurrent) OpenCodeTextPrimary else OpenCodeTextSecondary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel", color = OpenCodeTextSecondary) } }
    )
}
