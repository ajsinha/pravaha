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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public API, exercised the way a client uses it.
 *
 * <p>Through the full HTTP stack rather than by calling controllers directly, because the things
 * that break a client are serialisation, status codes and error shape -- none of which a direct
 * method call exercises.
 *
 * <p>Anonymous access is switched on explicitly, because the node otherwise refuses to start a
 * server that serves everything to unauthenticated callers. These tests are about the API's shape,
 * not its security model -- and saying so here means that if the refusal is ever weakened, this
 * property stops being necessary rather than these tests quietly covering a different configuration.
 * {@code ApiSecurityTest} is where authentication itself is pinned.
 */
@SpringBootTest(
        properties = {
            "pravaha.security.allow-anonymous=true",
            // Port 0, so the operating system assigns one. This is a MockMvc test and needs no
            // Flight client, but the node it boots starts a real Flight server -- and on the
            // default 19090 that collides with anything else holding the port: the other
            // @SpringBootTest in this module when surefire runs them in separate JVMs, and a
            // developer's own node on their own machine. A test that binds a fixed port is
            // fragile whether or not anything is running in parallel.
            "pravaha.flight.port=0"
        })
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String SCHEMA = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    /** For CFG-19: whether the common tags reach the meters, not only the configuration. */
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void registerStream() throws Exception {
        // Idempotent-ish: schema versions are immutable, so a second registration of the same
        // version is refused. Tests share the context, so tolerate that.
        try {
            mvc.perform(post("/api/v1/streams")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new StreamController.RegisterStreamRequest("txn", SCHEMA))));
        } catch (Exception ignored) {
            // already registered by an earlier test in this context
        }
    }

    @Test
    void listsAndFetchesStreams() throws Exception {
        // Found by name, not by position. OpenApiContractTest has identical @SpringBootTest
        // properties, so Spring hands both classes one cached context -- and when it runs first,
        // its "contract_check" stream sorts ahead of "txn". Asserting $[0] passed or failed on
        // surefire's class order rather than on anything the API did.
        mvc.perform(get("/api/v1/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'txn')].fieldCount").value(4));

        mvc.perform(get("/api/v1/streams/txn"))
                .andExpect(status().isOk())
                // The SQL rendering, not a Java enum name: a client should see what SQL calls it.
                .andExpect(jsonPath("$.fields[0].type").value("INT64 NOT NULL"))
                .andExpect(jsonPath("$.fields[0].nullable").value(false));
    }

    /**
     * DOCX-21. The {@code helpUrl} field is part of the contract and stays in every error body.
     * What it carries is the deployment's: this context configures no {@code
     * pravaha.docs.base-url}, so it is empty rather than the dead {@code docs.pravaha.io} link it
     * used to be, and a client reads "this deployment publishes no help pages" from the empty
     * string rather than from a missing field.
     */
    @Test
    void anUnknownStreamIsA400WithTheErrorCodeAndAnEmptyHelpUrlWhenNoneIsConfigured() throws Exception {
        mvc.perform(get("/api/v1/streams/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-2003"))
                .andExpect(jsonPath("$.helpUrl").exists())
                .andExpect(jsonPath("$.helpUrl").value(""))
                .andExpect(jsonPath("$.path").value("/api/v1/streams/nope"));
    }

    @Test
    void aConfiguredHelpBaseReachesTheErrorBody() throws Exception {
        com.ash.messaging.pravaha.api.HelpUrls.configure("http://localhost:17070/help/codes/");
        try {
            mvc.perform(get("/api/v1/streams/nope"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.helpUrl").value("http://localhost:17070/help/codes/PRV-2003"));
        } finally {
            com.ash.messaging.pravaha.api.HelpUrls.configure(null);
        }
    }

    @Test
    void theRegistryAndSinkEndpointsAnswerOverHttpAndAnUnknownNameIsA404() throws Exception {
        // Nothing is registered and nothing bound in this context: empty lists, not errors.
        mvc.perform(get("/api/v1/queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/sinks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(get("/api/v1/queries/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRV-8002"));
        mvc.perform(get("/api/v1/queries/ghost/plan")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/views/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRV-4023"));
    }

    @Test
    void aDiagnosticSerialisesItsRangeAndExplainCanAnswerWithAGraph() throws Exception {
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest("SELECT nope FROM txn"))))
                .andExpect(jsonPath("$.diagnostics[0].range.startLine").value(1))
                .andExpect(jsonPath("$.diagnostics[0].range.startColumn").value(8))
                .andExpect(jsonPath("$.diagnostics[0].range.endColumn").value(11));

        mvc.perform(post("/api/v1/queries/explain?format=graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn WHERE amount > 1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.nodes[0].id").value("n0"))
                .andExpect(jsonPath("$.graph.edges").isArray())
                .andExpect(jsonPath("$.graph.operatorMetrics").doesNotExist());

        mvc.perform(post("/api/v1/queries/explain?format=nonsense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStreamPostedWithAnEventTimeReportsIt() throws Exception {
        mvc.perform(post("/api/v1/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"clicks_http\",\"schema\":\"user:STRING,at:TIMESTAMP\","
                                + "\"eventTime\":\"at\",\"outOfOrderness\":\"PT15S\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventTime").value("at"))
                .andExpect(jsonPath("$.outOfOrderness").value("PT15S"));
    }

    @Test
    void aValidQueryValidates() throws Exception {
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest(
                                "SELECT user_id, amount FROM txn WHERE amount > 100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.outputFields[0].name").value("user_id"))
                .andExpect(jsonPath("$.diagnostics").isEmpty());
    }

    @Test
    void anInvalidQueryIsA200WithValidFalse() throws Exception {
        // Not a 400. A syntax error while someone is mid-word is a normal state of an editor, and
        // returning an error status would make every keystroke look like a failure in the client's
        // logs and metrics.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest("SELECT nope FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("PRV-2002"))
                .andExpect(jsonPath("$.diagnostics[0].helpUrl").exists());
    }

    @Test
    void aBodyWithNoSqlIsARefusalRatherThanALeakedNullPointerException() throws Exception {
        // API-F9. Both bodies -- {} and {"sql":null} -- used to reach the planner, which dereferenced
        // the null and produced PRV-2010 carrying Java's own text, `Cannot invoke "String.length()"
        // because "s" is null`, with a helpUrl for a planning failure that had not happened. On
        // /validate it arrived as a 200 with valid:false, which is the shape of a normal editor
        // diagnostic -- so an internal-exception leak read as "your SQL is wrong".
        //
        // Asserted on both endpoints and both bodies because the two bodies take different paths
        // through Jackson (absent field versus explicit null) and only one of them was ever tried.
        for (String body : new String[] {"{}", "{\"sql\":null}"}) {
            mvc.perform(post("/api/v1/queries/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRV-1050"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no 'sql'")))
                    // E3(b): the accepted form is stated, not just the refusal.
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SELECT")))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("String.length"))))
                    // DOCX-21: the field is there, and empty, because nothing configured a base.
                    .andExpect(jsonPath("$.helpUrl").exists())
                    .andExpect(jsonPath("$.helpUrl").value(""));

            mvc.perform(post("/api/v1/queries/explain")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRV-1050"))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("String.length"))));
        }
    }

    @Test
    void anEmptySqlStringIsStillAnOrdinaryEditorDiagnostic() throws Exception {
        // The other half of API-F9's fix, pinned so it cannot drift: an empty pane is what the
        // console sends between keystrokes, and the lexer refuses it precisely. Widening the
        // missing-field guard to cover blank text would turn the console's idle state into a stream
        // of 400s, which is the thing this endpoint's 200/valid:false shape exists to prevent.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value(org.hamcrest.Matchers.startsWith("PRV-2")));
    }

    @Test
    void validationReportsItsOwnLatency() throws Exception {
        // The console calls this on every keystroke burst and design 24.1 targets under 50 ms, so
        // the endpoint reports what it actually took rather than leaving the client to guess.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedMicros").isNumber());
    }

    @Test
    void explainReturnsBothLevels() throws Exception {
        mvc.perform(post("/api/v1/queries/explain?level=physical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn WHERE amount > 100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("physical"))
                .andExpect(jsonPath("$.plan").value(org.hamcrest.Matchers.containsString("Scan(txn)")));

        mvc.perform(post("/api/v1/queries/explain?level=logical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value(org.hamcrest.Matchers.containsString("Logical")));
    }

    /**
     * API-F8. {@code ?level=} — the parameter present, with nothing after it — answered 200 with
     * {@code level:"physical"}, the same as omitting it, while {@code ?level=PHYSICAL} was
     * refused.
     *
     * <p>Spring's {@code defaultValue} is not the same rule as "absent": it substitutes the
     * default for an <em>empty</em> value too. So the endpoint could not tell a caller who said
     * nothing from one who said nothing usable, and only one of those is a caller to answer.
     */
    @Test
    void apiF8_anEmptyLevelIsARefusalRatherThanAnAbsentOne() throws Exception {
        mvc.perform(post("/api/v1/queries/explain?level=")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-0400"));

        mvc.perform(post("/api/v1/queries/explain?format=")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isBadRequest());

        // The control, and the reason this is a behaviour change worth pinning: omitting the
        // parameter still takes the default.
        mvc.perform(post("/api/v1/queries/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new QueryController.ValidateRequest("SELECT user_id FROM txn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("physical"));
    }

    /**
     * API-F8's mechanism in the two paging endpoints that still used it: {@code
     * @RequestParam(defaultValue = ...)} made an empty {@code ?limit=} mean fifty. Present and empty,
     * or present and not a number, is now refused before anything is looked up.
     */
    @Test
    void anEmptyOrNonNumericPageParameterIsRefusedRatherThanDefaulted() throws Exception {
        for (String query : new String[] {"?limit=", "?offset=", "?limit=ten"}) {
            mvc.perform(get("/api/v1/queries/nothing_here/dead-letters" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRV-0400"));
            mvc.perform(get("/api/v1/debug/sessions/no-such-session/state/window%230" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRV-0400"));
        }
        // The control: absent still takes the default, so the lookup runs and answers for itself.
        mvc.perform(get("/api/v1/debug/sessions/no-such-session/state/window%230"))
                .andExpect(jsonPath("$.code").value("PRV-8013"));
    }

    /**
     * DBG-2. HTTP put every PRV-8xxx in the registry category and answered 400, where Flight answers
     * a session that has ended NOT_FOUND (FLIGHT-1). A client that changes transport must see the
     * same refusal the same way.
     */
    @Test
    void anEndedDebugSessionIsNotFoundOverHttpAsItIsOverFlight() throws Exception {
        mvc.perform(get("/api/v1/debug/sessions/no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRV-8013"));
    }

    /**
     * API-F10. A lone high surrogate is well-formed JSON and is not text: half of a surrogate pair
     * with no other half after it encodes no character.
     *
     * <p>Jackson decoded it rather than refusing the body, and the lone {@code char} reached the
     * SQL lexer, which failed cleanly on it with {@code PRV-2001} — an answer about the query, for
     * a request that never carried one. Every UTF-8 encoder replaces it with U+FFFD, so the SQL
     * this server would log, audit and quote back is not the SQL that was sent.
     */
    @Test
    void apiF10_anUnpairedSurrogateInTheSqlIsRefusedAsABadRequest() throws Exception {
        // PRV-1053, not PRV-1051: two batches fixed API-F10 independently and the earlier refusal
        // won. This one refused the malformed `sql` where the endpoint read its parameters; the
        // other refuses any malformed string in any request body, in the deserializer, which is
        // the only place that sees every one of them and is before the body becomes an argument.
        String loneHighSurrogate = "{\"sql\":\"\\ud800\"}";

        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loneHighSurrogate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1053"));

        mvc.perform(post("/api/v1/queries/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loneHighSurrogate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1053"));

        // A complete pair is a character and is left alone: this refuses half of one, not
        // everything outside the basic plane.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT user_id FROM txn WHERE user_id = '\\ud83d\\ude00'\"}"))
                .andExpect(status().isOk());
    }

    /**
     * API-F10. The finding looked at {@code {"sql":"\ud800"}}, where a lone surrogate reaches the
     * lexer and is refused cleanly, and filed it as a disagreement about a status code. The weight
     * is on the other endpoints: the same escape as a {@code name} answered 201 and put a key in
     * the stream catalogue that no later request could address -- not by URL, which has no UTF-8
     * for a lone surrogate, not in SQL, and over Flight only as {@code ?}, because protobuf
     * substitutes rather than fails.
     */
    @Test
    void aStringCarryingALoneSurrogateIsRefusedBeforeItBecomesAName() throws Exception {
        mvc.perform(post("/api/v1/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\\ud800name\",\"schema\":\"id:INT64,v:STRING\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1053"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'name'")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("\\ud800")));

        // And it is not in the catalogue afterwards, which is the half that matters: the refusal
        // has to arrive before the registration, not beside it.
        mvc.perform(get("/api/v1/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name =~ /bad.*name/)]").isEmpty());

        // The same escape in `sql`, which is where the case found it. 400 now rather than a 200
        // carrying a lexer diagnostic: the body was malformed, and the SQL never had to be read to
        // know it.
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"\\ud800\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-1053"));
    }

    /**
     * The control for the refusal above. A surrogate <em>pair</em> is one character and has to keep
     * working, or the guard has quietly banned every code point above the basic plane.
     */
    @Test
    void aProperSurrogatePairIsOrdinaryTextAndStillRegisters() throws Exception {
        // U+1D4C1, a mathematical script small l, as the pair \ud835\udcc1.
        mvc.perform(post("/api/v1/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"script_\\ud835\\udcc1\",\"schema\":\"id:INT64\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("script_\ud835\udcc1"));
    }

    /**
     * API-F8. An empty parameter is a value the caller sent, and it is not one of the three this
     * endpoint accepts. Spring's {@code defaultValue} applied the default to it, so {@code ?level=}
     * answered 200 with the physical plan while {@code ?level=PHYSICAL} answered 400 -- two answers
     * to the same class of mistake on the same parameter of the same request.
     */
    @Test
    void anEmptyLevelIsRefusedAndAnAbsentOneIsStillTheDefault() throws Exception {
        String body = json.writeValueAsString(new QueryController.ValidateRequest("SELECT user_id FROM txn"));

        mvc.perform(post("/api/v1/queries/explain?level=")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-0400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("an empty value")))
                // The message names every level the endpoint takes, codegen included: it named two
                // of the three, so a caller who mistyped `codegen` was told it was not a level.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("codegen")));

        mvc.perform(post("/api/v1/queries/explain?format=")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRV-0400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("format must be")));

        // The control, and the reason this is not simply "reject the empty string everywhere":
        // omitting the parameter is not a claim about anything, and still means physical/text.
        // `graph` absent is what says `format` defaulted to text rather than being compared
        // against null and falling through to the graph arm -- which is the mistake the explicit
        // default invites, and which the level assertion alone does not catch.
        mvc.perform(post("/api/v1/queries/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("physical"))
                .andExpect(jsonPath("$.graph").doesNotExist());

        mvc.perform(post("/api/v1/queries/explain?format=graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.nodes").isArray());
    }

    @Test
    void anUnboundedGroupByIsRefusedWithAnActionableMessage() throws Exception {
        mvc.perform(post("/api/v1/queries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new QueryController.ValidateRequest(
                                "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("PRV-2050"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("Bound it with a window")));
    }

    @Test
    void statusIsAvailableAsJson() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.engineState").exists())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void statusIsAlsoASelfContainedHtmlPageServedByTheEngine() throws Exception {
        // The console is a separate process (design 23.2a). A console that is the only way to see
        // anything makes itself a single point of failure for diagnosis, so this page has no
        // template engine, no static assets, no JavaScript and no external font -- it must render
        // from one HTTP response on a machine where little else works.
        String html = mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(html)
                .contains("Pravaha")
                .contains("Ask once. Answer always.")
                .contains("<style>")
                .doesNotContain("<script")
                .doesNotContain("http://")
                .doesNotContain("https://fonts");
    }

    @Test
    void theOpenApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/api/v1/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/streams']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/validate']").exists());
    }

    @Test
    void aMethodThePathDoesNotSupportIsRefusedWithoutReachingAHandler_CFG20() throws Exception {
        // CFG-20, the half MockMvc can see, and the reason the other half needs a real container:
        // the status is right here and the BODY IS EMPTY, because MockMvc does not run the servlet
        // container's error dispatch. A test of the error shape written on MockMvc therefore passes
        // whether or not an ErrorController exists, which is how three of six non-2xx responses
        // came back in Spring's own shape -- no code, no message, no helpUrl -- on an API whose
        // stated contract is one error shape. ApiErrorShapeTest asserts the shape over a port.
        assertThat(mvc.perform(delete("/api/v1/streams"))
                        .andExpect(status().isMethodNotAllowed())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .isEmpty();
    }

    @Test
    void everyMetricSaysWhichApplicationAndWhichNodeItCameFrom_CFG19() throws Exception {
        // CFG-19. The seven pravaha_* series carried a `query` label and nothing else, so a fleet
        // scraped into one Prometheus had no label distinguishing Pravaha's own series from any
        // other application's -- and none saying which node a number came from.
        // spring.application.name was set in the shipped application.yaml and reached no tag.
        //
        // Asserted on the registry rather than on a scrape, because the tag has to be on every
        // series this node publishes -- including ones registered after this test runs -- and a
        // MeterFilter is what makes that true. A meter created here is any meter.
        meters.counter("pravaha.test.common.tags").increment();

        io.micrometer.core.instrument.Meter meter = meters.getMeters().stream()
                .filter(m -> m.getId().getName().equals("pravaha.test.common.tags"))
                .findFirst()
                .orElseThrow();

        assertThat(meter.getId().getTag("application")).isEqualTo("pravaha");
        assertThat(meter.getId().getTag("node")).isEqualTo("pravaha-node-01");
    }

    @Test
    void theEndpointAnOperatorChecksFirstIdentifiesTheNode_CFG19() throws Exception {
        // `info` is on the shipped exposure list and the endpoint answered {} -- an empty object,
        // while /api/v1/status two paths away knew the version and the id.
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pravaha.name").value("pravaha"))
                .andExpect(jsonPath("$.pravaha.node").value("pravaha-node-01"))
                .andExpect(jsonPath("$.pravaha.version").isNotEmpty());
    }

    @Test
    void theBoundFlightAddressIsServed_CFG2() throws Exception {
        // CFG-2(b). This context runs with pravaha.flight.port=0, so the configured port is not
        // the bound one -- and no served surface reported the bound one: status had no field for
        // it, and /actuator/health's components are suppressed by the shipped
        // show-details: when-authorized on a node with authentication: none.
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flight").value(org.hamcrest.Matchers.matchesPattern("^.+:[1-9][0-9]*$")));
    }
}
