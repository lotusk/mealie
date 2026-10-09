package io.mealie.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Java's reimplementations against what Python does, recorded by dev/rebuild/compat_vectors.py. */
class PythonCompatibilityTest {

    static JsonNode vectors;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = PythonCompatibilityTest.class.getResourceAsStream("/compat/python-vectors.json")) {
            vectors = JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void slugifyMatchesPythonSlugify() {
        for (Map.Entry<String, JsonNode> e : vectors.get("slugify").properties()) {
            assertThat(PySlugify.slugify(e.getKey())).as(e.getKey()).isEqualTo(e.getValue().asString());
        }
    }

    @Test
    void shuffleMatchesPythonRandom() {
        for (Map.Entry<String, JsonNode> e : vectors.get("shuffle").properties()) {
            String seed = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
            int n = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf('|') + 1));
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            new PyRandom(seed).shuffle(order);
            List<Integer> expected = new ArrayList<>();
            e.getValue().forEach(v -> expected.add(v.asInt()));
            assertThat(order).as(e.getKey()).isEqualTo(expected);
        }
    }

    @Test
    void jsonErrorsMatchPythonJsonLoads() throws Exception {
        for (Map.Entry<String, JsonNode> e : vectors.get("json").properties()) {
            if (e.getValue().isNull()) {
                PyJson.loads(e.getKey());
                continue;
            }
            assertThatThrownBy(() -> PyJson.loads(e.getKey())).as(e.getKey())
                    .isInstanceOfSatisfying(PyJson.DecodeError.class, error -> {
                        assertThat(error.msg()).as(e.getKey()).isEqualTo(e.getValue().get(0).asString());
                        assertThat(error.pos()).as(e.getKey()).isEqualTo(e.getValue().get(1).asInt());
                    });
        }
    }

    @Test
    void uuidErrorsMatchPydantic() {
        for (Map.Entry<String, JsonNode> e : vectors.get("uuid").properties()) {
            String result;
            try {
                result = PyValidate.uuid4(e.getKey()).toString();
            } catch (PyValidate.Invalid invalid) {
                result = invalid.msg();
            }
            assertThat(result).as(e.getKey()).isEqualTo(e.getValue().asString());
        }
    }

    @Test
    void integersFollowPydanticLaxParsing() throws Exception {
        assertThat(PyValidate.integer(" 7 ")).isEqualTo(7);
        assertThat(PyValidate.integer("1.00")).isEqualTo(1);
        assertThat(PyValidate.integer("1_000")).isEqualTo(1000);
        assertThat(PyValidate.integer("-1")).isEqualTo(-1);
        for (String bad : List.of("1.5", "5.", "1e2", "0x10", "", "--1")) {
            assertThatThrownBy(() -> PyValidate.integer(bad)).as(bad).isInstanceOf(PyValidate.Invalid.class);
        }
    }

    @Test
    void reprMatchesPython() {
        assertThat(PyRepr.repr(Map.of("name", 5))).isEqualTo("{'name': 5}");
        assertThat(PyRepr.repr(new PyRepr.Tuple(List.of("body")))).isEqualTo("('body',)");
        assertThat(PyRepr.repr("it's")).isEqualTo("\"it's\"");
        assertThat(PyRepr.repr("a\nb\u0001")).isEqualTo("'a\\nb\\x01'");
        assertThat(PyRepr.repr(1e16)).isEqualTo("1e+16");
        assertThat(PyRepr.repr(0.0001)).isEqualTo("0.0001");
        assertThat(PyRepr.repr(2.5)).isEqualTo("2.5");
        assertThat(PyRepr.repr("name=x".getBytes())).isEqualTo("b'name=x'");
    }
}
