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
package com.ash.messaging.pravaha.api.wire;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The framing both ends of the control protocol speak.
 *
 * <p>It is hand-rolled so that the Python SDK needs no protobuf runtime, and hand-rolled framing is
 * exactly the kind of code that works on the happy path and falls over on a truncated buffer. These
 * bytes cross a network; every one of the ways they can arrive wrong is a test here.
 */
class ControlWireTest {

    @Test
    void whatIsEncodedComesBack() {
        List<String> fields = List.of("pravaha.register", "trade_feed", "SELECT * FROM trade", "0,1");

        assertThat(ControlWire.decode(ControlWire.encode(fields))).isEqualTo(fields);
    }

    @Test
    void emptyStringsSurvive() {
        // A filter value may legitimately be empty, and an empty field must not decode as absent.
        assertThat(ControlWire.decode(ControlWire.encode("a", "", "c"))).containsExactly("a", "", "c");
    }

    @Test
    void noFieldsIsValid() {
        assertThat(ControlWire.decode(ControlWire.encode())).isEmpty();
    }

    @Test
    void nonAsciiSurvives() {
        // Query text is user input and users are not all anglophone. UTF-8 end to end.
        List<String> fields = List.of("प्रवाह", "naïve", "日本語");

        assertThat(ControlWire.decode(ControlWire.encode(fields))).isEqualTo(fields);
    }

    @Test
    void aSubscribeTicketCarriesTheViewAndItsFilters() {
        List<String> decoded =
                ControlWire.decode(ControlWire.subscribeTicket("trade_feed", List.of("product_type", "SWAP")));

        assertThat(decoded).containsExactly("subscribe", "trade_feed", "product_type", "SWAP");
    }

    @Test
    void ourRequestsAreRecognisedWithoutBeingParsed() {
        // getStream has to tell a Pravaha subscription ticket from a Flight SQL one. Trying to
        // parse it as protobuf and seeing what happens is not telling.
        assertThat(ControlWire.isOurs(ControlWire.encode("x"))).isTrue();
        assertThat(ControlWire.isOurs(new byte[] {1, 2, 3, 4, 5})).isFalse();
        assertThat(ControlWire.isOurs(new byte[0])).isFalse();
        assertThat(ControlWire.isOurs(null)).isFalse();
    }

    @Test
    void somethingThatIsNotOursIsRefusedRatherThanMisread() {
        assertThatThrownBy(() -> ControlWire.decode(new byte[] {9, 9, 9, 9, 9, 0, 0, 0, 0}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not a Pravaha request");
    }

    @Test
    void aDifferentVersionSaysSoRatherThanGuessing() {
        byte[] encoded = ControlWire.encode("a");
        encoded[4] = 99;

        assertThatThrownBy(() -> ControlWire.decode(encoded))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("different version");
    }

    @Test
    void aTruncatedRequestIsRefusedRatherThanThrowingAnIndex() {
        byte[] encoded = ControlWire.encode("a-reasonably-long-field", "and-another");
        byte[] truncated = new byte[encoded.length / 2];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);

        // A refusal with a code, never an exception with an array index in it.
        assertThatThrownBy(() -> ControlWire.decode(truncated))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-");
    }

    @Test
    void aLengthThatOverrunsTheBufferIsRefused() {
        byte[] encoded = ControlWire.encode("short");
        // Claim the field is enormous. A reader that trusted this would allocate or overrun.
        ByteBuffer.wrap(encoded).putInt(9, Integer.MAX_VALUE);

        assertThatThrownBy(() -> ControlWire.decode(encoded))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void anAbsurdFieldCountIsRefusedBeforeAnythingIsAllocated() {
        byte[] encoded = ControlWire.encode("a");
        ByteBuffer.wrap(encoded).putInt(5, Integer.MAX_VALUE);

        assertThatThrownBy(() -> ControlWire.decode(encoded))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void aNegativeLengthIsRefused() {
        byte[] encoded = ControlWire.encode("short");
        ByteBuffer.wrap(encoded).putInt(9, -1);

        assertThatThrownBy(() -> ControlWire.decode(encoded)).isInstanceOf(PravahaException.class);
    }

    @Test
    void nullFieldsEncodeAsEmptyRatherThanFailing() {
        assertThat(ControlWire.decode(ControlWire.encode(java.util.Arrays.asList("a", null))))
                .containsExactly("a", "");
    }
}
