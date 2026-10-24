package com.w8sb.w8premiumyt

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real, direct Telegram Bot API transport. No credentials are compiled into the repair. */
object TelegramManager {
    private const val PREFS = "w8safe"
    private const val TOKEN = "telegram_token"
    private const val CHAT = "telegram_chat"
    private const val USER = "telegram_authorized_user"
    private const val API = "https://api.telegram.org/bot"
    private const val MAX_ATTEMPTS = 3

    data class TelegramResult(
        val success: Boolean,
        val message: String,
        val httpCode: Int = 0,
        val retryAfter: Int = 0,
        val body: JSONObject? = null
    )

    data class IncomingUpdate(
        val updateId: Long,
        val fromId: Long?,
        val chatId: Long?,
        val chatType: String?,
        val text: String?,
        val callbackQueryId: String? = null
    )

    data class PollResult(
        val success: Boolean,
        val updates: List<IncomingUpdate> = emptyList(),
        val message: String = "",
        val httpCode: Int = 0,
        val retryAfter: Int = 0
    )

    interface Callback {
        fun onComplete(success: Boolean, message: String)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun isConfigured(context: Context): Boolean {
        val p = prefs(context)
        val token = p.getString(TOKEN, "") ?: ""
        val chat = p.getString(CHAT, "") ?: ""
        val user = p.getString(USER, "") ?: ""
        return validToken(token) && chat.toLongOrNull() != null && (user.toLongOrNull() ?: 0L) > 0L
    }

    @JvmStatic
    fun saveConfiguration(context: Context, token: String, chatId: String, userId: String): String {
        val trimmedChat = chatId.trim()
        val trimmedUser = userId.trim()
        val trimmedToken = token.trim().ifBlank { prefs(context).getString(TOKEN, "") ?: "" }
        if (!validToken(trimmedToken)) return "Enter a valid replacement bot token."
        if (trimmedChat.toLongOrNull() == null) return "Chat ID must be numeric."
        if ((trimmedUser.toLongOrNull() ?: 0L) <= 0L) return "Authorized user ID must be a positive numeric Telegram user ID."
        val old = prefs(context)
        val changed = old.getString(TOKEN, "") != trimmedToken || old.getString(CHAT, "") != trimmedChat || old.getString(USER, "") != trimmedUser
        val editor = prefs(context).edit()
            .putString(TOKEN, trimmedToken)
            .putString(CHAT, trimmedChat)
            .putString(USER, trimmedUser)
        if (changed) editor.remove("telegram_last_update")
        editor.commit()
        return ""
    }

    @JvmStatic
    fun clearConfiguration(context: Context) {
        prefs(context).edit().remove(TOKEN).remove(CHAT).remove(USER).remove("telegram_last_update").commit()
    }

    @JvmStatic
    fun authorizedUserId(context: Context): Long = prefs(context).getString(USER, "")?.toLongOrNull() ?: 0L

    @JvmStatic
    fun configuredChatId(context: Context): Long = prefs(context).getString(CHAT, "")?.toLongOrNull() ?: 0L

    private fun validToken(token: String): Boolean = Regex("^[0-9]+:[A-Za-z0-9_-]{20,}$").matches(token)

    private fun token(context: Context): String = prefs(context).getString(TOKEN, "") ?: ""
    private fun chat(context: Context): String = prefs(context).getString(CHAT, "") ?: ""

    @JvmStatic
    fun sendMessageAsync(context: Context, text: String, callback: Callback) {
        val app = context.applicationContext
        Thread {
            val result = sendMessageBlocking(app, text)
            Handler(Looper.getMainLooper()).post { callback.onComplete(result.success, result.message) }
        }.start()
    }

