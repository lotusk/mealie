package io.mealie.backend.compat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code repr()} for the values a decoded JSON body or a validation error can hold. Used to reproduce the
 * text of FastAPI's validation errors, which embed {@code str(dict)} of each pydantic error.
 */
public final class PyRepr {

    private PyRepr() {
    }

    /** A Python tuple, e.g. a pydantic error's {@code loc}: {@code ('body', 'name')} or {@code ('body',)}. */
    public record Tuple(List<?> items) {
    }

    public static String repr(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("None");
            case Boolean b -> out.append(b ? "True" : "False");
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case BigInteger i -> out.append(i);
            case Double d -> out.append(floatRepr(d));
            case String s -> out.append(stringRepr(s));
            case byte[] bytes -> out.append(bytesRepr(bytes));
            case Tuple tuple -> {
                out.append('(');
                appendItems(out, tuple.items());
                if (tuple.items().size() == 1) {
                    out.append(',');
                }
                out.append(')');
            }
            case List<?> list -> {
                out.append('[');
                appendItems(out, list);
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    append(out, entry.getKey());
                    out.append(": ");
                    append(out, entry.getValue());
                }
                out.append('}');
            }
            default -> out.append(value);
        }
    }

    private static void appendItems(StringBuilder out, List<?> items) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            append(out, items.get(i));
        }
    }

    /** {@code repr(float)}: the shortest round-tripping digits, scientific below 1e-4 and from 1e16. */
    static String floatRepr(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        if (value == 0) {
            return (1 / value < 0) ? "-0.0" : "0.0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        String digits = decimal.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        String sign = value < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        String plain = decimal.abs().toPlainString();
        return sign + (plain.contains(".") ? plain : plain + ".0");
    }

    static String stringRepr(String value) {
        char quote = value.indexOf('\'') >= 0 && value.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        value.codePoints().forEach(cp -> {
            if (cp == quote || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else if (cp == '\t') {
                out.append("\\t");
            } else if (cp == '\n') {
                out.append("\\n");
            } else if (cp == '\r') {
                out.append("\\r");
            } else if (!isPrintable(cp)) {
                if (cp <= 0xff) {
                    out.append(String.format("\\x%02x", cp));
                } else if (cp <= 0xffff) {
                    out.append(String.format("\\u%04x", cp));
                } else {
                    out.append(String.format("\\U%08x", cp));
                }
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.append(quote).toString();
    }

    static String bytesRepr(byte[] value) {
        boolean hasSingle = false;
        boolean hasDouble = false;
        for (byte b : value) {
            hasSingle |= b == '\'';
            hasDouble |= b == '"';
        }
        char quote = hasSingle && !hasDouble ? '"' : '\'';
        StringBuilder out = new StringBuilder("b").append(quote);
        for (byte raw : value) {
            int b = raw & 0xff;
            if (b == quote || b == '\\') {
                out.append('\\').append((char) b);
            } else if (b == '\t') {
                out.append("\\t");
            } else if (b == '\n') {
                out.append("\\n");
            } else if (b == '\r') {
                out.append("\\r");
            } else if (b < 0x20 || b >= 0x7f) {
                out.append(String.format("\\x%02x", b));
            } else {
                out.append((char) b);
            }
        }
        return out.append(quote).toString();
    }

    /** {@code str.isprintable()} for one code point: everything except categories C* and Z* other than space. */
    private static boolean isPrintable(int cp) {
        if (cp == ' ') {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }
}
