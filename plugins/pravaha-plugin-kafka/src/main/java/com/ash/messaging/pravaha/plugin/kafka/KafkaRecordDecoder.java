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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A Kafka record's value, read back into a row: the inverse of {@link KafkaRecords}.
 *
 * <p><strong>{@code format: json}</strong>: the value is a JSON object whose members are the row's
 * columns, matched to the declared schema by name (exactly, then ignoring case). A member the schema
 * does not name is ignored; a column the value does not carry is null, and refused if the column is
 * not nullable. Every row is an insertion, weight {@code +1}. This is what {@code kafka-sink} writes
 * in upsert mode, and what most producers of JSON write.
 *
 * <p><strong>{@code format: changelog}</strong>: the value is {@code kafka-sink}'s changelog envelope,
 * {@code {"op":"insert"|"delete","weight":n,"row":{...}}}, and the row carries the envelope's weight --
 * so a retraction written by one query's sink arrives as a retraction in another's source. The weight
 * is required and must not be zero; {@code op}, when present, must agree with its sign.
 *
 * <p>Values are read the way {@link KafkaRecords} writes them: numbers as JSON numbers (an integer
 * column refuses a fraction or a value out of its range; a {@code DECIMAL} is read exactly and refused
 * rather than rounded when it has more places than its scale or more digits than its precision), a
 * float also from {@code "NaN"}, {@code "Infinity"} and {@code "-Infinity"}, {@code BYTES} from base64,
 * {@code DATE} from {@code 2026-09-19}, {@code TIME} from {@code 10:15:30.5}, {@code TIMESTAMP} from an
 * ISO-8601 instant or date-time with an offset, or from a JSON integer of epoch milliseconds. A string
 * column takes a JSON string and nothing else.
 *
 * <p>Anything else is {@link Undecodable}, with a sentence saying what -- which becomes a dead letter
 * or stops the source, never a guessed value.
 */
final class KafkaRecordDecoder implements KafkaValueDecoder {

    private static final JsonFactory JSON = new JsonFactory();

    private final StreamSchema schema;
    private final boolean changelog;
    private final int eventTimeOrdinal;
    private final Map<String, Integer> exact = new HashMap<>();
    private final Map<String, Integer> folded = new HashMap<>();

