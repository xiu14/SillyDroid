package com.jm.sillydroid.feature.main.ui.home.speech

/** 单次朗读的字数上限，防止误选整段聊天把 TTS 额度一次花光。 */
internal const val MAX_SPEECH_CHARS = 5_000

private val EMOJI = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")
private val SENTENCE = Regex("[^。！？!?；;\\n]*[。！？!?；;\\n]+|[^。！？!?；;\\n]+$")
private val CLAUSE_BREAK = Regex("(?<=[，,、：:])")

/**
 * 去掉会被读出来或者影响语调的符号（规则同 clawd 的 speakable），再去掉 Markdown 记号。
 * 换行保留，分段时当作句子边界。
 */
internal fun speakableText(raw: String): String {
    return raw
        .replace(EMOJI, "")
        .replace(Regex("[～~]+"), "")
        .replace(Regex("……|\\.{3,}"), "，")
        .replace(Regex("[*#`]+"), "")
        .replace(Regex("[ \\t\\u3000]+"), " ")
        .replace(Regex(" *\\n[\\s]*"), "\n")
        .trim()
}

/**
 * 按句子切成多段依次请求：第一段尽量短，让首包尽快出声；之后每段不超过 [maxChars]。
 */
internal fun splitForSpeech(text: String, firstMaxChars: Int = 60, maxChars: Int = 300): List<String> {
    val sentences = SENTENCE.findAll(text)
        .map { match -> match.value.trim() }
        .filter { sentence -> sentence.isNotEmpty() }
        .flatMap { sentence -> breakLongSentence(sentence, maxChars) }
        .toList()

    val segments = mutableListOf<String>()
    val current = StringBuilder()
    for (sentence in sentences) {
        val limit = if (segments.isEmpty()) firstMaxChars else maxChars
        if (current.isNotEmpty() && current.length + sentence.length > limit) {
            segments += current.toString()
            current.clear()
        }
        current.append(sentence)
        if (segments.isEmpty() && current.length >= firstMaxChars) {
            segments += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) {
        segments += current.toString()
    }
    return segments
}

/** 超长的句子先按逗号拆，仍然超长就硬切。 */
private fun breakLongSentence(sentence: String, maxChars: Int): List<String> {
    if (sentence.length <= maxChars) {
        return listOf(sentence)
    }
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    for (clause in sentence.split(CLAUSE_BREAK)) {
        if (current.isNotEmpty() && current.length + clause.length > maxChars) {
            parts += current.toString()
            current.clear()
        }
        current.append(clause)
        while (current.length > maxChars) {
            parts += current.substring(0, maxChars)
            current.delete(0, maxChars)
        }
    }
    if (current.isNotEmpty()) {
        parts += current.toString()
    }
    return parts
}
