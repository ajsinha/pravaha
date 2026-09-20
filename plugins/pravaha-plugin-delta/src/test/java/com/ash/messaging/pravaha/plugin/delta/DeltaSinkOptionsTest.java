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
package com.ash.messaging.pravaha.plugin.delta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.delta.kernel.types.StructType;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a {@code delta-sink} binding is refused for, and the shape it means -- all of it decided
 * from configuration alone, with no table anywhere.
 *
 * <p>That is not an accident of the test: the registry compares a sink's declared schema and key
 * with the query's output before the sink is opened (PRV-8010), so everything here has to be
 * answerable from {@code configure} alone or the check could not run.
 */
class DeltaSinkOptionsTest {

    @Test
    void theDeclaredSchemaAndKeyAreAnswerableWithoutOpeningAnything() {
        DeltaSinkPlugin sink = configured(Map.of("key.columns", "user_id"));

        assertThat(sink.schema())
                .hasValueSatisfying(schema ->
                        assertThat(schema.fields().stream().map(f -> f.name())).containsExactly("user_id", "total"));
        assertThat(sink.keyColumns()).containsExactly("user_id");
        assertThat(sink.capabilities().transactional()).isTrue();
        assertThat(sink.capabilities().maxBatchRows()).isEqualTo(1000);
        assertThat(sink.name()).isEqualTo("delta-sink");
    }

    @Test
    void upsertModeWithoutAKeyIsRefused() {
        assertThatThrownBy(() -> configured(Map.of()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5056")
                .hasMessageContaining("needs key.columns in upsert mode");
    }

    @Test
    void changelogModeWithAKeyIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("mode", "changelog", "key.columns", "user_id")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5056")
                .hasMessageContaining("key.columns would mean nothing");
    }

    @Test
    void aKeyColumnThatIsNotInTheSchemaIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("key.columns", "customer")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("key column 'customer' is not in the declared schema");
    }

