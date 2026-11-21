// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import com.termux.spectreboard.spectre.snippets.SnippetRenderer
import com.termux.spectreboard.spectre.snippets.SnippetSet
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Synthetic fixture shaped like an Expandroid export (never the user's real file). */
class SnippetsTest {
    private val dateVars = """
        [{"Name":"time","Type":"date","Params":{"Echo":null,"Format":"%H:%M","Offset":0,"Cmd":null,"Layout":null,"Choices":null}},
         {"Name":"datenow","Type":"date","Params":{"Echo":null,"Format":"%m/%%e/%Y","Offset":0,"Cmd":null,"Layout":null,"Choices":null}}]
    """
    private val fixture = """
        {"globalVars":[],"matches":[
          {"Trigger":".hi","Replace":"hello there","Vars":$dateVars,"Form":null,"Form_Fields":null,"Word":false},
          {"Trigger":".star","Replace":"*","Vars":$dateVars,"Form":null,"Form_Fields":null,"Word":false},
          {"Trigger":".stamp","Replace":"on {{datenow}} at {{time}}","Vars":$dateVars,"Form":null,"Form_Fields":null,"Word":false},
          {"Trigger":"brb","Replace":"be right back","Vars":[],"Form":null,"Form_Fields":null,"Word":true},
          {"Trigger":".odd","Replace":"x {{mystery}} {{nothere}} y","Vars":[{"Name":"mystery","Type":"form","Params":{}}],"Form":null,"Form_Fields":null,"Word":false},
          {"Trigger":".say","Replace":"[{{e}}]","Vars":[{"Name":"e","Type":"echo","Params":{"Echo":"hey"}}],"Word":false}
        ]}
    """.trimIndent()

    private val set = SnippetSet.parse(fixture)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance(TimeZone.getDefault(), Locale.US)
        .apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun parses() {
        assertEquals(6, set.snippets.size)
        assertEquals(6, set.maxTriggerLength)
    }

    @Test fun matchesOnlyCompleteTrigger() {
        assertNull(set.match("say .h"))
        assertEquals(".hi", set.match("say .hi")!!.snippet.trigger)
        assertEquals(".hi", set.match(".hi")!!.snippet.trigger)
        assertEquals(".hi", set.match("word.hi")!!.snippet.trigger) // Word=false: anywhere
        assertNull(set.match(".st"))
        assertEquals(".star", set.match("a .star")!!.snippet.trigger)
    }

    @Test fun autoCapitalisedTriggerStillMatches() {
        val m = set.match("Ok. .Hi")!!
        assertEquals(".hi", m.snippet.trigger)
        assertEquals(".Hi", m.typedTrigger)
    }

    @Test fun wordTriggerNeedsBoundary() {
        assertEquals("brb", set.match("ok brb")!!.snippet.trigger)
        assertEquals("brb", set.match("brb")!!.snippet.trigger)
        assertNull(set.match("xbrb"))
    }

    @Test fun rendersDatesLikeExpandroid() {
        val s = set.snippets.first { it.trigger == ".stamp" }
        val out = SnippetRenderer.render(s, emptyList(), millis(2026, 11, 1, 9, 5), Locale.US)
        assertEquals("on 11/1/2026 at 09:05", out)
        val out2 = SnippetRenderer.render(s, emptyList(), millis(2026, 3, 21, 17, 42), Locale.US)
        assertEquals("on 03/21/2026 at 17:42", out2)
    }

    @Test fun unknownVarsStayAsPlaceholders() {
        val s = set.snippets.first { it.trigger == ".odd" }
        assertEquals("x {{mystery}} {{nothere}} y", SnippetRenderer.render(s, emptyList(), 0L, Locale.US))
    }

    @Test fun echoVar() {
        val s = set.snippets.first { it.trigger == ".say" }
        assertEquals("[hey]", SnippetRenderer.render(s, emptyList(), 0L, Locale.US))
    }

    @Test fun literalPercent() {
        assertEquals("100% 5", SnippetRenderer.strftime("100%% %e", millis(2026, 1, 5, 0, 0), Locale.US))
    }
}
