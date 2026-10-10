package io.mealie.backend.recipe;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** CPython Random's version-2 string seed, MT19937, getrandbits and Fisher-Yates shuffle. */
final class PythonRandom {
    private final int[] state = new int[624];
    private int index = 624;

    PythonRandom(String seed) {
        try {
            byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-512").digest(bytes);
            byte[] combined = new byte[bytes.length + hash.length];
            System.arraycopy(bytes, 0, combined, 0, bytes.length);
            System.arraycopy(hash, 0, combined, bytes.length, hash.length);
            BigInteger integer = new BigInteger(1, combined);
            int[] keys = new int[(integer.bitLength() + 31) / 32];
            for (int k = 0; k < keys.length; k++) keys[k] = integer.shiftRight(k * 32).intValue();
            state[0] = 19650218;
            for (int i = 1; i < 624; i++) state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
            int i = 1, j = 0;
            for (int k = Math.max(624, keys.length); k > 0; k--) {
                state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1664525)) + keys[j] + j;
                if (++i >= 624) { state[0] = state[623]; i = 1; }
                if (++j >= keys.length) j = 0;
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

    int[] shuffledRanks(int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = i;
        for (int i = count - 1; i > 0; i--) {
            int bound = i + 1, bits = 32 - Integer.numberOfLeadingZeros(bound), j;
            do { j = next() >>> (32 - bits); } while (j >= bound);
            int previous = values[i]; values[i] = values[j]; values[j] = previous;
        }
        return values;
    }
}
