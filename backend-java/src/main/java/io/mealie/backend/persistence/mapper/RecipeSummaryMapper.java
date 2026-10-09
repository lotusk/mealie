package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.UserHouseholdRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** The lazy loads SQLAlchemy performs while serializing a RecipeSummary. */
public interface RecipeSummaryMapper {

    List<UserHouseholdRow> userHouseholds(@Param("userIds") List<Object> userIds);

    List<String> toolHouseholdSlugs(@Param("toolId") Object toolId);

    String setWordSimilarityThreshold(@Param("threshold") String threshold);
}
