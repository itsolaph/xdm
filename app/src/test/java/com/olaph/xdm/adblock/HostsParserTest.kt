package com.olaph.xdm.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class HostsParserTest {
    private fun parse(text: String): Set<String> = HostsParser.parse(text.lineSequence())

    @Test fun skipsCommentsAndBlankLines() {
        val set = parse(
            """
            # Title: StevenBlack/hosts
            #
            # Date: 07 October 2026

            0.0.0.0 ads.example.com
               # indented comment
            """.trimIndent(),
        )
        assertEquals(setOf("ads.example.com"), set)
    }

    @Test fun acceptsBothSinkholeAddresses() {
        val set = parse("0.0.0.0 a.example.com\n127.0.0.1 b.example.com\n0.0.0.0\tc.example.com")
        assertEquals(setOf("a.example.com", "b.example.com", "c.example.com"), set)
    }

    @Test fun excludesLocalhostEntries() {
        val set = parse(
            """
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            127.0.0.1 local
            255.255.255.255 broadcasthost
            ::1 localhost
            ::1 ip6-localhost
            ::1 ip6-loopback
            fe80::1%lo0 localhost
            ff00::0 ip6-localnet
            ff00::0 ip6-mcastprefix
            ff02::1 ip6-allnodes
            ff02::2 ip6-allrouters
            ff02::3 ip6-allhosts
            0.0.0.0 0.0.0.0
            0.0.0.0 tracker.example.net
            """.trimIndent(),
        )
        assertEquals(setOf("tracker.example.net"), set)
    }

    @Test fun stripsInlineComments() {
        val set = parse("0.0.0.0 ads.example.com # tracking\n0.0.0.0 x.example.org#no space before hash")
        assertEquals(setOf("ads.example.com", "x.example.org"), set)
    }

    @Test fun handlesCrlfLineEndings() {
        val set = parse("# c\r\n0.0.0.0 ads.example.com\r\n0.0.0.0 t.example.com\r\n").let {
            // lineSequence() splits on \r\n, but readers that only split on \n leave the \r behind: cover that too.
            it + HostsParser.parse("0.0.0.0 u.example.com\r\n0.0.0.0 v.example.com\r".split("\n").asSequence())
        }
        assertEquals(setOf("ads.example.com", "t.example.com", "u.example.com", "v.example.com"), set)
    }

    @Test fun readerVariantStripsBomAndCrlf() {
        val text = HostsParser.BOM + "0.0.0.0 first.example.com\r\n0.0.0.0 second.example.com\r\n"
        val set = HostsParser.parse(java.io.BufferedReader(StringReader(text)))
        assertEquals(setOf("first.example.com", "second.example.com"), set)
    }

    @Test fun readsSeveralHostsOnOneLine() {
        assertEquals(setOf("a.example.com", "b.example.com"), parse("0.0.0.0 a.example.com b.example.com"))
    }

    @Test fun acceptsCompactDomainsOnlyFormat() {
        val set = parse("# xdm-blocklist updated=1\nads.example.com\ntracker.example.net\n")
        assertEquals(setOf("ads.example.com", "tracker.example.net"), set)
    }

    @Test fun lowerCasesAndDropsTrailingDot() {
        assertEquals(setOf("ads.example.com"), parse("0.0.0.0 ADS.Example.COM."))
    }

    @Test fun deduplicates() {
        assertEquals(1, parse("0.0.0.0 a.example.com\n127.0.0.1 a.example.com\n0.0.0.0 A.EXAMPLE.COM").size)
    }

    @Test fun ignoresIpAddressesAndInvalidNames() {
        val set = parse(
            """
            0.0.0.0 1.2.3.4
            0.0.0.0 singlelabel
            0.0.0.0 bad_host!.example.com
            0.0.0.0 -.example.com
            0.0.0.0 ..example.com
            0.0.0.0 example..com
            1.2.3.4
            ::1
            this is not a hosts line
            0.0.0.0 ok.example.com
            """.trimIndent(),
        )
        assertEquals(setOf("ok.example.com"), set)
    }

    @Test fun allowsUnderscoresAndPunycode() {
        assertEquals(
            setOf("_dmarc.example.com", "xn--80ak6aa92e.com"),
            parse("0.0.0.0 _dmarc.example.com\n0.0.0.0 xn--80ak6aa92e.com"),
        )
    }

    @Test fun respectsMaxDomains() {
        val text = (1..50).joinToString("\n") { "0.0.0.0 d$it.example.com" }
        assertEquals(10, HostsParser.parse(text.lineSequence(), maxDomains = 10).size)
    }

    @Test fun emptyInputGivesEmptySet() {
        assertTrue(parse("").isEmpty())
        assertFalse(parse("# only a comment").isNotEmpty())
    }
}
