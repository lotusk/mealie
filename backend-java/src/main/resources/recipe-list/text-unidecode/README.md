# Search transliteration data

`data.bin` is copied unchanged from text-unidecode 1.3, already used by the Python backend.
Its UTF-8 NUL-separated mapping is consumed directly by RecipeSearch in Java.
The original `LICENSE.txt` accompanies the table; the Artistic License option applies.
Original project: https://github.com/kmike/text-unidecode (version 1.3).

No Python code is executed by the Java backend. Keeping the same table preserves search
normalization for accented text, non-Latin scripts and supplementary characters.
