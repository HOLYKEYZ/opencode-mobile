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
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.agenthub.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

data class LogLine(val id: Long, val text: String, val type: String = "append")

const val MAX_RENDERED_LOG_LINES = 200
const val MAX_PERSISTED_TRANSCRIPT_CHARS = 60000

fun restoreTranscriptLogs(savedTranscript: String, now: Long = System.currentTimeMillis()): List<LogLine> {
    return savedTranscript
        .takeLast(MAX_PERSISTED_TRANSCRIPT_CHARS)
        .split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeLast(MAX_RENDERED_LOG_LINES)
        .mapIndexed { index, text -> LogLine(now + index, text, "append") }
}

fun consolidateLogs(logs: List<LogLine>): List<LogLine> {
    val consolidated = mutableListOf<LogLine>()
    for (log in logs.takeLast(MAX_RENDERED_LOG_LINES * 2)) {
        if (log.type == "replace" && consolidated.isNotEmpty() && consolidated.last().type == "replace") {
            consolidated[consolidated.size - 1] = log
        } else {
            consolidated.add(log)
        }
    }
    return consolidated.takeLast(MAX_RENDERED_LOG_LINES)
}

fun visibleTranscriptFrom(logs: List<LogLine>): String {
    return consolidateLogs(logs)
        .map { it.text }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .takeLast(MAX_PERSISTED_TRANSCRIPT_CHARS)
}

fun appendBoundedLog(logs: List<LogLine>, line: LogLine): List<LogLine> {
    if (logs.lastOrNull()?.text == line.text && logs.lastOrNull()?.type == line.type) return logs
    return (logs + line).takeLast(MAX_RENDERED_LOG_LINES * 2)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AgentHubTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = OpenCodeBlack) {
                    AgentHubScreen()
                }
            }
        }
    }
}

