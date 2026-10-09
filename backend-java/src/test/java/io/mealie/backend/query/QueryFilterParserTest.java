package io.mealie.backend.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mealie.backend.query.QueryFilterParser.Component;
import io.mealie.backend.query.QueryFilterParser.LogicalOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryFilterParserTest {

    @Test
    void parsesGroupsListsKeywordsAndQuotedValues() {
        List<Object> parsed = QueryFilterParser.parse(
                "(name LIKE \"%a (b)%\" OR slug IN [x, \"y z\"]) AND recipeCount IS NOT NULL");
        assertThat(parsed).containsExactly(
                "(",
                new Component("name", "LIKE", "%a (b)%", "%a (b)%"),
                LogicalOperator.OR,
                new Component("slug", "IN", List.of("x", "y z"), List.of("x", "y z")),
                ")",
                LogicalOperator.AND,
                new Component("recipe_count", "IS NOT", null, null));
    }

    @Test
    void operatorsNeedNoSpaces() {
        assertThat(QueryFilterParser.parse("createdAt>=2024-01-01"))
                .containsExactly(new Component("created_at", ">=", "2024-01-01", "2024-01-01"));
    }

    @Test
    void rejectsWhatPythonRejects() {
        assertThatThrownBy(() -> QueryFilterParser.parse("(name = x"))
                .hasMessage("invalid query string: parenthesis are unbalanced");
        assertThatThrownBy(() -> QueryFilterParser.parse("name IN x"))
                .hasMessage("invalid query string: IN must be given a list of valuesenclosed by [ and ]");
        assertThatThrownBy(() -> QueryFilterParser.parse("name IS x"))
                .hasMessage("invalid query string: \"IS\" can only be used with \"NULL\", not \"x\"");
        assertThatThrownBy(() -> QueryFilterParser.parse("createdAt > $NOW+5x"))
                .hasMessage("Invalid time unit in NOW string ($NOW+5x)");
    }

    @Test
    void decamelizesLikePyhumps() {
        assertThat(Humps.decamelize("recipeCategory.name")).isEqualTo("recipe_category.name");
        assertThat(Humps.decamelize("APIResponse")).isEqualTo("api_response");
        assertThat(Humps.decamelize("updatedAt")).isEqualTo("updated_at");
        assertThat(Humps.decamelize("ID")).isEqualTo("ID");
    }
}
