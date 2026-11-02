package com.jm.sillydroid.feature.main.ui.home.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun speakableText_removesSymbolsThatAreReadOutOrChangeIntonation() {
        assertEquals("你好，世界。", speakableText("*你好*……世界～😊。"))
        assertEquals("第一行\n第二行", speakableText("  第一行  \n\n  第二行 "))
    }

    @Test
    fun splitForSpeech_keepsFirstSegmentShortAndLaterSegmentsWithinLimit() {
        val text = "第一句话。" + "这是比较长的一句话，用来测试分段。".repeat(30)
        val segments = splitForSpeech(text, firstMaxChars = 10, maxChars = 50)

        assertEquals("第一句话。", segments.first())
        assertTrue(segments.all { segment -> segment.length <= 50 })
        assertEquals(text, segments.joinToString(""))
    }

    @Test
    fun splitForSpeech_breaksOverlongSentenceAtCommasThenHardCuts() {
        val sentence = "啊".repeat(70) + "，" + "哦".repeat(20) + "。"
        val segments = splitForSpeech(sentence, firstMaxChars = 60, maxChars = 40)

        assertTrue(segments.all { segment -> segment.length <= 40 })
        assertEquals(sentence, segments.joinToString(""))
    }

    @Test
    fun splitForSpeech_treatsNewlinesAsSentenceBoundaries() {
        val segments = splitForSpeech("短句\n另一句", firstMaxChars = 2, maxChars = 300)

        assertEquals(listOf("短句", "另一句"), segments)
    }
}
