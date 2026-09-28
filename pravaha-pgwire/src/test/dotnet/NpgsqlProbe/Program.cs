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

// Drives Pravaha's PostgreSQL gateway with Npgsql 4.0.17 -- the driver inside Power BI's PostgreSQL
// connector -- the way Power BI does: open with type loading on (the default), list tables and
// columns, read a view whole (Import), and read it filtered and aggregated (DirectQuery).
//
// Every check prints one line, which NpgsqlClientTest asserts on:
//   OK <name>: <detail>        the check ran and <detail> is what came back
//   REFUSED <name>: <sqlstate> the gateway refused it, as that check expects
//   FAIL <name>: <what>        anything else
// The exit code is the number of FAIL lines.
//
// The navigator queries below (character_sets, tables, columns, the two foreign-key queries and the
// index query) are Power BI Desktop's own text, verbatim, as captured from a real PostgreSQL server's
// statement log in datafusion-contrib/datafusion-postgres issue 218 -- with the table name changed.
// The DirectQuery statements are the shapes Power BI's SQL generator produces (derived tables with
// "_" and "rows" aliases, a trailing LIMIT 1000001); they were written from its documented behaviour,
// not captured, because no Power BI instance was available to capture them from.

using System;
using System.Collections.Generic;
using System.Data;
using System.Globalization;
using System.Linq;
using Npgsql;

static class Program
{
    static int failures;

