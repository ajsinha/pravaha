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
package com.ash.messaging.pravaha.server.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Every string in a request body is text that can be written down again.
 *
 * <p>API-F10. {@code "\ud800"} is a syntactically valid JSON escape and not a character: a UTF-16
 * high surrogate with no low surrogate after it encodes no code point at all. Jackson decoded it
 * into a Java {@code String} and handed it on, and what happened next depended entirely on where it
 * went. As the {@code sql} field it reached the lexer and was refused cleanly, which is what
 * API-098(d) looked at and why it was filed as a low-severity disagreement about a status code.
 *
 * <p>It is not, because {@code sql} is not the only string this API takes. {@code POST
 * /api/v1/streams} with {@code {"name":"bad\ud800name", ...}} answers {@code 201} and puts that name
 * in the stream catalogue, where it stays: the name is a key, and every later request that would
 * address it -- reading it, dropping it, naming it in SQL -- has to carry the same half-character
 * back, which a URL path cannot (there is no UTF-8 encoding of a lone surrogate) and SQL will not
 * quote. So a caller can add an entry to the catalogue that nobody, including them, can ever name
 * again. Past the catalogue it gets worse rather than better: a name reaches Flight as a protobuf
 * string, and protobuf's Java encoder replaces an unpaired surrogate with {@code ?} rather than
 * failing, so the same stream is {@code bad?name} to every Flight client and {@code bad\ud800name}
 * over HTTP -- two surfaces disagreeing about the identity of one object. It reaches a state
 * directory and a checkpoint file as a path element, and the file system has its own opinion.
 *
 * <p>So it is refused at the deserializer, which is the earliest point that sees it and the only
 * one that sees all of it. Refusing it per field would mean remembering, in every controller and
 * every future one, that a string from JSON may not be text; refusing it here means a string that
 * reaches a controller is text.
 *
 * <p>Deliberately only the unpaired surrogate. Control characters, emoji, right-to-left marks and
 * every other awkward-but-real character are left alone: they encode, they round-trip, and deciding
 * which of them a name may contain is a different question with a different answer per field. This
 * one has no legitimate use, because there is no text it represents.
 */
public final class WellFormedTextModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public WellFormedTextModule() {
        super("pravaha-well-formed-text");
        addDeserializer(String.class, new WellFormedStringDeserializer());
    }

    /** {@link StringDeserializer}'s answer, checked before it is handed over. */
    static final class WellFormedStringDeserializer extends StdScalarDeserializer<String> {

        private static final long serialVersionUID = 1L;

        WellFormedStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = StringDeserializer.instance.deserialize(parser, context);
            if (value != null) {
                refuseLoneSurrogate(value, parser.currentName());
            }
            return value;
        }

        @Override
        public Object getEmptyValue(DeserializationContext context) {
            return "";
        }
    }

    /**
     * Refuses a string carrying a UTF-16 surrogate with no partner.
     *
     * @param field the JSON property the value arrived under, or null when it has no name
     */
    static void refuseLoneSurrogate(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isSurrogate(c)) {
                continue;
            }
            boolean paired = Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1));
            if (paired) {
                // Skip the low half: it is spoken for, and reading it on its own would report the
                // pair as two lone surrogates.
                i++;
                continue;
            }
            throw new PravahaException(
                    ApiErrors.MALFORMED_TEXT,
                    (field == null ? "a string in this request" : "'" + field + "'")
                            + " contains \\u" + String.format("%04x", (int) c) + " at position " + i
                            + ", a UTF-16 surrogate with no partner. That is not a character: it encodes no "
                            + "code point, it cannot be written as UTF-8, and a name carrying one could not be "
                            + "used again in a URL, in SQL or over Flight. Send the character you meant as a "
                            + "surrogate pair, or as the character itself.");
        }
    }
}
