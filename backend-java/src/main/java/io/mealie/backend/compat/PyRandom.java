package io.mealie.backend.compat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Python's {@code random.Random} (Mersenne Twister, Modules/_randommodule.c), as far as {@code seed(str)} and
 * {@code shuffle()} go. Python orders {@code orderBy=random} pages by shuffling with the client's paginationSeed, so
 * the same seed must give the same order on both backends.
 */
public final class PyRandom {

    private static final int N = 624;
    private static final int M = 397;

    private final int[] mt = new int[N];
    private int mti = N + 1;

    /** {@code random.seed(seed)} for a str seed (version 2: the bytes plus their SHA-512 as one big integer). */
    public PyRandom(String seed) {
        byte[] text = seed.getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-512").digest(text);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] all = new byte[text.length + digest.length];
        System.arraycopy(text, 0, all, 0, text.length);
        System.arraycopy(digest, 0, all, text.length, digest.length);
        initByArray(toKey(new BigInteger(1, all)));
    }

    /** The integer as 32-bit words, least significant first, like random_seed() in C. */
    private static int[] toKey(BigInteger value) {
        int words = Math.max(1, (value.bitLength() + 31) / 32);
        int[] key = new int[words];
        BigInteger mask = BigInteger.valueOf(0xffffffffL);
        for (int i = 0; i < words; i++) {
            key[i] = value.shiftRight(32 * i).and(mask).intValue();
        }
        return key;
    }

    private void initGenrand(int seed) {
        mt[0] = seed;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = 1812433253 * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti;
        }
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
    }

    private int genrandUint32() {
        int y;
        if (mti >= N) {
            int kk;
            for (kk = 0; kk < N - M; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            for (; kk < N - 1; kk++) {
                y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            }
            y = (mt[N - 1] & 0x80000000) | (mt[0] & 0x7fffffff);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
            mti = 0;
        }
        y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= (y >>> 18);
        return y;
    }

    /** {@code getrandbits(k)} for 1 <= k <= 32. */
    private long getrandbits(int k) {
        return Integer.toUnsignedLong(genrandUint32()) >>> (32 - k);
    }

    /** {@code _randbelow_with_getrandbits(n)}. */
    private int randbelow(int n) {
        int k = 32 - Integer.numberOfLeadingZeros(n);
        long r = getrandbits(k);
        while (r >= n) {
            r = getrandbits(k);
        }
        return (int) r;
    }

    /** {@code random.shuffle(x)} in place. */
    public <T> void shuffle(List<T> items) {
        for (int i = items.size() - 1; i > 0; i--) {
            int j = randbelow(i + 1);
            T tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
    }
}