    @Test
    void aFloatingPointKeyIsRefused() {
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(context(Map.of(
                                "path", "/tmp/nowhere",
                                "schema", "ratio:FLOAT64,total:INT64",
                                "key.columns", "ratio"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("A floating-point key");
    }

    @Test
    void aNullableKeyIsRefused() {
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(context(Map.of(
                                "path", "/tmp/nowhere",
                                "schema", "user_id:STRING?,total:INT64",
                                "key.columns", "user_id"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("declared nullable");
    }

    @Test
    void aTimeColumnIsRefusedBecauseDeltaHasNoTimeType() {
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(context(Map.of(
                                "path", "/tmp/nowhere",
                                "schema", "user_id:STRING,at:TIME",
                                "key.columns", "user_id"))))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5051")
                .hasMessageContaining("cannot write column 'at'");
    }

    @Test
    void aChangelogSchemaThatAlreadyUsesTheChangeColumnsIsRefused() {
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(context(Map.of(
                                "path", "/tmp/nowhere",
                                "schema", "user_id:STRING,_weight:INT64",
                                "mode", "changelog"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("already declares '_weight'");
    }

    @Test
    void aTransactionIdThatCannotAlsoBeADirectoryNameIsRefused() {
        assertThatThrownBy(() -> configured(Map.of("key.columns", "user_id", "transaction.id", "a/b")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("transaction.id 'a/b'");
        assertThatThrownBy(() -> configured(Map.of("key.columns", "user_id", "transaction.id", "")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must be 1 to 200 characters");
    }

    @Test
    void anUnknownTypeIsRefusedByName() {
        assertThatThrownBy(() -> DeltaSinkSchema.parse("t", "a:MONEY"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("unknown type 'MONEY'");
        assertThatThrownBy(() -> DeltaSinkSchema.parse("t", "a"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("is not 'name:TYPE'");
    }

    @Test
    void everyTypeTheSinkTakesMapsToADeltaTypeAndBackToItself() {
        StreamSchema schema = DeltaSinkSchema.parse(
                "t",
                "a:BOOLEAN,b:INT8,c:INT16,d:INT32,e:INT64,f:FLOAT32,g:FLOAT64,h:STRING,i:BYTES,"
                        + "j:DATE,k:TIMESTAMP,l:DECIMAL(12,4),m:STRING?");
        StructType delta = DeltaSinkSchema.toDeltaSchema("s", schema, false);

        assertThat(delta.fieldNames()).hasSize(13);
        assertThat(delta.at(12).isNullable()).isTrue();
        assertThat(delta.at(0).isNullable()).isFalse();
        StreamSchema roundTrip = DeltaTypes.toStreamSchema("t", delta);
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            assertThat(roundTrip.field(ordinal).type().typeName())
                    .as("column " + schema.field(ordinal).name())
                    .isEqualTo(schema.field(ordinal).type().typeName());
        }
        assertThat(roundTrip.field(11).type().typeName()).isEqualTo(TypeName.DECIMAL);
    }

    @Test
    void changelogModeAddsItsTwoColumnsAtTheEnd() {
        StructType delta = DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "a:INT64"), true);
        assertThat(delta.fieldNames()).containsExactly("a", "_op", "_weight");
    }

    @Test
    void aTableWhoseColumnsDifferIsNamedColumnByColumn() {
        StructType wanted = DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "a:INT64,b:STRING"), false);
        StructType swapped = DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "b:STRING,a:INT64"), false);
        StructType retyped = DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "a:INT32,b:STRING"), false);
        StructType shorter = DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "a:INT64"), false);
        StructType nullable =
                DeltaSinkSchema.toDeltaSchema("s", DeltaSinkSchema.parse("t", "a:INT64?,b:STRING"), false);

        assertThatThrownBy(() -> DeltaSinkSchema.refuseMismatch("s", "/t", wanted, swapped))
                .hasMessageContaining("column 0 of the table is 'b'");
        assertThatThrownBy(() -> DeltaSinkSchema.refuseMismatch("s", "/t", wanted, retyped))
                .hasMessageContaining("column 'a' is integer in the table and long in this binding");
        assertThatThrownBy(() -> DeltaSinkSchema.refuseMismatch("s", "/t", wanted, shorter))
                .hasMessageContaining("the table has columns [a]");
        assertThatThrownBy(() -> DeltaSinkSchema.refuseMismatch("s", "/t", nullable, wanted))
                .hasMessageContaining("is NOT NULL in the table and nullable in this binding");
        DeltaSinkSchema.refuseMismatch("s", "/t", wanted, wanted);
    }

    @Test
    void theSinkIsDeclaredToTheServiceLoader() {
        assertThat(java.util.ServiceLoader.load(com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin.class).stream()
                        .map(p -> p.get().name())
                        .toList())
                .contains("delta-sink");
    }

    private static DeltaSinkPlugin configured(Map<String, String> extra) {
        DeltaSinkPlugin sink = new DeltaSinkPlugin();
        Map<String, String> options =
                new HashMap<>(Map.of("path", "/tmp/nowhere", "schema", "user_id:STRING,total:INT64"));
        options.putAll(extra);
        sink.configure(context(options));
        return sink;
    }

    private static PluginContext context(Map<String, String> options) {
        return new Ctx("spend_table", options);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Guards the list above: a type added here has to be added to the write path too. */
    @Test
    void theTypesTheSinkNamesAreTheTypesItCanWrite() {
        List<String> named = List.of(
                "BOOLEAN",
                "INT8",
                "INT16",
                "INT32",
                "INT64",
                "FLOAT32",
                "FLOAT64",
                "STRING",
                "BYTES",
                "DATE",
                "TIMESTAMP",
                "DECIMAL(1,0)");
        for (String type : named) {
            StreamSchema one = DeltaSinkSchema.parse("t", "c:" + type);
            assertThat(DeltaSinkSchema.toDeltaType("s", "c", one.field(0).type()))
                    .as(type)
                    .isNotNull();
        }
    }
}