@Composable
fun AgentHubScreen() {
    val context = LocalContext.current
    val rootView = LocalView.current
    val prefs = remember { context.getSharedPreferences("OCmobPrefs", Context.MODE_PRIVATE) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ocClient = remember { OcClient() }

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
    var logs by remember { mutableStateOf(restoreTranscriptLogs(prefs.getString("LAST_TRANSCRIPT", "").orEmpty())) }
    var serverConnected by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<OcSession>>(emptyList()) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var selectedSessionId by remember { mutableStateOf(prefs.getString("SELECTED_SESSION_ID", "") ?: "") }
    var selectedSessionTitle by remember { mutableStateOf(prefs.getString("SELECTED_SESSION_TITLE", "") ?: "") }
    var allModels by remember { mutableStateOf<List<OcModelInfo>>(emptyList()) }
    var currentModel by remember { mutableStateOf(prefs.getString("CURRENT_MODEL", "") ?: "") }
    var currentProvider by remember { mutableStateOf(prefs.getString("CURRENT_PROVIDER", "") ?: "") }
    var promptRunning by remember { mutableStateOf(false) }
    var showTechnicalEvents by remember { mutableStateOf(prefs.getBoolean("SHOW_TECHNICAL_EVENTS", false)) }
    var stickToBottom by remember { mutableStateOf(true) }
    var viewMode by remember { mutableStateOf("list") }
    var serverUrl by remember { mutableStateOf(prefs.getString("SERVER_URL", "http://192.168.100.13:4096") ?: "http://192.168.100.13:4096") }
    var serverPassword by remember { mutableStateOf(prefs.getString("SERVER_PASSWORD", "") ?: "") }
    var lastKnownMessageCount by remember { mutableStateOf(0) }

    val scrollScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = listOf(input, spoken).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.take(5000) ?: ""
                if (text.isNotBlank()) {
                    input = listOf(input, text).filter { s -> s.isNotBlank() }.joinToString("\n\n")
                }
            } catch (_: Exception) {}
        }
    }

    fun appendLog(line: LogLine) { logs = appendBoundedLog(logs, line) }

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

    fun compactChatText(text: String): String {
        val cleaned = text
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
            .replace("\r", "")
            .trim()
        if (cleaned.length <= 4000) return cleaned
        return cleaned.take(4000) + "\n\n[truncated]"
    }

    fun connectToServer() {
        if (serverUrl.isBlank() || serverPassword.isBlank()) {
            appendLog(LogLine(System.currentTimeMillis(), "Error: Enter server URL and password"))
            return
        }
        Thread {
            ocClient.configure(serverUrl, serverPassword)
            val ok = ocClient.healthCheck()
            onUi {
                if (ok) {
                    serverConnected = true
                    appendLog(LogLine(System.currentTimeMillis(), "Connected to OC server"))
                    prefs.edit().putString("SERVER_URL", serverUrl).putString("SERVER_PASSWORD", serverPassword).apply()
                    Thread {
                        val fetched = ocClient.getSessions()
                        onUi {
                            sessions = fetched
                            if (fetched.isEmpty()) appendLog(LogLine(System.currentTimeMillis(), "No sessions found"))
                        }
                    }.start()
                    Thread {
                        val models = ocClient.getProviders()
                        onUi {
                            allModels = models
                            if (models.isEmpty()) appendLog(LogLine(System.currentTimeMillis(), "No models found"))
                        }
                    }.start()
                } else {
                    appendLog(LogLine(System.currentTimeMillis(), "Error: Could not connect to OC server"))
                }
            }
        }.start()
    }

    var livePollThread: Thread? = null

    fun startLivePolling(sessionId: String) {
        livePollThread?.interrupt()
        livePollThread = Thread {
            val startTime = System.currentTimeMillis()
            val maxWait = 10 * 60 * 1000L
            var staleCount = 0
            try {
                while (System.currentTimeMillis() - startTime < maxWait && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(2500)
                    val messages = ocClient.getSessionMessages(sessionId)
                    if (messages.size > lastKnownMessageCount) {
                        staleCount = 0
                        val newMsgs = messages.drop(lastKnownMessageCount)
                        for (msg in newMsgs) {
                            val logType = when (msg.role) {
                                "user" -> "user"
                                "assistant" -> "replace"
                                "thinking" -> "thinking"
                                "tool" -> "tool"
                                "file" -> "file"
                                else -> "status"
                            }
                            onUi { appendLog(LogLine(System.currentTimeMillis(), compactChatText(msg.text), logType)) }
                        }
                        lastKnownMessageCount = messages.size
                    } else {
                        staleCount++
                        if (staleCount >= 4) {
                            onUi { promptRunning = false }
                            return@Thread
                        }
                    }
                }
            } catch (_: InterruptedException) {}
            onUi { promptRunning = false }
        }
        livePollThread?.start()
    }

    fun refreshSessions() {
        if (!serverConnected) return
        sessionsLoading = true
        Thread {
            val fetched = ocClient.getSessions()
            onUi {
                sessions = fetched
                sessionsLoading = false
            }
        }.start()
    }

    fun loadSessionMessages(sessionId: String, title: String) {
        selectedSessionId = sessionId
        selectedSessionTitle = title
        viewMode = "chat"
        logs = emptyList()
        lastKnownMessageCount = 0
        promptRunning = false
        prefs.edit().putString("SELECTED_SESSION_ID", sessionId).putString("SELECTED_SESSION_TITLE", title).apply()
        Thread {
            val messages = ocClient.getSessionMessages(sessionId)
            onUi {
                if (messages.isEmpty()) {
                    appendLog(LogLine(System.currentTimeMillis(), "No messages in this session"))
                } else {
                    lastKnownMessageCount = messages.size
                    for (msg in messages) {
                        val logType = when (msg.role) {
                            "user" -> "user"
                            "assistant" -> "replace"
                            "thinking" -> "thinking"
                            "tool" -> "tool"
                            "file" -> "file"
                            else -> "status"
                        }
                        appendLog(LogLine(System.currentTimeMillis(), compactChatText(msg.text), logType))
                    }
                    val lastRole = messages.lastOrNull()?.role
                    if (lastRole == "user" || lastRole == "assistant") {
                        promptRunning = lastRole == "user"
                        startLivePolling(sessionId)
                    }
                }
            }
        }.start()
    }

    fun sendMessage() {
        if (input.isBlank() || !serverConnected) return
        val prompt = input.trim()
        val sessionId = selectedSessionId
        if (sessionId.isBlank()) {
            appendLog(LogLine(System.currentTimeMillis(), "Error: No session selected"))
            return
        }
        logs = logs + LogLine(System.currentTimeMillis(), prompt, "user")
        promptRunning = true
        input = ""
        Thread {
            val modelToSend = currentModel.ifBlank { null }
            val sent = ocClient.sendPromptAsync(sessionId, prompt, modelToSend)
            if (!sent) {
                onUi {
                    promptRunning = false
                    appendLog(LogLine(System.currentTimeMillis(), "Error: Failed to send prompt"))
                }
                return@Thread
            }
            onUi {
                appendLog(LogLine(System.currentTimeMillis(), "Prompt sent, waiting...", "status"))
                startLivePolling(sessionId)
            }
        }.start()
    }

    fun createNewSession() {
        if (!serverConnected) return
        Thread {
            val newId = ocClient.createSession()
            onUi {
                if (newId != null) {
                    selectedSessionId = newId
                    selectedSessionTitle = "New chat"
                    viewMode = "chat"
                    logs = emptyList()
                    lastKnownMessageCount = 0
                    appendLog(LogLine(System.currentTimeMillis(), "New session created"))
                } else {
                    appendLog(LogLine(System.currentTimeMillis(), "Error: Could not create session"))
                }
            }
        }.start()
    }

    fun copyVisibleTranscript() {
        val text = visibleTranscriptFrom(logs)
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OC-mob transcript", text))
        appendLog(LogLine(System.currentTimeMillis(), "Copied transcript"))
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {}
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && serverConnected) {
                refreshSessions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (serverPassword.isNotBlank() && !serverConnected) {
            connectToServer()
        }
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
        val text = visibleTranscriptFrom(logs)
        prefs.edit().putString("LAST_TRANSCRIPT", text.takeLast(MAX_PERSISTED_TRANSCRIPT_CHARS)).apply()
        val renderedCount = consolidateLogs(logs).size
        if (renderedCount > 0 && stickToBottom) {
            try { listState.scrollToItem(renderedCount - 1) } catch (_: Exception) {}
        }
    }

    val canPrompt = serverConnected && selectedSessionId.isNotBlank()
    val hasDraft = input.isNotBlank()
    val canSubmit = canPrompt && hasDraft

    if (showSettings) {
        SettingsDialog(
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            serverPassword = serverPassword,
            onServerPasswordChange = { serverPassword = it },
            currentModel = currentModel,
            allModels = allModels,
            onModelSelect = {
                currentModel = it
                prefs.edit().putString("CURRENT_MODEL", it).apply()
            },
            showTechnicalEvents = showTechnicalEvents,
            onShowTechnicalEventsChange = {
                showTechnicalEvents = it
                prefs.edit().putBoolean("SHOW_TECHNICAL_EVENTS", it).apply()
            },
            onTestConnection = { connectToServer() },
            onSave = {
                prefs.edit().putString("SERVER_URL", serverUrl).putString("SERVER_PASSWORD", serverPassword)
                    .putString("CURRENT_MODEL", currentModel).apply()
                showSettings = false
                connectToServer()
            },
            onCancel = { showSettings = false }
        )
    }

    if (showModelPicker && allModels.isNotEmpty()) {
        ModelPickerDialog(
            models = allModels,
            currentModel = currentModel,
            onSelect = { model ->
                currentModel = model.modelID
                prefs.edit().putString("CURRENT_MODEL", model.modelID).apply()
                showModelPicker = false
            },
            onCancel = { showModelPicker = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(OpenCodeBlack)) {
        when {
            !serverConnected -> ConnectScreen(
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                serverPassword = serverPassword,
                onServerPasswordChange = { serverPassword = it },
                onConnect = { connectToServer() }
            )
            viewMode == "list" -> ChatListScreen(
                sessions = sessions,
                sessionsLoading = sessionsLoading,
                onSettings = { showSettings = true },
                onRefresh = { refreshSessions() },
                onNewChat = { createNewSession() },
                onSelectSession = { session -> loadSessionMessages(session.id, session.title) },
                formatRelativeTime = { formatRelativeTime(it) }
            )
            else -> ChatScreen(
                title = selectedSessionTitle.ifBlank { "Chat" },
                subtitle = selectedSessionId,
                currentModel = currentModel,
                allModels = allModels,
                onModelPicker = { showModelPicker = true },
                logs = logs,
                listState = listState,
                isNearBottom = isNearBottom,
                onScrollToBottom = {
                    scrollScope.launch {
                        val count = consolidateLogs(logs).size
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
                    refreshSessions()
                },
                onSettings = { showSettings = true },
                input = input,
                onInputChange = { input = it },
                canPrompt = canPrompt,
                canSubmit = canSubmit,
                promptRunning = promptRunning,
                onAttach = { fileLauncher.launch("*/*") },
                onVoice = { startVoiceInput() },
                onSend = { sendMessage() }
            )
        }
    }
}

@Composable
fun ConnectScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    serverPassword: String,
    onServerPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
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
        Text("Connect to your OpenCode server", color = OpenCodeTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.100.13:4096") },
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
            value = serverPassword,
            onValueChange = onServerPasswordChange,
            label = { Text("Password") },
            placeholder = { Text("OpenCode server password") },
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
    }
}

@Composable
fun ChatListScreen(
    sessions: List<OcSession>,
    sessionsLoading: Boolean,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (OcSession) -> Unit,
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
                    Text("Connected", color = OpenCodeGreen, fontSize = 11.sp)
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
        Text("Sessions", color = OpenCodeTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Your OpenCode conversations", color = OpenCodeTextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (sessionsLoading) "Loading sessions..." else "No sessions yet",
                    color = OpenCodeTextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(sessions) { session ->
                    ChatListItem(
                        session = session,
                        formatRelativeTime = formatRelativeTime,
                        onClick = { onSelectSession(session) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = OpenCodeGreen)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = OpenCodeBlack, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Chat", color = OpenCodeBlack, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ChatListItem(
    session: OcSession,
    formatRelativeTime: (Long) -> String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
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
            if (session.directory.isNotBlank()) {
                Text(
                    session.directory.replace("\\", "/").takeLast(60),
                    color = OpenCodeTextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatRelativeTime(session.updatedAt), color = OpenCodeTextMuted, fontSize = 11.sp)
            if (session.status.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(session.status, color = OpenCodeGreenLight, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ChatScreen(
    title: String,
    subtitle: String,
    currentModel: String,
    allModels: List<OcModelInfo>,
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
    canPrompt: Boolean,
    canSubmit: Boolean,
    promptRunning: Boolean,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(OpenCodeBlack).padding(top = 44.dp, bottom = 8.dp)) {
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
                        subtitle.take(40),
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PillButton(
                label = currentModel.ifBlank { "Select model" },
                icon = null,
                onClick = onModelPicker,
                enabled = allModels.isNotEmpty(),
                containerColor = OpenCodeSurface,
                contentColor = OpenCodeTextPrimary
            )
            PillButton(
                label = "Full access",
                icon = Icons.Default.CheckCircle,
                onClick = { },
                enabled = true,
                containerColor = OpenCodeGreen.copy(alpha = 0.15f),
                contentColor = OpenCodeGreen
            )
        }

        Spacer(Modifier.height(8.dp))

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
                        Text("Send a prompt to start", color = OpenCodeTextMuted, fontSize = 12.sp)
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

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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
                            if (!canPrompt) "Select a session to chat"
                            else if (promptRunning) "Waiting for response..."
                            else "Type a message...",
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
                        if (canSubmit) OpenCodeGreen else OpenCodeSurface
                    ).clickable(enabled = canSubmit) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    if (promptRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = OpenCodeTextPrimary)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
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
    val isThinking = log.type == "thinking"
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
                isThinking -> OpenCodeSurfaceElevated.copy(alpha = 0.5f)
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
            val displayText = when {
                isTool -> log.text.removePrefix("[tool] ")
                isThinking -> log.text.removePrefix("[thinking] ")
                isFile -> log.text.removePrefix("[file] ")
                else -> log.text
            }
            Text(
                displayText,
                color = when {
                    log.text.startsWith("Error") -> Color(0xFFEF4444)
                    isStatus -> OpenCodeTextSecondary
                    isTool -> OpenCodeGreenLight
                    isThinking -> OpenCodeTextMuted
                    isFile -> OpenCodeGreen
                    isUser -> OpenCodeBlack
                    else -> OpenCodeTextPrimary
                },
                fontFamily = if (isStatus || isTool || isFile || isThinking) FontFamily.Monospace else FontFamily.Default,
                fontStyle = if (isThinking) FontStyle.Italic else FontStyle.Normal,
                fontSize = if (isStatus || isTool || isFile || isThinking) 11.sp else 14.sp,
                lineHeight = if (isStatus || isTool || isFile || isThinking) 16.sp else 20.sp,
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
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    serverPassword: String,
    onServerPasswordChange: (String) -> Unit,
    currentModel: String,
    allModels: List<OcModelInfo>,
    onModelSelect: (String) -> Unit,
    showTechnicalEvents: Boolean,
    onShowTechnicalEventsChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
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
                    Text("Connection", fontWeight = FontWeight.Bold, color = OpenCodeGreen, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Server URL") },
                        placeholder = { Text("http://192.168.100.13:4096") },
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverPassword,
                        onValueChange = onServerPasswordChange,
                        label = { Text("Password") },
                        placeholder = { Text("OpenCode server password") },
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
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onTestConnection,
                        modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = OpenCodeSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = OpenCodeGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection", color = OpenCodeTextPrimary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Current Model", fontWeight = FontWeight.Bold, color = OpenCodeGreen, fontSize = 14.sp)
                    Text(
                        currentModel.ifBlank { "No model selected" },
                        color = if (currentModel.isBlank()) OpenCodeTextSecondary else OpenCodeGreenLight,
                        fontSize = 12.sp
                    )
                    Text("Available models: ${allModels.size}", color = OpenCodeTextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show technical events", color = OpenCodeTextPrimary, fontSize = 13.sp)
                            Text("Terminal output and paths", color = OpenCodeTextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = showTechnicalEvents,
                            onCheckedChange = onShowTechnicalEventsChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = OpenCodeTextPrimary, checkedTrackColor = OpenCodeGreen)
                        )
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
    models: List<OcModelInfo>,
    currentModel: String,
    onSelect: (OcModelInfo) -> Unit,
    onCancel: () -> Unit,
) {
    val grouped = remember(models) { models.groupBy { it.providerID } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Select model", color = OpenCodeTextPrimary, fontWeight = FontWeight.Bold) },
        containerColor = OpenCodeSurface,
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                grouped.forEach { (provider, providerModels) ->
                    item {
                        Text(
                            provider.uppercase(),
                            color = OpenCodeGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(providerModels) { model ->
                        val isCurrent = model.modelID == currentModel
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(model) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isCurrent,
                                onClick = { onSelect(model) },
                                colors = RadioButtonDefaults.colors(selectedColor = OpenCodeGreen)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(model.modelID, color = if (isCurrent) OpenCodeTextPrimary else OpenCodeTextSecondary, fontSize = 14.sp)
                                if (model.name != model.modelID) {
                                    Text(model.name, color = OpenCodeTextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel", color = OpenCodeTextSecondary) } }
    )
}
