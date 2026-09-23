package com.college.pap.pool;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Validates pooled connections — real JDBC uses {@code SELECT 1}. */
public final class ConnectionValidator {

    public boolean validate(PapConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (connection.isDestroyed() || connection.nativeHandle() == null) {
            return false;
        }
        Object handle = connection.nativeHandle();
        if (handle instanceof Connection jdbc) {
            return validateJdbc(jdbc);
        }
        return connection.isValid();
    }

    private boolean validateJdbc(Connection jdbc) {
        try {
            if (jdbc.isClosed()) {
                return false;
            }
            try (Statement st = jdbc.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
}