    static int Main(string[] args)
    {
        if (args.Length < 5)
        {
            Console.Error.WriteLine("usage: NpgsqlProbe <host> <port> <password> <disable|require> <view>");
            return 64;
        }
        string host = args[0];
        int port = int.Parse(args[1], CultureInfo.InvariantCulture);
        string password = args[2];
        bool tls = args[3] == "require";
        string view = args[4];

        // Power BI's own connection is built the same way: a host, a port, the database, a user name
        // and a password. Pooling on, as Power BI leaves it, which is what makes Npgsql send
        // DISCARD ALL when a pooled connection is reused.
        string connectionString =
            $"Host={host};Port={port};Database=pravaha;Username=ann;Password={password};"
            + "Pooling=true;Timeout=15;Command Timeout=30;"
            + (tls ? "SSL Mode=Require;Trust Server Certificate=true;" : "SSL Mode=Disable;");

        using (var conn = new NpgsqlConnection(connectionString))
        {
            Check("open", () =>
            {
                conn.Open(); // runs Npgsql's type-loading queries: pg_type, composites, enums
                // Npgsql 4.0 has no IsSecure; with SSL Mode=Require it refuses to open at all unless
                // the server accepted SSLRequest, so reaching this line under "require" is the proof.
                return $"server={conn.PostgreSqlVersion} sslmode={(tls ? "require" : "disable")}";
            });
            if (conn.State != ConnectionState.Open)
            {
                return Math.Max(failures, 1);
            }

            Check("getschema-tables", () =>
                string.Join(",", conn.GetSchema("Tables").Rows.Cast<DataRow>()
                    .Select(r => $"{r["table_schema"]}.{r["table_name"]}:{r["table_type"]}")));
            Check("getschema-columns", () =>
                string.Join(",", conn.GetSchema("Columns", new[] { null, null, view }).Rows.Cast<DataRow>()
                    .Select(r => $"{r["column_name"]}:{r["data_type"]}:{r["is_nullable"]}:{r["ordinal_position"]}")));

            // Power BI's navigator, verbatim.
            Query(conn, "pbi-charsets", "select character_set_name from INFORMATION_SCHEMA.character_sets");
            Query(conn, "pbi-tables", @"select TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE
        from INFORMATION_SCHEMA.tables
        where TABLE_SCHEMA not in ('information_schema', 'pg_catalog')
        order by TABLE_SCHEMA, TABLE_NAME");
            Query(conn, "pbi-columns", $@"select COLUMN_NAME, ORDINAL_POSITION, IS_NULLABLE, case when (data_type like '%unsigned%') then DATA_TYPE || ' unsigned' else DATA_TYPE end as DATA_TYPE
        from INFORMATION_SCHEMA.columns
        where TABLE_SCHEMA = 'public' and TABLE_NAME = '{view}'
        order by TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");
            Query(conn, "pbi-foreign-keys-out", $@"select
            pkcol.COLUMN_NAME as PK_COLUMN_NAME,
            fkcol.TABLE_SCHEMA AS FK_TABLE_SCHEMA,
            fkcol.TABLE_NAME AS FK_TABLE_NAME,
            fkcol.COLUMN_NAME as FK_COLUMN_NAME,
            fkcol.ORDINAL_POSITION as ORDINAL,
            fkcon.CONSTRAINT_SCHEMA || '_' || fkcol.TABLE_NAME || '_' || '{view}' || '_' || fkcon.CONSTRAINT_NAME as FK_NAME
        from
            (select distinct constraint_catalog, constraint_schema, unique_constraint_schema, constraint_name, unique_constraint_name
                from INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS) fkcon
                inner join
            INFORMATION_SCHEMA.KEY_COLUMN_USAGE fkcol
                on fkcon.CONSTRAINT_SCHEMA = fkcol.CONSTRAINT_SCHEMA
                and fkcon.CONSTRAINT_NAME = fkcol.CONSTRAINT_NAME
                inner join
            INFORMATION_SCHEMA.KEY_COLUMN_USAGE pkcol
                on fkcon.UNIQUE_CONSTRAINT_SCHEMA = pkcol.CONSTRAINT_SCHEMA
                and fkcon.UNIQUE_CONSTRAINT_NAME = pkcol.CONSTRAINT_NAME
        where pkcol.TABLE_SCHEMA = 'public' and pkcol.TABLE_NAME = '{view}'
                and pkcol.ORDINAL_POSITION = fkcol.ORDINAL_POSITION
        order by FK_NAME, fkcol.ORDINAL_POSITION");
            Query(conn, "pbi-foreign-keys-in", $@"select
            pkcol.TABLE_SCHEMA AS PK_TABLE_SCHEMA,
            pkcol.TABLE_NAME AS PK_TABLE_NAME,
            pkcol.COLUMN_NAME as PK_COLUMN_NAME,
            fkcol.COLUMN_NAME as FK_COLUMN_NAME,
            fkcol.ORDINAL_POSITION as ORDINAL,
            fkcon.CONSTRAINT_SCHEMA || '_' || '{view}' || '_' || pkcol.TABLE_NAME || '_' || fkcon.CONSTRAINT_NAME as FK_NAME
        from
            (select distinct constraint_catalog, constraint_schema, unique_constraint_schema, constraint_name, unique_constraint_name
                from INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS) fkcon
                inner join
            INFORMATION_SCHEMA.KEY_COLUMN_USAGE fkcol
                on fkcon.CONSTRAINT_SCHEMA = fkcol.CONSTRAINT_SCHEMA
                and fkcon.CONSTRAINT_NAME = fkcol.CONSTRAINT_NAME
                inner join
            INFORMATION_SCHEMA.KEY_COLUMN_USAGE pkcol
                on fkcon.UNIQUE_CONSTRAINT_SCHEMA = pkcol.CONSTRAINT_SCHEMA
                and fkcon.UNIQUE_CONSTRAINT_NAME = pkcol.CONSTRAINT_NAME
        where fkcol.TABLE_SCHEMA = 'public' and fkcol.TABLE_NAME = '{view}'
                and pkcol.ORDINAL_POSITION = fkcol.ORDINAL_POSITION
        order by FK_NAME, fkcol.ORDINAL_POSITION");
            Query(conn, "pbi-indexes", $@"select i.CONSTRAINT_SCHEMA || '_' || i.CONSTRAINT_NAME as INDEX_NAME, ii.COLUMN_NAME, ii.ORDINAL_POSITION, case when i.CONSTRAINT_TYPE = 'PRIMARY KEY' then 'Y' else 'N' end as PRIMARY_KEY
        from INFORMATION_SCHEMA.table_constraints i inner join INFORMATION_SCHEMA.key_column_usage ii on i.CONSTRAINT_SCHEMA = ii.CONSTRAINT_SCHEMA and i.CONSTRAINT_NAME = ii.CONSTRAINT_NAME and i.TABLE_SCHEMA = ii.TABLE_SCHEMA and i.TABLE_NAME = ii.TABLE_NAME
        where i.TABLE_SCHEMA = 'public' and i.TABLE_NAME = '{view}'
        and i.CONSTRAINT_TYPE in ('PRIMARY KEY', 'UNIQUE')
        order by i.CONSTRAINT_SCHEMA || '_' || i.CONSTRAINT_NAME, ii.TABLE_SCHEMA, ii.TABLE_NAME, ii.ORDINAL_POSITION");

            // Import: the whole view, typed values read through Npgsql's binary decoders.
            Query(conn, "import-select-star", $"SELECT * FROM {view}");
            Query(conn, "import-power-bi-shape",
                $@"select ""_"".""region"", ""_"".""revenue"" from ""public"".""{view}"" ""_""");
            Query(conn, "navigator-preview", $@"select ""$Table"".""region"",
            ""$Table"".""revenue""
        from ""public"".""{view}"" ""$Table""
        limit 4096");

            // DirectQuery: what a slicer, a card and a bar chart ask for.
            Query(conn, "directquery-filter", $@"select ""_"".""region"",
    ""_"".""revenue""
from ""public"".""{view}"" ""_""
where ""_"".""region"" = 'EMEA'
limit 1000001");
            Query(conn, "directquery-group-by", $@"select ""rows"".""region"" as ""region"",
    sum(""rows"".""revenue"") as ""a0""
from
(
    select ""_"".""region"",
        ""_"".""revenue""
    from ""public"".""{view}"" ""_""
) ""rows""
group by ""region""
limit 1000001");
            Query(conn, "directquery-count", $@"select count(*) as ""a0""
from
(
    select ""_"".""region""
    from ""public"".""{view}"" ""_""
) ""_""
limit 1000001");
            Refused(conn, "directquery-top-n", $@"select ""_"".""region"", ""_"".""revenue""
from ""public"".""{view}"" ""_""
order by ""_"".""revenue"" desc
limit 2");

            Check("parameter", () =>
            {
                using (var cmd = new NpgsqlCommand($"SELECT region FROM {view} WHERE revenue > @min", conn))
                {
                    cmd.Parameters.AddWithValue("min", 700000L);
                    return Rows(cmd);
                }
            });
            Refused(conn, "insert-refused", $"INSERT INTO {view} (region, revenue) VALUES ('X', 1)");
        }

        // Pooled reuse: Npgsql resets a pooled connection with DISCARD ALL before handing it out.
        using (var again = new NpgsqlConnection(connectionString))
        {
            Check("pooled-reopen", () =>
            {
                again.Open();
                using (var cmd = new NpgsqlCommand($"SELECT region FROM {view} WHERE region = 'APAC'", again))
                {
                    return Rows(cmd);
                }
            });
        }
        return failures;
    }

    static void Query(NpgsqlConnection conn, string name, string sql)
    {
        Check(name, () =>
        {
            using (var cmd = new NpgsqlCommand(sql, conn))
            {
                return Rows(cmd);
            }
        });
    }

    /** Every row as col=value(Type), rows separated by ';', so the test can see types as well as values. */
    static string Rows(NpgsqlCommand cmd)
    {
        var rows = new List<string>();
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                var cells = new List<string>();
                for (int i = 0; i < reader.FieldCount; i++)
                {
                    object value = reader.GetValue(i);
                    string text = value is DBNull ? "NULL" : Convert.ToString(value, CultureInfo.InvariantCulture);
                    if (value is DateTime dt)
                    {
                        text = dt.ToString("yyyy-MM-dd HH:mm:ss.ffffff", CultureInfo.InvariantCulture) + " " + dt.Kind;
                    }
                    cells.Add($"{reader.GetName(i)}={text}({value.GetType().Name})");
                }
                rows.Add(string.Join(" ", cells));
            }
        }
        return $"{rows.Count} rows: " + string.Join("; ", rows);
    }

    static void Check(string name, Func<string> body)
    {
        try
        {
            Console.WriteLine($"OK {name}: {Flatten(body())}");
        }
        catch (PostgresException e)
        {
            failures++;
            Console.WriteLine($"FAIL {name}: {e.SqlState} {Flatten(e.MessageText)}");
        }
        catch (Exception e)
        {
            failures++;
            Console.WriteLine($"FAIL {name}: {e.GetType().Name} {Flatten(e.Message)}");
        }
    }

    static void Refused(NpgsqlConnection conn, string name, string sql)
    {
        try
        {
            using (var cmd = new NpgsqlCommand(sql, conn))
            using (var reader = cmd.ExecuteReader())
            {
                while (reader.Read()) { }
            }
            failures++;
            Console.WriteLine($"FAIL {name}: expected a refusal, and the statement ran");
        }
        catch (PostgresException e)
        {
            Console.WriteLine($"REFUSED {name}: {e.SqlState} {Flatten(e.MessageText)}");
        }
        catch (Exception e)
        {
            failures++;
            Console.WriteLine($"FAIL {name}: {e.GetType().Name} {Flatten(e.Message)}");
        }
    }

    static string Flatten(string text) => (text ?? "").Replace('\r', ' ').Replace('\n', ' ');
}
