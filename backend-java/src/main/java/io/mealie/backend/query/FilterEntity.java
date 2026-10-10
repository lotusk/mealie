package io.mealie.backend.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What a query filter or order-by attribute can refer to on one SQLAlchemy model: its columns (only those declared
 * {@code FilterableColumn} can be used), its relationships, and its association proxies.
 *
 * <p>Python resolves attribute paths by introspecting the SQLAlchemy models, so any relationship of any model is
 * reachable. Java describes the models explicitly in {@link FilterEntities}; a path through a relationship that isn't
 * described yet is rejected with a 400 that says so, rather than silently answering differently.
 */
public final class FilterEntity {

    public enum ColumnType { STRING, GUID, DATETIME, DATE, BOOLEAN, INTEGER, FLOAT }

    record Column(String attribute, String column, ColumnType type, boolean filterable) {
    }

    /**
     * A relationship. {@code joins} are its SQL join conditions as SQLAlchemy renders them (primaryjoin, then
     * secondaryjoin for many-to-many), and {@code tables} the tables a correlated subquery must select from.
     */
    record Relation(String attribute, Supplier<FilterEntity> target, boolean uselist, List<String> tables,
            List<String> joins) {
    }

    /** {@code association_proxy(collection, attribute)}. */
    record Proxy(String attribute, String relation, String targetAttribute) {
    }

    private final String model;
    private final String table;
    private final Map<String, Column> columns = new LinkedHashMap<>();
    private final Map<String, Relation> relations = new LinkedHashMap<>();
    private final Map<String, Proxy> proxies = new LinkedHashMap<>();
    private final Set<String> otherRelations;

    FilterEntity(String model, String table, Set<String> otherRelations) {
        this.model = model;
        this.table = table;
        this.otherRelations = Set.copyOf(otherRelations);
    }

    /** Columns every Mealie model gets from SqlAlchemyBase, including the updated_at synonym of update_at. */
    FilterEntity baseColumns() {
        column("id", ColumnType.GUID);
        column("created_at", ColumnType.DATETIME);
        columns.put("update_at", new Column("update_at", "update_at", ColumnType.DATETIME, true));
        columns.put("updated_at", new Column("updated_at", "update_at", ColumnType.DATETIME, true));
        return this;
    }

    FilterEntity column(String name, ColumnType type) {
        columns.put(name, new Column(name, name, type, true));
        return this;
    }

    FilterEntity unfilterable(String... names) {
        for (String name : names) {
            columns.put(name, new Column(name, name, ColumnType.STRING, false));
        }
        return this;
    }

    /** A many-to-one or one-to-many relationship without an association table. */
    FilterEntity relation(String name, Supplier<FilterEntity> target, String targetTable, boolean uselist,
            String join) {
        relations.put(name, new Relation(name, target, uselist, List.of(targetTable), List.of(join)));
        return this;
    }

    /** A many-to-many relationship through {@code secondary}. */
    FilterEntity secondary(String name, Supplier<FilterEntity> target, String targetTable, String secondary,
            String primaryJoin, String secondaryJoin) {
        relations.put(name, new Relation(name, target, true, List.of(secondary, targetTable),
                List.of(primaryJoin, secondaryJoin)));
        return this;
    }

    FilterEntity proxy(String name, String relation, String targetAttribute) {
        proxies.put(name, new Proxy(name, relation, targetAttribute));
        return this;
    }

    String model() {
        return model;
    }

    String table() {
        return table;
    }

    Column column(String name) {
        return columns.get(name);
    }

    Relation relation(String name) {
        return relations.get(name);
    }

    Proxy proxy(String name) {
        return proxies.get(name);
    }

    boolean hasUndescribedRelation(String name) {
        return otherRelations.contains(name);
    }
}
