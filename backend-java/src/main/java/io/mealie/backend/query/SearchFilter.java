package io.mealie.backend.query;

import io.mealie.backend.compat.PyStr;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code search} parameter of paginated endpoints, ported from SearchFilter
 * (mealie/schema/response/query_search.py) and MealieModel.filter_search_query(). Postgres uses pg_trgm fuzzy matching unless the search contains a quoted
 * phrase; SQLite (and quoted searches) match each word with a plain LIKE.
 */
public final class SearchFilter {

    /** string.punctuation without the quotes, which delimit literal phrases. */
    private static final String PUNCTUATION = "!#$%&()*+,-./:;<=>?@[\\]^_`{|}~";
    private static final Pattern QUOTED = Pattern.compile("([\"'])(?:(?=(\\\\?))\\2.)*?\\1");
    private static final Pattern REMOVE_QUOTES = Pattern.compile("['\"](.*)['\"]");
    /** MealieModel._fuzzy_similarity_threshold. */
    public static final String FUZZY_SIMILARITY_THRESHOLD = "0.5";

    private final boolean fuzzy;
    private final String search;
    private final List<String> searchList;

    /** normalizeCharacters is the schema's {@code _normalize_search}. */
    public SearchFilter(String search, boolean postgres, boolean normalizeCharacters) {
        if (normalizeCharacters) {
            throw new UnsupportedOperationException("character normalization is not ported yet");
        }
        this.fuzzy = postgres && !QUOTED.matcher(PyStr.strip(search)).find();
        this.search = PyStr.strip(translatePunctuation(search));
        this.searchList = buildSearchList(this.search);
    }

    /** True when the query must run after {@code set pg_trgm.word_similarity_threshold}. */
    public boolean isFuzzy() {
        return fuzzy;
    }

    private static String translatePunctuation(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(PUNCTUATION.indexOf(c) >= 0 ? ' ' : c);
        }
        return out.toString();
    }

    private static List<String> buildSearchList(String search) {
        List<String> list = new ArrayList<>();
        if (QUOTED.matcher(search).find()) {
            Matcher quoted = QUOTED.matcher(search);
            while (quoted.find()) {
                list.add(REMOVE_QUOTES.matcher(quoted.group()).replaceAll("$1"));
            }
            list.addAll(PyStr.split(translatePunctuation(QUOTED.matcher(search).replaceAll(""))));
        } else {
            list.addAll(PyStr.split(translatePunctuation(search)));
        }
        return list.stream().map(PyStr::strip).toList();
    }

    /** The WHERE condition over the searchable columns (the first one also orders the results). */
    public QueryFilterSql.Expr where(List<String> columns) {
        if (fuzzy) {
            return out -> {
                out.append("(");
                for (int i = 0; i < columns.size(); i++) {
                    out.append(i == 0 ? "" : " OR ").append(columns.get(i) + " %> ").param(search);
                }
                out.append(")");
            };
        }
        if (searchList.isEmpty()) {
            // SQLAlchemy drops an empty or_() from the WHERE clause.
            return null;
        }
        return out -> {
            out.append("(");
            boolean first = true;
            for (String column : columns) {
                for (String word : searchList) {
                    out.append(first ? "" : " OR ").append(column + " LIKE ").param("%" + word + "%");
                    first = false;
                }
            }
            out.append(")");
        };
    }

    /** The ORDER BY term that ranks the best matches first. */
    public QueryFilterSql.Expr order(List<String> columns) {
        String first = columns.getFirst();
        if (fuzzy) {
            return out -> out.append("least(" + first + " <->> ").param(search).append(")");
        }
        return out -> out.append(first + " LIKE ").param("%" + search + "%").append(" DESC");
    }
}
