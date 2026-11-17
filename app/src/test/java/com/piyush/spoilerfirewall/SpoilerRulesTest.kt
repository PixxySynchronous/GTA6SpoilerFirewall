package com.piyush.spoilerfirewall

import org.junit.Assert.assertEquals
import org.junit.Test

class SpoilerRulesTest {
    private fun v(s: String?) = SpoilerRules.evaluate(s)

    @Test fun titlesAreBlocked() {
        assertEquals(Verdict.SPOILER, v("GTA 6 Ending EXPLAINED"))
        assertEquals(Verdict.SPOILER, v("#gta6 first look"))
        assertEquals(Verdict.SPOILER, v("GTA-VI is here"))
        assertEquals(Verdict.SPOILER, v("Grand Theft Auto Ⅵ review"))
    }

    @Test fun wholeFranchiseIsBlocked() {
        assertEquals(Verdict.SPOILER, v("GTA 5 funny moments compilation"))
        assertEquals(Verdict.SPOILER, v("GTA V online heist"))
        assertEquals(Verdict.SPOILER, v("#gtarp best clips"))
        assertEquals(Verdict.SPOILER, v("Grand Theft Auto: San Andreas remaster"))
        assertEquals(Verdict.SPOILER, v("Vice City Stories retro review"))
        assertEquals(Verdict.SPOILER, v("Rockstar Games just announced"))
        assertEquals(Verdict.SPOILER, v("Top 10 Los Santos easter eggs"))
    }

    @Test fun contextPlusRiskIsBlocked() {
        assertEquals(Verdict.SPOILER, v("Lucia's final mission leaked"))
        assertEquals(Verdict.SPOILER, v("Rockstar story leak"))
    }

    @Test fun ordinaryContentIsSafe() {
        assertEquals(Verdict.SAFE, v("Best pasta recipe in 10 minutes"))
        assertEquals(Verdict.SAFE, v("Good vibes only"))
        assertEquals(Verdict.SAFE, v("Rockstar energy drink taste test"))
        assertEquals(Verdict.SAFE, v("Jason Statham workout routine"))
    }

    @Test fun noTextStaysHidden() {
        assertEquals(Verdict.UNKNOWN, v(null))
        assertEquals(Verdict.UNKNOWN, v("   "))
        assertEquals(Verdict.UNKNOWN, v("ok"))
    }

    @Test fun wordBoundariesRespected() {
        assertEquals(Verdict.SAFE, v("gtag setup tutorial")) // not "gta"
        assertEquals(Verdict.SAFE, v("Vegetables for dinner")) // not "gta"
    }
}