    @JvmStatic
    fun sendMessageBlocking(context: Context, text: String): TelegramResult {
        if (!isConfigured(context)) return TelegramResult(false, "Telegram is not configured.")
        if (text.length > 42_000) return TelegramResult(false, "Text exceeds the 42,000-character safety limit; use a file export or Android Share.")
        val chunks = if (text.isEmpty()) listOf("") else text.chunked(3500)
        var last = TelegramResult(false, "No response")
        for (chunk in chunks) {
            last = sendMessageSingle(context, chunk)
            if (!last.success) return last
        }
        return last
    }

    private fun sendMessageSingle(context: Context, text: String): TelegramResult {
        val params = linkedMapOf(
            "chat_id" to chat(context),
            "text" to text,
            "disable_web_page_preview" to "true"
        )
        return executeWithRetry(context, "sendMessage", params, null, null)
    }

    @JvmStatic
    fun answerCallbackQueryBlocking(context: Context, callbackQueryId: String, text: String): TelegramResult {
        if (!isConfigured(context)) return TelegramResult(false, "Telegram is not configured.")
        if (callbackQueryId.isBlank()) return TelegramResult(false, "Callback query ID is missing.")
        val params = linkedMapOf("callback_query_id" to callbackQueryId, "text" to text.take(180), "show_alert" to "false")
        return executeWithRetry(context, "answerCallbackQuery", params, null, null)
    }

    @JvmStatic
    fun sendFileAsync(context: Context, file: File, mimeType: String, caption: String, callback: Callback) {
        val app = context.applicationContext
        Thread {
            val result = sendFileBlocking(app, file, mimeType, caption)
            Handler(Looper.getMainLooper()).post { callback.onComplete(result.success, result.message) }
        }.start()
    }

    @JvmStatic
    fun sendFileBlocking(context: Context, file: File, mimeType: String, caption: String): TelegramResult {
        if (!isConfigured(context)) return TelegramResult(false, "Telegram is not configured.")
        if (!file.isFile || !file.canRead()) return TelegramResult(false, "The selected file is not readable.")
        val isAudio = mimeType.startsWith("audio/") && file.length() <= 20 * 1024 * 1024
        val method = if (isAudio) "sendAudio" else "sendDocument"
        val field = if (isAudio) "audio" else "document"
        val params = linkedMapOf("chat_id" to chat(context))
        if (caption.isNotBlank()) params["caption"] = caption.take(900)
        return executeWithRetry(context, method, params, field, file)
    }

    /** One long-poll request. Retry/backoff is owned by the service loop, not nested here. */
    @JvmStatic
    fun pollOnce(context: Context, offset: Long): PollResult {
        if (!isConfigured(context)) return PollResult(false, message = "Telegram is not configured.")
        val params = linkedMapOf(
            "offset" to offset.toString(),
            "timeout" to "20",
            "limit" to "20",
            "allowed_updates" to JSONArray().put("message").put("callback_query").toString()
        )
        val result = request(context, "getUpdates", params, null, null, 30_000)
        if (!result.success) return PollResult(false, message = result.message, httpCode = result.httpCode, retryAfter = result.retryAfter)
        try {
            val array = result.body?.getJSONArray("result") ?: JSONArray()
            val updates = ArrayList<IncomingUpdate>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = numericId(item, "update_id") ?: return PollResult(false, message = "Telegram returned a non-integer update ID")
                val message = item.optJSONObject("message")
                val callback = item.optJSONObject("callback_query")
                val source = callback ?: message
                val from = source?.optJSONObject("from")
                val chatObject = message?.optJSONObject("chat") ?: callback?.optJSONObject("message")?.optJSONObject("chat")
                val text = when {
                    callback != null && callback.has("data") -> callback.optString("data")
                    message != null && message.has("text") -> message.optString("text")
                    else -> null
                }
                updates.add(
                    IncomingUpdate(
                        id,
                        numericId(from, "id"),
                        numericId(chatObject, "id"),
                        chatObject?.optString("type"),
                        text,
                        callback?.optString("id")
                    )
                )
            }
            return PollResult(true, updates)
        } catch (e: Exception) {
            return PollResult(false, message = "Invalid Telegram update response: ${safeException(context, e)}")
        }
    }

