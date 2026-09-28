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
package com.ash.messaging.pravaha.sql;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The alert statements (ADR-057), read before Calcite, and told apart from the catalogue's and the queries'. */
class AlertStatementsTest {

    private static AlertStatement parse(String sql) {
        ContinuousStatement statement = ContinuousStatements.recognize(sql).orElseThrow();
        assertThat(statement).isInstanceOf(ContinuousStatement.Alert.class);
        return ((ContinuousStatement.Alert) statement).statement();
    }

    @Test
    void createReadsItsViewConditionChannelsAndOptions() {
        AlertStatement.Create create = (AlertStatement.Create) parse("""
                CREATE ALERT low ON inventory.low_stock
                  WHERE warehouse = 'LDN' AND on_hand <= 5 AND qty <> -3 AND note IS NOT NULL AND ok = TRUE
                  NOTIFY buyers, "ops-hook"
                  WITH (severity = 'critical', dedupe = 10m, fire_after = '30s', include = (sku, on_hand));""");
        assertThat(create.name()).isEqualTo("low");
        assertThat(create.ifNotExists()).isFalse();
        assertThat(create.view()).isEqualTo("inventory.low_stock");
        assertThat(create.where())
                .extracting(AlertStatement.Condition::toString)
                .containsExactly("warehouse = 'LDN'", "on_hand <= 5", "qty != -3", "note IS NOT NULL", "ok = TRUE");
        assertThat(create.channels()).containsExactly("buyers", "ops-hook");
        assertThat(create.options())
                .isEqualTo(
                        Map.of("severity", "critical", "dedupe", "10m", "fire_after", "30s", "include", "sku,on_hand"));
    }

    @Test
    void theOtherStatements() {
        assertThat(parse("CREATE ALERT IF NOT EXISTS a ON v NOTIFY c")).isInstanceOf(AlertStatement.Create.class);
        assertThat(parse("ALTER ALERT a NOTIFY c, d"))
                .isEqualTo(new AlertStatement.Alter("a", List.of("c", "d"), Map.of()));
        assertThat(parse("ALTER ALERT a SET (severity = 'info')"))
                .isEqualTo(new AlertStatement.Alter("a", List.of(), Map.of("severity", "info")));
        assertThat(parse("DROP ALERT IF EXISTS a")).isEqualTo(new AlertStatement.Drop("a", true));
        assertThat(parse("pause alert a")).isEqualTo(new AlertStatement.Pause("a"));
        assertThat(parse("RESUME ALERT a;")).isEqualTo(new AlertStatement.Resume("a"));
        assertThat(parse("SNOOZE ALERT a FOR '2h'")).isEqualTo(new AlertStatement.Snooze("a", "2h"));
        assertThat(parse("SNOOZE ALERT a FOR 30m")).isEqualTo(new AlertStatement.Snooze("a", "30m"));
        assertThat(parse("SNOOZE ALERT a FOR PT1H")).isEqualTo(new AlertStatement.Snooze("a", "PT1H"));
        assertThat(parse("ACK ALERT a")).isEqualTo(new AlertStatement.Ack("a"));
        assertThat(parse("SHOW ALERTS")).isEqualTo(new AlertStatement.Show());
    }

    @Test
    void theCataloguesAlterAndTheQueriesPauseAreStillTheirs() {
        assertThat(ContinuousStatements.recognize("ALTER ALERT a SET TAGS ('tier' = 'gold')")
                        .orElseThrow())
                .isInstanceOf(ContinuousStatement.Governance.class);
        assertThat(ContinuousStatements.recognize("ALTER ALERT a OWNER TO ROLE ops")
                        .orElseThrow())
                .isInstanceOf(ContinuousStatement.Governance.class);
        assertThat(ContinuousStatements.recognize("PAUSE CONTINUOUS QUERY q").orElseThrow())
                .isInstanceOf(ContinuousStatement.Pause.class);
        assertThat(ContinuousStatements.recognize("SELECT * FROM alerts")).isEmpty();
        assertThat(ContinuousStatements.isContinuousStatement("SHOW ALERTS")).isTrue();
    }

    @Test
    void whatItDoesNotReadIsRefusedByName() {
        assertThatThrownBy(() -> parse("CREATE ALERT a ON v WHERE x = 1 OR y = 2 NOTIFY c"))
                .hasMessageContaining("PRV-2072")
                .hasMessageContaining("OR is not built");
        assertThatThrownBy(() -> parse("CREATE ALERT a ON v WHERE x = y NOTIFY c"))
                .hasMessageContaining("PRV-2070")
                .hasMessageContaining("Comparing two columns");
        assertThatThrownBy(() -> parse("CREATE ALERT a ON v")).hasMessageContaining("expected NOTIFY");
        assertThatThrownBy(() -> parse("CREATE ALERT a ON v NOTIFY c, c")).hasMessageContaining("named twice");
        assertThatThrownBy(() -> parse("SNOOZE ALERT a")).hasMessageContaining("expected FOR");
        assertThatThrownBy(() -> parse("CREATE ALERT a ON v NOTIFY c WITH (x = 1, x = 2)"))
                .hasMessageContaining("given twice");
    }
}
