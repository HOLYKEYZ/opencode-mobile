package com.example.agenthub

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OcSession(
    val id: String,
    val title: String,
    val directory: String,
    val updatedAt: Long,
    val createdAt: Long,
    val status: String,
)

data class OcMessage(
    val role: String,
    val text: String,
    val model: String,
    val tokens: Long,
    val time: Long,
)

data class OcModelInfo(
    val providerID: String,
    val modelID: String,
    val name: String,
)

class OcClient {
    private var baseUrl = ""
    private var authHeader = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun configure(serverUrl: String, password: String) {
        baseUrl = serverUrl.trimEnd('/')
        val cred = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        authHeader = "Basic $cred"
    }

    fun isConfigured() = baseUrl.isNotBlank() && authHeader.isNotBlank()

    private fun get(path: String): JSONObject? {
        val cacheBuster = if (path.contains("?")) "&_t=${System.currentTimeMillis()}" else "?_t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url("$baseUrl$path$cacheBuster")
            .addHeader("Authorization", authHeader)
            .addHeader("Accept", "application/json")
            .addHeader("Cache-Control", "no-cache")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        if (body.isBlank()) return null
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) return null // array response, not object
        return JSONObject(body)
    }

    private fun getArray(path: String): JSONArray? {
        val cacheBuster = if (path.contains("?")) "&_t=${System.currentTimeMillis()}" else "?_t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url("$baseUrl$path$cacheBuster")
            .addHeader("Authorization", authHeader)
            .addHeader("Accept", "application/json")
            .addHeader("Cache-Control", "no-cache")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null
        if (body.isBlank()) return null
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("[")) JSONArray(body) else {
            val obj = JSONObject(body)
            obj.optJSONArray("data") ?: obj.optJSONArray("value") ?: obj.optJSONArray("sessions") ?: JSONArray()
        }
    }

    private fun post(path: String, jsonBody: String? = null): JSONObject? {
        val mediaType = "application/json".toMediaType()
        val body = (jsonBody ?: "{}").toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", authHeader)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        if (response.code == 204) return null
        if (!response.isSuccessful) return null
        val responseBody = response.body?.string() ?: return null
        if (responseBody.isBlank()) return null
        return JSONObject(responseBody)
    }

    fun healthCheck(): Boolean {
        return try {
            get("/global/health") != null
        } catch (_: Exception) {
            false
        }
    }

    fun getSessions(): List<OcSession> {
        return try {
            val arr = getArray("/session") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isBlank()) return@mapNotNull null
                OcSession(
                    id = id,
                    title = obj.optString("title", obj.optString("slug", id)),
                    directory = obj.optString("directory", ""),
                    updatedAt = obj.optJSONObject("time")?.optLong("updated", 0) ?: 0,
                    createdAt = obj.optJSONObject("time")?.optLong("created", 0) ?: 0,
                    status = obj.optString("status", ""),
                )
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getSessionMessages(sessionId: String): List<OcMessage> {
        return try {
            val arr = getArray("/session/$sessionId/message") ?: return emptyList()
            val messages = mutableListOf<OcMessage>()
            for (i in 0 until arr.length()) {
                val msg = arr.optJSONObject(i) ?: continue
                val info = msg.optJSONObject("info") ?: msg
                val role = info.optString("role", "assistant")
                val parts = msg.optJSONArray("parts") ?: JSONArray()
                val text = buildString {
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        val partText = formatPart(part)
                        if (partText.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append(partText)
                        }
                    }
                }
                if (text.isBlank()) continue
                messages.add(
                    OcMessage(
                        role = role,
                        text = text,
                        model = info.optString("model", ""),
                        tokens = info.optLong("tokens", 0),
                        time = info.optJSONObject("time")?.optLong("created", 0) ?: 0,
                    )
                )
            }
            messages
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun formatPart(part: JSONObject): String {
        return when (part.optString("type")) {
            "text" -> part.optString("text", "")
            "reasoning" -> {
                val t = part.optString("text", "")
                if (t.isNotBlank()) "[thinking] $t" else ""
            }
            "tool" -> {
                val name = part.optString("tool", "")
                    .ifBlank { part.optJSONObject("state")?.optString("title", "tool") ?: "tool" }
                val state = part.optJSONObject("state")
                val status = state?.optString("status", "") ?: ""
                val input = state?.optJSONObject("input")
                val inputSummary = input?.optString("filePath", "")
                    ?.ifBlank { input?.optString("command", "") ?: "" } ?: ""
                val display = if (inputSummary.isNotBlank()) "$name — ${inputSummary.take(80)}" else name
                "[tool] $display${if (status.isNotBlank()) " ($status)" else ""}"
            }
            "file" -> {
                val filename = part.optString("filename", "")
                    .ifBlank { part.optString("url", "attachment") }
                "[file] $filename"
            }
            "step-start", "step-finish" -> ""
            else -> ""
        }
    }

    fun createSession(): String? {
        return try {
            val json = post("/session", "{}")
            json?.optString("id")
                ?: json?.optJSONObject("data")?.optString("id")
        } catch (_: Exception) {
            null
        }
    }

    fun sendPromptAsync(sessionId: String, prompt: String, modelId: String? = null, providerId: String? = null): Boolean {
        return try {
            val body = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                })
                if (!modelId.isNullOrBlank()) {
                    put("model", JSONObject().apply {
                        put("providerID", providerId ?: "opencode-go")
                        put("modelID", modelId)
                    })
                }
            }
            post("/session/$sessionId/prompt_async", body.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    fun abortSession(sessionId: String): Boolean {
        return try {
            post("/session/$sessionId/abort") != null || true
        } catch (_: Exception) {
            false
        }
    }

    fun getProviders(): List<OcModelInfo> {
        return try {
            val json = get("/config/providers") ?: return emptyList()
            val models = mutableListOf<OcModelInfo>()
            val providersArr = json.optJSONArray("providers") ?: return emptyList()
            for (i in 0 until providersArr.length()) {
                val provider = providersArr.optJSONObject(i) ?: continue
                val providerId = provider.optString("id", "unknown")
                val providerName = provider.optString("name", providerId)
                val modelsObj = provider.optJSONObject("models") ?: continue
                for (modelId in modelsObj.keys()) {
                    val m = modelsObj.optJSONObject(modelId) ?: continue
                    models.add(
                        OcModelInfo(
                            providerID = providerId,
                            modelID = m.optString("id", modelId),
                            name = m.optString("name", modelId),
                        )
                    )
                }
            }
            models
        } catch (_: Exception) {
            emptyList()
        }
    }
}
