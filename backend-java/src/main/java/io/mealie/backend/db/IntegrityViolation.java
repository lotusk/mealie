package io.mealie.backend.db;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * A constraint violation from either engine, described the way the Python backend reports it: HttpRepo's
 * handle_exception (mealie/routes/_base/mixins.py) answers 409 and includes {@code str(IntegrityError)} in the body.
 *
 * @param unique whether this is a unique-constraint violation (is_unique_violation() in mixins.py)
 * @param driverError the database error as the Python driver words it, e.g.
 *     {@code (sqlite3.IntegrityError) UNIQUE constraint failed: tags.slug, tags.group_id}
 */
public record IntegrityViolation(boolean unique, String driverError) {

    private static final Pattern SQLITE_DETAIL = Pattern.compile("\\(([^()]*)\\)\\s*$");

    /** The violation behind {@code error}, if it is one. */
    public static Optional<IntegrityViolation> of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException sqlite && sqlite.getResultCode().name().startsWith("SQLITE_CONSTRAINT")) {
                Matcher detail = SQLITE_DETAIL.matcher(sqlite.getMessage());
                String message = detail.find() ? detail.group(1) : sqlite.getMessage();
                return Optional.of(new IntegrityViolation(
                        sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
                        "(sqlite3.IntegrityError) " + message));
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return Optional.of(new IntegrityViolation("23505".equals(sql.getSQLState()), psycopg2Error(sql)));
            }
        }
        return Optional.empty();
    }

    private static String psycopg2Error(SQLException error) {
        String type = switch (error.getSQLState()) {
            case "23505" -> "psycopg2.errors.UniqueViolation";
            case "23503" -> "psycopg2.errors.ForeignKeyViolation";
            case "23502" -> "psycopg2.errors.NotNullViolation";
            case "23514" -> "psycopg2.errors.CheckViolation";
            default -> "psycopg2.errors.IntegrityError";
        };
        String message = error.getMessage();
        if (error instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
            ServerErrorMessage server = psql.getServerErrorMessage();
            message = server.getMessage() + (server.getDetail() != null ? "\nDETAIL:  " + server.getDetail() : "")
                    + "\n";
        }
        return "(" + type + ") " + message;
    }

    /** {@code str(sqlalchemy.exc.IntegrityError)} for a statement and its parameters, as Python formats them. */
    public String describe(String statement, String parameters) {
        return driverError + "\n[SQL: " + statement + "]\n[parameters: " + parameters + "]\n"
                + "(Background on this error at: https://sqlalche.me/e/20/gkpj)";
    }
}
