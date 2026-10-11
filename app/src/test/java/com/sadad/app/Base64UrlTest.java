package com.sadad.app;

import org.junit.Test;

import java.util.Base64;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link Base64Url} exists because the app supports Android 24 and {@code java.util.Base64} only
 * arrives in 26. It has to be indistinguishable from the platform codec, so the platform codec is
 * the oracle here.
 */
public final class Base64UrlTest {

    @Test public void encodingMatchesThePlatformUrlEncoderForEveryTailLength() {
        Random random = new Random(42);
        for (int length = 0; length <= 34; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            assertEquals("length " + length,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), Base64Url.encode(bytes));
        }
    }

    @Test public void decodingMatchesThePlatformDecoder() {
        Random random = new Random(7);
        for (int length = 0; length <= 34; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            assertArrayEquals("length " + length, bytes, Base64Url.decode(Base64Url.encode(bytes)));
            // and the padded platform form decodes to the same bytes
            assertArrayEquals("padded, length " + length, bytes, Base64Url.decode(Base64.getUrlEncoder().encodeToString(bytes)));
        }
    }

    @Test public void outputCarriesNeitherPlusSlashNorPadding() {
        Random random = new Random(1);
        for (int round = 0; round < 200; round++) {
            byte[] bytes = new byte[1 + random.nextInt(40)];
            random.nextBytes(bytes);
            String encoded = Base64Url.encode(bytes);
            assertFalse(encoded.contains("+"));
            assertFalse(encoded.contains("/"));
            assertFalse(encoded.contains("="));
        }
    }

    @Test public void bothAlphabetsAreAcceptedOnTheWayIn() {
        // the standard alphabet has to keep working: certificates and older server code use it
        assertArrayEquals(Base64.getDecoder().decode("++//"), Base64Url.decode("--__"));
        assertEquals("MQ", Base64Url.encode(Base64Url.decode("MQ==")));
        assertTrue(Base64Url.decode("").length == 0);
    }

    @Test public void aSignatureSizedValueSurvivesTheRoundTrip() {
        byte[] signature = new byte[64];
        for (int index = 0; index < signature.length; index++) signature[index] = (byte) (index * 31);
        // 64 bytes is three groups of 24 with 16 bytes left over: the case keys and signatures hit
        assertEquals(86, Base64Url.encode(signature).length());
        assertArrayEquals(signature, Base64Url.decode(Base64Url.encode(signature)));
    }
}
