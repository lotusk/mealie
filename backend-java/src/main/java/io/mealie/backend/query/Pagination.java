package io.mealie.backend.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Python's PaginationBase. It is a plain pydantic BaseModel, not a MealieModel, so unlike everything else its
 * multi-word keys are snake_case ({@code per_page}, {@code total_pages}).
 */
public record Pagination<T>(
        long page,
        @JsonProperty("per_page") long perPage,
        long total,
        @JsonProperty("total_pages") long totalPages,
        List<T> items,
        String next,
        String previous) {
}
