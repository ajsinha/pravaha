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
package com.ash.messaging.pravaha.plugin.kafka;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Row;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property the Avro reader must have: for a random schema and random values, what {@link
 * AvroWriter} writes is what {@link AvroRowReader} reads -- every column, every logical type,
 * nullable branches, and fields no column names written in between and skipped.
 *
 * <p>Two thousand records over five hundred schemas, from a fixed seed, so a failure is reproducible
 * and a new one is not luck. The generator writes the schema as Avro JSON text, so the parser is on
 * the path as well as the decoder.
 */
class AvroPropertyTest {

    private static final long SEED = 20260919L;
    private static final int SCHEMAS = 500;
    private static final int RECORDS_PER_SCHEMA = 4;

    /**
     * One column: its declared type, its Avro type (with {@code $N} standing for the column's
     * ordinal, so a schema with two of the same named type does not declare the name twice), and how
     * a value of it is written and expected.
     */
    private interface Column {
        String pravaha();

        String avro();

        /** Writes one random value and returns what the reader must produce for it. */
        Object write(Random random, AvroWriter writer);
    }

    @Test
    void whatTheWriterWritesTheReaderReadsForAnySchemaAndAnyValues() throws Undecodable {
        Random random = new Random(SEED);
        int records = 0;
        for (int s = 0; s < SCHEMAS; s++) {
            List<Column> columns = new ArrayList<>();
            int count = 1 + random.nextInt(6);
            for (int c = 0; c < count; c++) {
                columns.add(columnKind(random));
            }
            // A field between the columns that no column names, to be skipped whole.
            int skipped = random.nextInt(3);
            List<String> skips = new ArrayList<>();
            for (int i = 0; i < skipped; i++) {
                skips.add(SKIPPABLE[random.nextInt(SKIPPABLE.length)]);
            }

            StringBuilder declared = new StringBuilder();
            StringBuilder json = new StringBuilder("{\"type\":\"record\",\"name\":\"Generated")
                    .append(s)
                    .append("\",\"fields\":[");
            for (int c = 0; c < columns.size(); c++) {
                declared.append(c == 0 ? "" : ",")
                        .append("c")
                        .append(c)
                        .append(':')
                        .append(columns.get(c).pravaha());
                json.append(c == 0 ? "" : ",")
                        .append("{\"name\":\"c")
                        .append(c)
                        .append("\",\"type\":")
                        .append(columns.get(c).avro().replace("$N", Integer.toString(c)))
                        .append('}');
            }
            for (int i = 0; i < skips.size(); i++) {
                json.append(",{\"name\":\"skipped")
                        .append(i)
                        .append("\",\"type\":")
                        .append(skips.get(i))
                        .append('}');
            }
            json.append("]}");

            StreamSchema schema = KafkaSchema.parse("generated", declared.toString());
            AvroRowReader reader = AvroRowReader.map(schema, AvroSchema.parse(json.toString()), -1);

            for (int r = 0; r < RECORDS_PER_SCHEMA; r++) {
                AvroWriter writer = new AvroWriter();
                Object[] expected = new Object[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    expected[c] = columns.get(c).write(random, writer);
                }
                for (String ignored : skips) {
                    writeSkippable(ignored, random, writer);
                }
                Row row = reader.read(writer.bytes(), 0, 1_000L);
                assertThat(row.values())
                        .as("seed %d, schema %d (%s), record %d", SEED, s, json, r)
                        .containsExactly(expected);
                records++;
            }
        }
        assertThat(records).isEqualTo(SCHEMAS * RECORDS_PER_SCHEMA);
    }

    // ---- the generator -------------------------------------------------------------------------

    private static Column columnKind(Random random) {
        Column base = BASE[random.nextInt(BASE.length)];
        // A third of the columns are nullable, written as Avro's ["null", T] union.
        return random.nextInt(3) == 0 ? nullable(base) : base;
    }

    private static Column nullable(Column base) {
        return new Column() {
            @Override
            public String pravaha() {
                return base.pravaha() + "?";
            }

            @Override
            public String avro() {
                return "[\"null\"," + base.avro() + "]";
            }

            @Override
            public Object write(Random random, AvroWriter writer) {
                if (random.nextInt(4) == 0) {
                    writer.union(0);
                    return null;
                }
                writer.union(1);
                return base.write(random, writer);
            }
        };
    }

    private static Column column(
            String pravaha, String avro, java.util.function.BiFunction<Random, AvroWriter, Object> write) {
        return new Column() {
            @Override
            public String pravaha() {
                return pravaha;
            }

            @Override
            public String avro() {
                return avro;
            }

            @Override
            public Object write(Random random, AvroWriter writer) {
                return write.apply(random, writer);
            }
        };
    }

