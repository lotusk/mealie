package io.mealie.backend.web.validation;

import io.mealie.backend.compat.PyJson;
import io.mealie.backend.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Reads the body the way FastAPI's request handler does (fastapi/routing.py): JSON is decoded only for
 * {@code application/json} or {@code application/*+json} (strict content type, so a missing header means bytes), with
 * json.loads()' encoding detection. Malformed JSON is a 422 {@code json_invalid}; undecodable bytes are a 400.
 */
@Component
public class PyRequestBodyArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PyRequestBody.class;
    }

    @Override
    public PyRequestBody resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        byte[] bytes;
        try {
            bytes = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return read(bytes, request.getContentType());
    }

    static PyRequestBody read(byte[] bytes, String contentType) {
        if (bytes.length == 0) {
            return PyRequestBody.MISSING;
        }
        if (!isJson(contentType)) {
            return PyRequestBody.of(bytes);
        }
        String text;
        try {
            text = decode(bytes);
        } catch (CharacterCodingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "There was an error parsing the body", Map.of());
        }
        try {
            return PyRequestBody.of(PyJson.loads(text));
        } catch (PyJson.DecodeError e) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("error", e.msg());
            throw new RequestValidationException(List.of(new ValidationError(
                    "json_invalid", List.of("body", e.pos()), "JSON decode error", new LinkedHashMap<>(), ctx)));
        }
    }

    /** email.message.Message's view of the header: a value without a '/' counts as text/plain. */
    static boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        int slash = type.indexOf('/');
        if (slash < 0 || type.indexOf('/', slash + 1) >= 0) {
            return false;
        }
        String subtype = type.substring(slash + 1);
        return type.startsWith("application/") && (subtype.equals("json") || subtype.endsWith("+json"));
    }

    /** json.detect_encoding() followed by a strict decode. */
    static String decode(byte[] b) throws CharacterCodingException {
        Charset charset;
        int skip = 0;
        if (startsWith(b, 0x00, 0x00, 0xfe, 0xff) || startsWith(b, 0xff, 0xfe, 0x00, 0x00)) {
            charset = Charset.forName("UTF-32");
        } else if (startsWith(b, 0xfe, 0xff) || startsWith(b, 0xff, 0xfe)) {
            charset = StandardCharsets.UTF_16;
        } else if (startsWith(b, 0xef, 0xbb, 0xbf)) {
            charset = StandardCharsets.UTF_8;
            skip = 3;
        } else if (b.length >= 4 && b[0] == 0) {
            charset = b[1] != 0 ? StandardCharsets.UTF_16BE : Charset.forName("UTF-32BE");
        } else if (b.length >= 4 && b[1] == 0) {
            charset = b[2] != 0 || b[3] != 0 ? StandardCharsets.UTF_16LE : Charset.forName("UTF-32LE");
        } else if (b.length == 2 && b[0] == 0) {
            charset = StandardCharsets.UTF_16BE;
        } else if (b.length == 2 && b[1] == 0) {
            charset = StandardCharsets.UTF_16LE;
        } else {
            charset = StandardCharsets.UTF_8;
        }
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, skip, b.length - skip))
                .toString();
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
