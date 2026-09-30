package app.saveit.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values produced by Node.js (V8) `Number.prototype.toString`. */
class JsNumberTest {
    @Test fun syndicationTokensMatchV8() {
        val cases = mapOf(
            "20" to "6dq1a2xwd93",
            "1" to "bhi2ay3f28n",
            "1629307668568633344" to "3y6mctgwzxo",
            "1600009362759733248" to "3vmksnaiclg",
            "1599108643743473680" to "3vjqxd652ls",
            "1234567890123456789" to "2zqic77uqyk",
            "1834812402348290048" to "4g48e6hvede",
            "463440424141459456" to "14fxvks611f",
            "9999999999999999999" to "o8nxcsgioad",
            "1706698223961555302" to "44xrs3mbe",
            "1791132470302384166" to "4cbb5qelg",
            "1577719286659006464" to "3tojuiek1l",
        )
        cases.forEach { (id, token) -> assertEquals("token for $id", token, TwitterExtractor.syndicationToken(id)) }
    }

    @Test fun radixStringsMatchV8() {
        assertEquals("0.3lllllllllm", JsNumber.toRadixString(0.1, 36))
        assertEquals("0.i", JsNumber.toRadixString(0.5, 36))
        assertEquals("0.c", JsNumber.toRadixString(1.0 / 3, 36))
        assertEquals("3f.gez4w97ry", JsNumber.toRadixString(123.456, 36))
        assertEquals("3.53i5ab8p5f", JsNumber.toRadixString(Math.PI, 36))
        assertEquals("73.zzzzzzzzx", JsNumber.toRadixString(255.99999999999997, 36))
        assertEquals("0.0001ogs5wo29m8", JsNumber.toRadixString(0.000001, 36))
        assertEquals("7b.74bc6a7ef9dc", JsNumber.toRadixString(123.456, 16))
        assertEquals("0.1999999999999a", JsNumber.toRadixString(0.1, 16))
        assertEquals("5v1j4f4ds7c000", JsNumber.toRadixString(1e21, 36))
    }
}