    private static final Column[] BASE = {
        column("BOOLEAN", "\"boolean\"", (random, writer) -> {
            boolean value = random.nextBoolean();
            writer.bool(value);
            return value;
        }),
        column("INT16", "\"int\"", (random, writer) -> {
            short value = (short) (random.nextInt(0x10000) - 0x8000);
            writer.integer(value);
            return value;
        }),
        column("INT32", "\"int\"", (random, writer) -> {
            int value = random.nextInt();
            writer.integer(value);
            return value;
        }),
        column("INT64", "\"long\"", (random, writer) -> {
            long value = random.nextLong();
            writer.number(value);
            return value;
        }),
        column("INT64", "\"int\"", (random, writer) -> {
            int value = random.nextInt();
            writer.integer(value);
            return (long) value;
        }),
        column("FLOAT32", "\"float\"", (random, writer) -> {
            float value = random.nextFloat() * 1e6f - 5e5f;
            writer.float32(value);
            return value;
        }),
        column("FLOAT64", "\"double\"", (random, writer) -> {
            double value = random.nextDouble() * 1e9 - 5e8;
            writer.float64(value);
            return value;
        }),
        column("FLOAT64", "\"float\"", (random, writer) -> {
            float value = random.nextFloat();
            writer.float32(value);
            return (double) value;
        }),
        column("STRING", "\"string\"", (random, writer) -> {
            String value = text(random);
            writer.text(value);
            return value;
        }),
        column(
                "STRING",
                "{\"type\":\"enum\",\"name\":\"E$N\",\"symbols\":[\"RED\",\"GREEN\",\"BLUE\"]}",
                (random, writer) -> {
                    int index = random.nextInt(3);
                    writer.integer(index);
                    return List.of("RED", "GREEN", "BLUE").get(index);
                }),
        column("BYTES", "\"bytes\"", (random, writer) -> {
            byte[] value = new byte[random.nextInt(8)];
            random.nextBytes(value);
            writer.binary(value);
            return value;
        }),
        column("BYTES", "{\"type\":\"fixed\",\"name\":\"F$N\",\"size\":4}", (random, writer) -> {
            byte[] value = new byte[4];
            random.nextBytes(value);
            writer.fixed(value);
            return value;
        }),
        column(
                "DECIMAL(12,3)",
                "{\"type\":\"bytes\",\"logicalType\":\"decimal\",\"precision\":12,\"scale\":3}",
                (random, writer) -> {
                    BigDecimal value =
                            new BigDecimal(BigInteger.valueOf(random.nextInt(2_000_000_000) - 1_000_000_000), 3);
                    writer.decimalBytes(value, 3);
                    return value;
                }),
        column(
                "DECIMAL(18,4)",
                "{\"type\":\"fixed\",\"name\":\"D$N\",\"size\":8,\"logicalType\":\"decimal\",\"precision\":18,"
                        + "\"scale\":4}",
                (random, writer) -> {
                    BigDecimal value = new BigDecimal(BigInteger.valueOf(random.nextLong() / 1_000_000L), 4);
                    writer.decimalFixed(value, 4, 8);
                    return value;
                }),
        column("DATE", "{\"type\":\"int\",\"logicalType\":\"date\"}", (random, writer) -> {
            int value = random.nextInt(40_000) - 5_000;
            writer.integer(value);
            return value;
        }),
        column("TIME", "{\"type\":\"int\",\"logicalType\":\"time-millis\"}", (random, writer) -> {
            int value = random.nextInt(86_400_000);
            writer.integer(value);
            return value * 1_000_000L;
        }),
        column("TIME", "{\"type\":\"long\",\"logicalType\":\"time-micros\"}", (random, writer) -> {
            long value = (long) random.nextInt(86_400_000) * 1_000L;
            writer.number(value);
            return value * 1_000L;
        }),
        column("TIMESTAMP", "{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}", (random, writer) -> {
            long value = random.nextInt(2_000_000_000) * 1_000L;
            writer.number(value);
            return value * 1_000_000L;
        }),
        column("TIMESTAMP", "{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}", (random, writer) -> {
            long value = random.nextInt(2_000_000_000) * 1_000_000L;
            writer.number(value);
            return value * 1_000L;
        }),
    };

    /** Shapes written into a record and skipped, since no column names them. */
    private static final String[] SKIPPABLE = {
        "{\"type\":\"array\",\"items\":\"long\"}",
        "{\"type\":\"map\",\"values\":\"string\"}",
        "[\"null\",\"double\"]",
        "\"boolean\"",
    };

    private static void writeSkippable(String shape, Random random, AvroWriter writer) {
        switch (shape) {
            case "{\"type\":\"array\",\"items\":\"long\"}" -> {
                int items = random.nextInt(4);
                if (items > 0) {
                    writer.block(items);
                    for (int i = 0; i < items; i++) {
                        writer.number(random.nextLong());
                    }
                }
                writer.block(0);
            }
            case "{\"type\":\"map\",\"values\":\"string\"}" -> {
                int items = random.nextInt(3);
                if (items > 0) {
                    writer.block(items);
                    for (int i = 0; i < items; i++) {
                        writer.text(text(random));
                        writer.text(text(random));
                    }
                }
                writer.block(0);
            }
            case "[\"null\",\"double\"]" -> {
                if (random.nextBoolean()) {
                    writer.union(0);
                } else {
                    writer.union(1).float64(random.nextDouble());
                }
            }
            default -> writer.bool(random.nextBoolean());
        }
    }

    private static String text(Random random) {
        int length = random.nextInt(12);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private static final String ALPHABET = "abcXYZ0129 _-.éन";
}
