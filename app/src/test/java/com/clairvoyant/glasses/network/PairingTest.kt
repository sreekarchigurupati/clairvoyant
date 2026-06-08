package com.clairvoyant.glasses.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun parsesAValidPairingUrl() {
        val p = Pairing.parse("clairvoyant://pair?host=192.168.1.42&port=4317&token=abc-DEF_123")
        assertEquals(Pairing("192.168.1.42", 4317, "abc-DEF_123"), p)
    }

    @Test fun toleratesParamOrderAndExtraParams() {
        val p = Pairing.parse("clairvoyant://pair?token=t1&port=5000&host=10.0.0.5&x=y")
        assertEquals(Pairing("10.0.0.5", 5000, "t1"), p)
    }

    @Test fun rejectsWrongSchemeOrHost() {
        assertNull(Pairing.parse("https://claude.ai/code"))
        assertNull(Pairing.parse("clairvoyant://pair?host=h&port=4317")) // missing token
        assertNull(Pairing.parse("clairvoyant://pair?host=h&token=t"))    // missing port
        assertNull(Pairing.parse("clairvoyant://pair?port=4317&token=t")) // missing host
        assertNull(Pairing.parse("clairvoyant://pair?host=h&port=notnum&token=t"))
        assertNull(Pairing.parse("not a url at all"))
    }
}
