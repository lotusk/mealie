package io.mealie.backend.query;

import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.query.FilterEntity.Column;
import io.mealie.backend.query.FilterEntity.ColumnType;
import io.mealie.backend.query.FilterEntity.Proxy;
import io.mealie.backend.query.FilterEntity.Relation;
import io.mealie.backend.query.QueryFilterParser.Component;
import io.mealie.backend.query.QueryFilterParser.LogicalOperator;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a query filter or an order-by attribute into SQL, porting the SQL half of QueryFilterBuilder
 * (mealie/services/query_filter/builder.py): attributes are resolved through {@link FilterEntity} descriptions,
 * conditions on related models become {@code EXISTS} subqueries, and ordering by a related attribute becomes a
 * correlated MIN/MAX subquery, all rendered the way SQLAlchemy renders them for each engine.
 */
public final class QueryFilterSql {

    /** A SQL expression that renders itself, with its bound values, into a fragment. */
    @FunctionalInterface
    public interface Expr {
        void render(SqlFragment out);
    }

    private final SqlDialect dialect;

    public QueryFilterSql(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /** An attribute path resolved to its column (or relationship) and the relationships traversed to reach it. */
    record Resolved(FilterEntity entity, Column column, Relation relation, List<Relation> relations) {

        String reference() {
            return entity.table() + "." + column.column();
        }
    }

    // -- attribute resolution -----------------------------------------------------------------------------------

    /** get_model_and_model_attr_from_attr_string(). */
    Resolved resolve(String attrString, FilterEntity root) {
        List<String> chain = QueryFilterParser.split(Humps.decamelize(attrString), ".");
        List<Relation> relations = new ArrayList<>();
        FilterEntity current = root;
        Column column = null;
        Relation relation = null;
        for (int i = 0; i < chain.size(); i++) {
            String link = chain.get(i);
            Proxy proxy = current.proxy(link);
            String name = link;
            if (proxy != null) {
                Relation through = current.relation(proxy.relation());
                relations.add(through);
                current = through.target().get();
                name = proxy.targetAttribute();
            }
            column = current.column(name);
            relation = current.relation(name);
            if (column == null && relation == null && !current.hasUndescribedRelation(name)) {
                throw invalidAttribute(attrString);
            }
            if (i == chain.size() - 1) {
                if (column == null && relation == null) {
                    throw new QueryFilterException(nonFilterable(current, name));
                }
                break;
            }
            Relation next = current.relation(link);
            if (next == null) {
                if (current.hasUndescribedRelation(link)) {
                    throw new QueryFilterException("invalid attribute string: '" + attrString
                            + "' goes through " + current.model() + "." + link
                            + ", which this server does not support filtering through yet");
                }
                throw invalidAttribute(attrString);
            }
            relations.add(next);
            current = next.target().get();
        }
        if (column == null || !column.filterable()) {
            throw new QueryFilterException(
                    nonFilterable(current, column != null ? column.attribute() : relation.attribute()));
        }
        return new Resolved(current, column, null, relations);
    }

    private static String nonFilterable(FilterEntity entity, String attribute) {
        return "Cannot filter on " + entity.model() + "." + attribute;
    }

    private static QueryFilterException invalidAttribute(String attrString) {
        return new QueryFilterException(
                "invalid attribute string: '" + attrString + "' does not exist on this schema");
    }

    // -- filtering ----------------------------------------------------------------------------------------------

    /** QueryFilterBuilder(filter).filter_query(): the WHERE condition for a filter string. */
    public Expr filter(String filterString, FilterEntity root) {
        List<Object> components = QueryFilterParser.parse(filterString);

        // Resolve every attribute first, as Python does, so attribute errors win over value errors.
        List<Resolved> resolved = new ArrayList<>();
        for (Object component : components) {
            resolved.add(component instanceof Component c ? resolve(c.attributeName(), root) : null);
        }

        List<Expr> partialGroup = new ArrayList<>();
        Deque<List<Expr>> groupStack = new ArrayDeque<>();
        Deque<LogicalOperator> operatorStack = new ArrayDeque<>();
        for (int i = 0; i < components.size(); i++) {
            Object component = components.get(i);
            if ("(".equals(component)) {
                groupStack.push(partialGroup);
                partialGroup = new ArrayList<>();
            } else if (")".equals(component)) {
                if (!partialGroup.isEmpty()) {
                    Expr complete = consolidate(partialGroup, operatorStack);
                    partialGroup = popGroup(groupStack);
                    partialGroup.add(complete);
                } else {
                    partialGroup = popGroup(groupStack);
                }
            } else if (component instanceof LogicalOperator operator) {
                operatorStack.push(operator);
            } else {
                partialGroup.add(element((Component) component, resolved.get(i)));
            }
        }

        while (true) {
            Expr consolidated = consolidate(partialGroup, operatorStack);
            if (groupStack.isEmpty()) {
                return consolidated;
            }
            partialGroup = groupStack.pop();
            partialGroup.add(consolidated);
        }
    }

    private static List<Expr> popGroup(Deque<List<Expr>> stack) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("pop from an empty deque");
        }
        return stack.pop();
    }

    /**
     * _consolidate_group(): folds the group from its last element backwards, popping one operator per element from
     * the shared operator stack, so "a AND b OR c" means "a AND (b OR c)". Null for an empty group.
     */
    private static Expr consolidate(List<Expr> group, Deque<LogicalOperator> operators) {
        Expr result = null;
        for (int i = group.size() - 1; i >= 0; i--) {
            Expr element = group.get(i);
            if (result == null) {
                result = element;
            } else {
                if (operators.isEmpty()) {
                    throw new IllegalStateException("pop from an empty deque");
                }
                String keyword = operators.pop() == LogicalOperator.AND ? " AND " : " OR ";
                Expr left = result;
                result = out -> {
                    out.append("(");
                    renderOrTrue(left, out);
                    out.append(keyword);
                    renderOrTrue(element, out);
                    out.append(")");
                };
            }
        }
        return result;
    }

    private static void renderOrTrue(Expr expr, SqlFragment out) {
        if (expr == null) {
            out.append("1 = 1");
        } else {
            expr.render(out);
        }
    }

    /** _get_filter_element(): one condition, wrapped in EXISTS for each relationship on the way to the column. */
    private Expr element(Component component, Resolved attr) {
        String column = transform(attr);
        List<Object> values = validate(component, attr.column().type());
        List<Relation> relations = attr.relations();
        String relationship = component.relationship();
        Expr element = switch (relationship) {
            case "IS" -> out -> out.append(column + " IS NULL");
            case "IS NOT" -> out -> out.append(column + " IS NOT NULL");
            case "IN" -> in(column, values);
            case "NOT IN" -> {
                if (!relations.isEmpty()) {
                    // "none of the related rows match", rather than "some related row doesn't match"
                    Expr wrapped = wrap(in(column, values), relations);
                    yield out -> {
                        out.append("NOT (");
                        wrapped.render(out);
                        out.append(")");
                    };
                }
                Expr inner = in(column, values);
                yield out -> {
                    out.append("NOT (");
                    inner.render(out);
                    out.append(")");
                };
            }
            case "CONTAINS ALL" -> {
                if (values.size() == 1) {
                    yield in(column, values);
                }
                // every value must be matched by a different related row, so each gets its own EXISTS
                List<Expr> each = new ArrayList<>();
                for (Object value : values) {
                    each.add(wrap(compare(column, "=", value), relations));
                }
                yield out -> {
                    out.append("(");
                    for (int i = 0; i < each.size(); i++) {
                        out.append(i == 0 ? "" : " AND ");
                        each.get(i).render(out);
                    }
                    out.append(")");
                };
            }
            case "LIKE" -> like(column, values.getFirst(), false);
            case "NOT LIKE" -> like(column, values.getFirst(), true);
            case "=", ">", "<", ">=", "<=" -> compare(column, relationship, values.getFirst());
            case "<>" -> compare(column, "!=", values.getFirst());
            default -> throw new IllegalStateException("invalid relationship " + relationship);
        };
        if (relationship.equals("NOT IN") && !relations.isEmpty()
                || relationship.equals("CONTAINS ALL") && values.size() != 1) {
            return element;
        }
        return wrap(element, relations);
    }

    private static String transform(Resolved attr) {
        return attr.column().type() == ColumnType.STRING ? "lower(" + attr.reference() + ")" : attr.reference();
    }

    private static Expr compare(String column, String operator, Object value) {
        return out -> out.append(column + " " + operator + " ").param(value);
    }

    private static Expr in(String column, List<Object> values) {
        return out -> {
            out.append(column + " IN (");
            for (int i = 0; i < values.size(); i++) {
                out.append(i == 0 ? "" : ", ").param(values.get(i));
            }
            out.append(")");
        };
    }

    /** SQLAlchemy's ilike(): {@code ILIKE} on Postgres, {@code lower(x) LIKE lower(y)} on SQLite. */
    private Expr like(String column, Object value, boolean negate) {
        String not = negate ? " NOT" : "";
        if (dialect.engine() == DbEngine.POSTGRES) {
            return out -> out.append(column + not + " ILIKE ").param(value);
        }
        return out -> out.append("lower(" + column + ")" + not + " LIKE lower(").param(value).append(")");
    }

    /** _wrap_in_relationships(): {@code any()}/{@code has()} for each relationship, innermost last. */
    private static Expr wrap(Expr element, List<Relation> relations) {
        Expr result = element;
        for (int i = relations.size() - 1; i >= 0; i--) {
            Relation relation = relations.get(i);
            Expr inner = result;
            result = out -> {
                out.append("EXISTS (SELECT 1 FROM " + String.join(", ", relation.tables()) + " WHERE "
                        + String.join(" AND ", relation.joins()) + " AND ");
                inner.render(out);
                out.append(")");
            };
        }
        return result;
    }

    /** QueryFilterBuilderComponent.validate(): checks each value against the column type and converts it. */
    private List<Object> validate(Component component, ColumnType type) {
        Object value = component.value();
        List<Object> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            values.addAll(list);
        } else {
            values.add(value);
        }
        String relationship = component.relationship();
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i);
            if (raw == null) {
                continue;
            }
            String v = (String) raw;
            if (type == ColumnType.STRING) {
                values.set(i, PyStr.lower(v));
            }
            if ((relationship.equals("LIKE") || relationship.equals("NOT LIKE")) && type != ColumnType.STRING) {
                throw new QueryFilterException(
                        "invalid query string: \"" + relationship + "\" can only be used with string columns");
            }
            switch (type) {
                case GUID -> values.set(i, dialect.uuid(pythonUuid(v)));
                case DATE, DATETIME -> {
                    PyDateParser.Parsed parsed = PyDateParser.parse(v);
                    if (parsed == null) {
                        throw new QueryFilterException(
                                "invalid query string: unknown date or datetime format '" + v + "'");
                    }
                    values.set(i, type == ColumnType.DATE
                            ? dialect.date(parsed.local().toLocalDate())
                            : dialect.timestamp(parsed.offset() == null
                                    ? parsed.local().atOffset(ZoneOffset.UTC)
                                    : parsed.local().atOffset(parsed.offset())));
                }
                case BOOLEAN -> {
                    if (v.isEmpty()) {
                        throw new QueryFilterException("invalid query string");
                    }
                    char first = PyStr.lower(v).charAt(0);
                    values.set(i, dialect.bool(first == 't' || first == 'y' || v.equals("1")));
                }
                default -> {
                }
            }
        }
        return values;
    }

    /** {@code uuid.UUID(value)}: optional urn/uuid prefixes, braces and dashes around 32 hex digits. */
    private static UUID pythonUuid(String value) {
        String hex = value.replace("urn:", "").replace("uuid:", "");
        int start = 0;
        int end = hex.length();
        while (start < end && (hex.charAt(start) == '{' || hex.charAt(start) == '}')) {
            start++;
        }
        while (end > start && (hex.charAt(end - 1) == '{' || hex.charAt(end - 1) == '}')) {
            end--;
        }
        hex = hex.substring(start, end).replace("-", "");
        if (hex.length() != 32 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 0x80)) {
            throw new QueryFilterException("invalid query string: invalid UUID '" + value + "'");
        }
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
                Long.parseUnsignedLong(hex.substring(16), 16));
    }

    // -- ordering -----------------------------------------------------------------------------------------------

    /**
     * get_order_attr(): the column to order by; for a related attribute, a correlated subquery reducing it to the
     * value the ordering would surface first (MIN ascending, MAX descending).
     */
    public String orderAttr(String attrString, FilterEntity root, boolean descending) {
        Resolved attr = resolve(attrString, root);
        String column = transform(attr);
        if (attr.relations().isEmpty()) {
            return column;
        }
        Set<String> tables = new LinkedHashSet<>();
        List<String> joins = new ArrayList<>();
        for (Relation relation : attr.relations()) {
            tables.addAll(relation.tables());
            joins.addAll(relation.joins());
        }
        tables.remove(root.table());
        return "(SELECT " + (descending ? "max" : "min") + "(" + column + ")"
                + (tables.isEmpty() ? "" : " FROM " + String.join(", ", tables))
                + " WHERE " + String.join(" AND ", joins) + ")";
    }
}
