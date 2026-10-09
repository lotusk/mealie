package io.mealie.backend.tags;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;

/** CPython Random's version-2 string seed and MT19937 shuffle, for stable pagination parity. */
final class PythonRandom {
    private final int[] state = new int[624];
    private int index = 624;
    PythonRandom(String seed) {
        try {
            byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(bytes);
            byte[] combined = new byte[bytes.length + digest.length];
            System.arraycopy(bytes, 0, combined, 0, bytes.length);
            System.arraycopy(digest, 0, combined, bytes.length, digest.length);
            BigInteger number = new BigInteger(1, combined);
            int[] key = new int[(number.bitLength() + 31) / 32];
            for (int k = 0; k < key.length; k++) { key[k] = number.intValue(); number = number.shiftRight(32); }
            state[0] = 19650218;
            for (int i = 1; i < 624; i++) state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
            int i = 1, j = 0;
            for (int k = Math.max(624, key.length); k > 0; k--) {
                state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1664525)) + key[j] + j;
                if (++i >= 624) { state[0] = state[623]; i = 1; }
                if (++j >= key.length) j = 0;
            }
            for (int k = 623; k > 0; k--) {
                state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1566083941)) - i;
                if (++i >= 624) { state[0] = state[623]; i = 1; }
            }
            state[0] = 0x80000000;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private int next() {
        if (index >= 624) {
            for (int i = 0; i < 624; i++) {
                int y = (state[i] & 0x80000000) | (state[(i + 1) % 624] & 0x7fffffff);
                state[i] = state[(i + 397) % 624] ^ (y >>> 1) ^ ((y & 1) == 0 ? 0 : 0x9908b0df);
            }
            index = 0;
        }
        int y = state[index++];
        y ^= y >>> 11; y ^= (y << 7) & 0x9d2c5680; y ^= (y << 15) & 0xefc60000; y ^= y >>> 18;
        return y;
    }
    void shuffle(List<Integer> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int bits = 32 - Integer.numberOfLeadingZeros(i + 1), j;
            do { j = next() >>> (32 - bits); } while (j > i);
            Collections.swap(values, i, j);
        }
    }
}
