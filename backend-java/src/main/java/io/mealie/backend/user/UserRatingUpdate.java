package io.mealie.backend.user;

import io.mealie.backend.compat.PyStr;
import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.web.validation.PyRequestBody;
import io.mealie.backend.web.validation.ValidationErrors;
import java.util.List;
import java.util.Locale;

/** Nullable, optional fields from Python's UserRatingUpdate; unknown fields are ignored. */
public record UserRatingUpdate(Double rating, Boolean isFavorite) {
    public static UserRatingUpdate parse(PyRequestBody body, ValidationErrors errors) {
        var fields = errors.bodyObject(body);
        if (fields == null) return new UserRatingUpdate(null, null);
        Double rating = fields.get("rating") == null ? null
                : errors.check(List.of("body", "rating"), fields.get("rating"), UserRatingUpdate::number);
        String key = fields.containsKey("isFavorite") ? "isFavorite" : "is_favorite";
        Boolean favorite = fields.get(key) == null ? null
                : errors.check(List.of("body", key), fields.get(key), UserRatingUpdate::bool);
        return new UserRatingUpdate(rating, favorite);
    }

    private static Double number(Object value) throws PyValidate.Invalid {
        if (value instanceof Boolean flag) return flag ? 1.0 : 0.0;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            String s = PyStr.strip(text);
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.matches("[+-]?nan")) return Double.NaN;
            if (lower.matches("[+-]?(inf|infinity)")) {
                return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            if (s.matches("[+-]?(?:[0-9](?:_?[0-9])*(?:\\.[0-9](?:_?[0-9])*|\\.)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?")) {
                try {
                    return Double.valueOf(s.replace("_", ""));
                } catch (NumberFormatException ignored) {
                    // Report the same validation category as a malformed decimal.
                }
            }
            throw new PyValidate.Invalid("float_parsing",
                    "Input should be a valid number, unable to parse string as a number", null);
        }
        throw new PyValidate.Invalid("float_type", "Input should be a valid number", null);
    }

    private static Boolean bool(Object value) throws PyValidate.Invalid {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) {
            if (number.doubleValue() == 0) return false;
            if (number.doubleValue() == 1) return true;
        } else if (value instanceof String text) {
            switch (text.toLowerCase(Locale.ROOT)) {
                case "0", "off", "f", "false", "n", "no": return false;
                case "1", "on", "t", "true", "y", "yes": return true;
                default: break;
            }
        } else {
            throw new PyValidate.Invalid("bool_type", "Input should be a valid boolean", null);
        }
        throw new PyValidate.Invalid("bool_parsing",
                "Input should be a valid boolean, unable to interpret input", null);
    }
}
