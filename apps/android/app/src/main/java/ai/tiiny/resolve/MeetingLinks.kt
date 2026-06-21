package ai.tiiny.resolve

import org.json.JSONArray
import org.json.JSONObject

private val urlPattern = Regex("https?://[^\\s<>\"')，。；、]+", RegexOption.IGNORE_CASE)
private val meetingUrlPattern = Regex(
    "feishu|larksuite|vc|videochat|meeting|meet|zoom|teams|tencent|voov|google",
    RegexOption.IGNORE_CASE
)
private val meetingKeyPattern = Regex(
    "meeting|vchat|vc|conference|url|link|join|video|online",
    RegexOption.IGNORE_CASE
)

fun extractMeetingUrlFromText(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return urlPattern.findAll(value)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .firstOrNull { it.looksLikeMeetingUrl() }
}

fun extractMeetingUrlFromJson(value: Any?): String? = findMeetingUrl(value, depth = 0, trustedContext = false)

private fun findMeetingUrl(value: Any?, depth: Int, trustedContext: Boolean): String? {
    if (value == null || value == JSONObject.NULL || depth > 6) return null
    return when (value) {
        is String -> {
            val url = value.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: urlPattern.find(value)?.value
            url?.trimEnd('.', ',', ';', ':')?.takeIf { trustedContext || it.looksLikeMeetingUrl() }
        }

        is JSONArray -> {
            for (index in 0 until value.length()) {
                findMeetingUrl(value.opt(index), depth + 1, trustedContext)?.let { return it }
            }
            null
        }

        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sortedBy { key ->
                if (meetingKeyPattern.containsMatchIn(key)) 0 else 1
            }
            for (key in keys) {
                val isTrusted = trustedContext || meetingKeyPattern.containsMatchIn(key)
                findMeetingUrl(value.opt(key), depth + 1, isTrusted)?.let { return it }
            }
            null
        }

        else -> null
    }
}

private fun String.looksLikeMeetingUrl(): Boolean =
    startsWith("http", ignoreCase = true) && meetingUrlPattern.containsMatchIn(this)