    KafkaRecordDecoder(StreamSchema schema, boolean changelog, int eventTimeOrdinal) {
        this.schema = schema;
        this.changelog = changelog;
        this.eventTimeOrdinal = eventTimeOrdinal;
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            exact.put(schema.field(ordinal).name(), ordinal);
            folded.putIfAbsent(schema.field(ordinal).name().toLowerCase(Locale.ROOT), ordinal);
        }
    }

    @Override
    public Row decode(byte[] value, long recordTimestampMillis) throws Undecodable {
        Object[] values = new Object[schema.fieldCount()];
        long weight = 1L;
        try (JsonParser parser = JSON.createParser(value)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new Undecodable("the value is not a JSON object");
            }
            if (changelog) {
                weight = envelope(parser, values);
            } else {
                row(parser, values);
            }
            if (parser.nextToken() != null) {
                throw new Undecodable("the value has more after its JSON object");
            }
        } catch (IOException e) {
            throw new Undecodable("the value is not valid JSON: " + firstLine(e.getMessage()));
        }
        return KafkaValueDecoder.finish(schema, values, eventTimeOrdinal, weight, recordTimestampMillis);
    }

    /** {@code {"op":..,"weight":n,"row":{..}}}, in any member order; returns the weight. */
    private long envelope(JsonParser parser, Object[] values) throws IOException, Undecodable {
        Long weight = null;
        String op = null;
        boolean sawRow = false;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            switch (name) {
                case "row" -> {
                    if (token != JsonToken.START_OBJECT) {
                        throw new Undecodable("the changelog member 'row' is not a JSON object");
                    }
                    row(parser, values);
                    sawRow = true;
                }
                case "weight" -> {
                    if (token != JsonToken.VALUE_NUMBER_INT) {
                        throw new Undecodable("the changelog member 'weight' is not a whole number");
                    }
                    BigInteger raw = parser.getBigIntegerValue();
                    if (raw.bitLength() > 63) {
                        throw new Undecodable("the changelog weight " + raw + " is out of range");
                    }
                    weight = raw.longValue();
                }
                case "op" -> {
                    if (token != JsonToken.VALUE_STRING) {
                        throw new Undecodable("the changelog member 'op' is not a string");
                    }
                    op = parser.getText();
                }
                default -> parser.skipChildren();
            }
        }
        if (!sawRow) {
            throw new Undecodable("the value has no 'row' member, so it is not a kafka-sink changelog record. "
                    + "A topic of plain JSON rows is read with format: json");
        }
        if (weight == null) {
            throw new Undecodable("the changelog record has no 'weight'");
        }
        if (weight == 0L) {
            throw new Undecodable("the changelog weight is zero, which changes nothing and is never written");
        }
        if (op != null) {
            boolean agrees =
                    switch (op) {
                        case "insert" -> weight > 0;
                        case "delete" -> weight < 0;
                        default -> throw new Undecodable("the changelog op '" + op + "' is not insert or delete");
                    };
            if (!agrees) {
                throw new Undecodable("the changelog op '" + op + "' disagrees with its weight " + weight);
            }
        }
        return weight;
    }

    /** The members of an object, the parser positioned on its START_OBJECT; leaves it on END_OBJECT. */
    private void row(JsonParser parser, Object[] values) throws IOException, Undecodable {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            Integer ordinal = exact.get(name);
            if (ordinal == null) {
                ordinal = folded.get(name.toLowerCase(Locale.ROOT));
            }
            if (ordinal == null) {
                parser.skipChildren();
                continue;
            }
            values[ordinal] = token == JsonToken.VALUE_NULL ? null : value(parser, token, ordinal);
        }
    }

    private Object value(JsonParser parser, JsonToken token, int ordinal) throws IOException, Undecodable {
        PravahaType type = schema.field(ordinal).type();
        String column = schema.field(ordinal).name();
        return switch (type.typeName()) {
            case BOOLEAN -> {
                if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                    throw wrong(column, type, token);
                }
                yield token == JsonToken.VALUE_TRUE;
            }
            case INT8 -> (byte) integer(parser, token, column, type, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case INT16 -> (short) integer(parser, token, column, type, Short.MIN_VALUE, Short.MAX_VALUE);
            case INT32 -> (int) integer(parser, token, column, type, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case INT64 -> integer(parser, token, column, type, Long.MIN_VALUE, Long.MAX_VALUE);
            case FLOAT32 -> (float) floating(parser, token, column, type);
            case FLOAT64 -> floating(parser, token, column, type);
            case DECIMAL -> decimal(parser, token, column, (DecimalType) type);
            case STRING -> {
                if (token != JsonToken.VALUE_STRING) {
                    throw wrong(column, type, token);
                }
                yield parser.getText();
            }
            case BYTES -> {
                try {
                    yield Base64.getDecoder().decode(text(parser, token, column, type));
                } catch (IllegalArgumentException e) {
                    throw new Undecodable("column '" + column + "' is BYTES and its value is not base64");
                }
            }
            case DATE -> {
                try {
                    yield Math.toIntExact(
                            LocalDate.parse(text(parser, token, column, type)).toEpochDay());
                } catch (DateTimeParseException | ArithmeticException e) {
                    throw new Undecodable(
                            "column '" + column + "' is DATE and its value is not a date such as " + "2026-09-19");
                }
            }
            case TIME -> {
                try {
                    yield LocalTime.parse(text(parser, token, column, type)).toNanoOfDay();
                } catch (DateTimeParseException e) {
                    throw new Undecodable(
                            "column '" + column + "' is TIME and its value is not a time such as " + "10:15:30.5");
                }
            }
            case TIMESTAMP_LTZ -> timestamp(parser, token, column, type);
            default ->
                throw new Undecodable(
                        "column '" + column + "' has type " + type + ", which this source " + "does not read");
        };
    }

    private static long integer(JsonParser parser, JsonToken token, String column, PravahaType type, long min, long max)
            throws IOException, Undecodable {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw wrong(column, type, token);
        }
        BigInteger value = parser.getBigIntegerValue();
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new Undecodable(
                    "column '" + column + "' is " + type.typeName() + " and " + value + " is out of its range");
        }
        return value.longValue();
    }

    private static double floating(JsonParser parser, JsonToken token, String column, PravahaType type)
            throws IOException, Undecodable {
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getDoubleValue();
        }
        if (token == JsonToken.VALUE_STRING) {
            switch (parser.getText()) {
                case "NaN":
                    return Double.NaN;
                case "Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                default:
                    break;
            }
        }
        throw wrong(column, type, token);
    }

    private static BigDecimal decimal(JsonParser parser, JsonToken token, String column, DecimalType type)
            throws IOException, Undecodable {
        BigDecimal value;
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            value = parser.getDecimalValue();
        } else if (token == JsonToken.VALUE_STRING) {
            try {
                value = new BigDecimal(parser.getText().strip());
            } catch (NumberFormatException e) {
                throw wrong(column, type, token);
            }
        } else {
            throw wrong(column, type, token);
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(type.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + value.toPlainString()
                    + " has more than " + type.scale() + " decimal places; it is refused rather than rounded");
        }
        if (scaled.precision() > type.precision() && scaled.signum() != 0) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + value.toPlainString()
                    + " has more than " + type.precision() + " digits");
        }
        return scaled;
    }

    private static long timestamp(JsonParser parser, JsonToken token, String column, PravahaType type)
            throws IOException, Undecodable {
        if (token == JsonToken.VALUE_NUMBER_INT) {
            BigInteger millis = parser.getBigIntegerValue();
            try {
                return Math.multiplyExact(millis.longValueExact(), 1_000_000L);
            } catch (ArithmeticException e) {
                throw new Undecodable(
                        "column '" + column + "' is TIMESTAMP and " + millis + " milliseconds is out of range");
            }
        }
        String text = text(parser, token, column, type);
        Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                instant = OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                throw new Undecodable("column '" + column + "' is TIMESTAMP and '" + text + "' is neither an "
                        + "ISO-8601 instant (2026-09-19T10:15:30Z) nor epoch milliseconds");
            }
        }
        try {
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is TIMESTAMP and " + text + " is out of range");
        }
    }

    private static String text(JsonParser parser, JsonToken token, String column, PravahaType type)
            throws IOException, Undecodable {
        if (token != JsonToken.VALUE_STRING) {
            throw wrong(column, type, token);
        }
        return parser.getText();
    }

    private static Undecodable wrong(String column, PravahaType type, JsonToken token) {
        return new Undecodable(
                "column '" + column + "' is " + type.typeName() + " and its value is " + describe(token));
    }

    private static String describe(JsonToken token) {
        return switch (token) {
            case VALUE_STRING -> "a string that does not parse as one";
            case VALUE_NUMBER_INT -> "a whole number";
            case VALUE_NUMBER_FLOAT -> "a number with a fraction or exponent";
            case VALUE_TRUE, VALUE_FALSE -> "a boolean";
            case START_OBJECT -> "an object";
            case START_ARRAY -> "an array";
            default -> token.toString();
        };
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
