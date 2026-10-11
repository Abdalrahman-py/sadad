package com.sadad.app;

import java.util.Arrays;

/**
 * Base64 for the wire, URL-safe and unpadded, with no dependency on {@code java.util.Base64} — which
 * Android only has from API 26 while this app supports 24. Decoding stays tolerant the way the
 * platform decoder is: padding optional, and both the URL-safe and the standard alphabets accepted.
 */
final class Base64Url {
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] VALUES = new int[128];

    static {
        Arrays.fill(VALUES, -1);
        for (int index = 0; index < ALPHABET.length; index++) VALUES[ALPHABET[index]] = index;
        VALUES['+'] = 62;
        VALUES['/'] = 63;
    }

    private Base64Url() { }

    static String encode(byte[] bytes) {
        StringBuilder text = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int index = 0; index < bytes.length; index += 3) {
            int first = bytes[index] & 0xff;
            int second = index + 1 < bytes.length ? bytes[index + 1] & 0xff : -1;
            int third = index + 2 < bytes.length ? bytes[index + 2] & 0xff : -1;
            text.append(ALPHABET[first >>> 2]);
            text.append(ALPHABET[((first & 0x03) << 4) | (second < 0 ? 0 : second >>> 4)]);
            if (second < 0) break;
            text.append(ALPHABET[((second & 0x0f) << 2) | (third < 0 ? 0 : third >>> 6)]);
            if (third < 0) break;
            text.append(ALPHABET[third & 0x3f]);
        }
        return text.toString();
    }

    static byte[] decode(String value) {
        if (value == null) return new byte[0];
        byte[] out = new byte[value.length() / 4 * 3 + 3];
        int bits = 0, collected = 0, size = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '=' || character == '\n' || character == '\r') continue;
            int digit = character < 128 ? VALUES[character] : -1;
            if (digit < 0) throw new IllegalArgumentException("محرف base64 غير صالح.");
            bits = (bits << 6) | digit;
            collected += 6;
            if (collected >= 8) {
                collected -= 8;
                out[size++] = (byte) ((bits >>> collected) & 0xff);
            }
        }
        return Arrays.copyOf(out, size);
    }
}
