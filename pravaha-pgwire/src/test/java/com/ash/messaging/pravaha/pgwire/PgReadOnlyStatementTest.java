/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.pgwire;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The gateway stays read-only: a continuous-query statement is refused by name, with {@code 25006
 * read_only_sql_transaction} and {@code PRV-6211}, on both query protocols -- never handed to the
 * planner to be called a syntax error -- and the session goes on answering reads afterwards.
 */
class PgReadOnlyStatementTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private PravahaPgWireServer server;

    @BeforeEach
    void setUp() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.commit(100);
        server = new PravahaPgWireServer(new ViewCatalog().register(view)).start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE CONTINUOUS QUERY big KEYED BY (user_id) AS SELECT user_id FROM user_volume",
                "DROP CONTINUOUS QUERY user_volume",
                "PAUSE CONTINUOUS QUERY user_volume",
                "RESUME CONTINUOUS QUERY user_volume",
                "SHOW CONTINUOUS QUERIES",
                "CREATE CONTINUOUS QUERY malformed",
            })
    void everyContinuousQueryStatementIsRefusedOnBothProtocols(String sql) throws Exception {
        for (boolean simple : new boolean[] {true, false}) {
            try (Connection conn = connect(simple);
                    Statement statement = conn.createStatement()) {
                SQLException refused = catchThrowableOfType(SQLException.class, () -> statement.execute(sql));

                assertThat((Object) refused).as(simple ? "simple" : "extended").isNotNull();
                assertThat(refused.getSQLState()).isEqualTo("25006");
                assertThat(refused.getMessage())
                        .contains("PRV-6211")
                        .contains("read-only")
                        .contains("Flight SQL")
                        .doesNotContain("PRV-2001");

                try (ResultSet rs = statement.executeQuery("SELECT total FROM user_volume")) {
                    assertThat(rs.next()).as("the session still answers reads").isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(300L);
                }
            }
        }
    }

    private Connection connect(boolean simple) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "dana");
        if (simple) {
            props.setProperty("preferQueryMode", "simple");
        }
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port() + "/pravaha", props);
    }
}
