package io.mealie.backend.query;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of SQL built at runtime (a query filter, an ORDER BY) for a MyBatis mapper. Pass it as a mapper parameter
 * named {@code name} and splice it in with {@code ${name.sql}}: values are never written into the text, only
 * {@code #{name.params[i]}} placeholders, which MyBatis binds after the splice. The text itself only ever contains
 * SQL written in this codebase (identifiers come from {@link FilterEntities}).
 */
public final class SqlFragment {

    private final String name;
    private final StringBuilder sql = new StringBuilder();
    private final List<Object> params = new ArrayList<>();

    public SqlFragment(String name) {
        this.name = name;
    }

    public SqlFragment append(String text) {
        sql.append(text);
        return this;
    }

    /** Appends a placeholder bound to {@code value}. */
    public SqlFragment param(Object value) {
        sql.append("#{").append(name).append(".params[").append(params.size()).append("]}");
        params.add(value);
        return this;
    }

    public boolean isEmpty() {
        return sql.isEmpty();
    }

    public String getSql() {
        return sql.toString();
    }

    public List<Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        return sql + " " + params;
    }
}