    private fun numericId(json: JSONObject?, key: String): Long? {
        return when (val value = json?.opt(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    private fun executeWithRetry(
        context: Context,
        method: String,
        params: Map<String, String>,
        fileField: String?,
        file: File?
    ): TelegramResult {
        var last = TelegramResult(false, "No response")
        for (attempt in 1..MAX_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) return TelegramResult(false, "Operation cancelled.")
            last = request(context, method, params, fileField, file, 30_000)
            if (last.success) return last
            // Only an explicit Telegram rate-limit response is retried. A timeout/5xx is ambiguous
            // for sendMessage/sendDocument and automatic replay could duplicate owner-approved data.
            val retryable = (last.retryAfter > 0 || last.httpCode == 429) && last.retryAfter <= 60
            if (!retryable || attempt == MAX_ATTEMPTS) break
            val delaySeconds = if (last.retryAfter > 0) last.retryAfter else (1 shl (attempt - 1)) * 2
            try {
                Thread.sleep(delaySeconds * 1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return TelegramResult(false, "Operation cancelled.")
            }
        }
        return last
    }

    private fun request(
        context: Context,
        method: String,
        params: Map<String, String>,
        fileField: String?,
        file: File?,
        readTimeout: Int
    ): TelegramResult {
        var connection: HttpURLConnection? = null
        try {
            // The token is used only in the connection URL and is never included in logs or errors.
            val url = URL(API + token(context) + "/" + method)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                this.readTimeout = readTimeout
                doInput = true
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
            }
            if (fileField == null) {
                val encoded = formEncode(params)
                val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                DataOutputStream(connection.outputStream).use { it.write(bytes) }
            } else {
                requireNotNull(file)
                writeMultipart(connection, params, fileField, file)
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val response = readLimited(stream, 4 * 1024 * 1024)
            val json = try { JSONObject(response) } catch (_: Exception) { JSONObject() }
            val ok = json.optBoolean("ok", false)
            if (code in 200..299 && ok) {
                return TelegramResult(true, "Delivered to Telegram.", code, body = json)
            }
            val retryAfter = json.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
            val description = json.optString("description").ifBlank { "Telegram HTTP $code" }
            return TelegramResult(false, sanitize(context, description), code, retryAfter, json)
        } catch (e: Exception) {
            return TelegramResult(false, "Network error: ${safeException(context, e)}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeMultipart(connection: HttpURLConnection, params: Map<String, String>, field: String, file: File) {
        val boundary = "----W8" + UUID.randomUUID().toString().replace("-", "")
        connection.setChunkedStreamingMode(16 * 1024)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(connection.outputStream).use { output ->
            for ((key, value) in params) {
                output.writeUtf("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n")
            }
            val safeName = file.name.replace("\"", "_").replace("\r", "_").replace("\n", "_")
            output.writeUtf("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$safeName\"\r\n")
            output.writeUtf("Content-Type: application/octet-stream\r\n\r\n")
            FileInputStream(file).use { input -> input.copyTo(output, 16 * 1024) }
            output.writeUtf("\r\n--$boundary--\r\n")
        }
    }

    private fun DataOutputStream.writeUtf(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun formEncode(params: Map<String, String>): String = params.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun readLimited(input: InputStream?, max: Int): String {
        if (input == null) return ""
        BufferedInputStream(input).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > max) throw IOException("Telegram response exceeded the allowed size")
                output.write(buffer, 0, count)
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun safeException(context: Context, error: Exception): String {
        val raw = (error.javaClass.simpleName + ": " + (error.message ?: "unknown error"))
        return sanitize(context, raw)
    }

    private fun sanitize(context: Context, value: String): String {
        val secret = token(context)
        val redacted = if (secret.isNotEmpty()) value.replace(secret, "[redacted]") else value
        return redacted.replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot[redacted]").take(500)
    }
}
