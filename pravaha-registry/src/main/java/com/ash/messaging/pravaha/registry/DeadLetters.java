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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterCounts;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterPage;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.state.StateErrors;

/**
 * What one principal may learn about a query's dead letters, and may do with them (B5).
 *
 * <p>Written once, here, for the same reason {@link QueryListing} is: the HTTP API, the Flight
 * action, the CLI through Flight, both SDKs and the console all ask this question, and an
 * authorization rule decided five times diverges in the direction of whichever copy somebody
 * forgot. These are the rules, and they are the view's own rules rather than new ones.
 *
 * <h2>Three decisions</h2>
 *
 * <p><strong>Whether the queue exists for you at all</strong> is {@link QueryListing}, unchanged. A
 * name the policy denies is refused whether or not it is registered; a query hidden by provenance
 * (SX-11) answers exactly as a name that was never registered. A dead-letter endpoint that told
 * those apart would be the existence oracle the listing refuses to be, with the added detail that
 * a query <em>has</em> rejected records.
 *
 * <p><strong>Whether you may see the bytes</strong> is decided by the same flag SX-18 withholds a
 * view's counts on: {@link QueryListing.Entry#restricted()}, a row filter on or behind the view.
 * The reasoning is that a dead letter is a record that <em>did not decode</em> -- so there is no
 * row, and a row filter cannot be evaluated against it. The choice is therefore all or nothing,
 * and for a principal entitled to a slice the answer has to be nothing: the record may well be one
 * of the rows their filter excludes, and handing it over because it is malformed would make
 * "unparseable" a way round the filter. The decoder's own sentence goes with the bytes, because it
 * quotes the field it choked on ({@code cannot read '12.50' as a number}) and a value quoted from
 * a row is a row.
 *
 * <p>What a restricted principal keeps is everything that is not the record: the count, the id,
 * the offset, the code, the size in bytes and when. That is the whole of the operational fact --
 * this query is rejecting records, here is how fast, here is which code -- without any of the
 * data. And it is <em>said</em>, in {@link Visible#withheld()}, rather than left as an empty
 * field: an operator who cannot see a record must not be left thinking the record was empty.
 *
 * <p><strong>Whether you may replay</strong> is {@link QueryOwners#mayAdminister}, the check
 * {@code drop}, {@code pause} and {@code resume} use. A replay puts a row into a view that other
 * people read, so it is an act on the whole view and not on the caller's slice of it -- and that
 * check is the view's owner, a grant or an admin, never a reader as such: being shown a slice of
 * something is the weakest claim on it there is. Reading a dead letter's bytes and
 * replaying it are therefore two different rights, and a deployment that separates operators from
 * readers gets to separate them here.
 *
 * <p>Every decision is audited through the node's own sink, with the verbs {@code dlq.list},
 * {@code dlq.show}, {@code dlq.count} and {@code dlq.replay}.
 */
public final class DeadLetters {

    /** What is said in place of the bytes when the caller's access to the view is row-filtered. */
    public static final String WITHHELD = "the record is withheld: your access to this view is row-filtered, and a "
            + "record that failed to decode has no row for that filter to be applied to. The count, the "
            + "offset and the code are not withheld.";

    private final QueryRegistry registry;
    private final QueryListing listing;
    private final SecurityPolicy policy;
    private final AuditSink audit;
    private final DeadLetterStore store;

    public DeadLetters(QueryRegistry registry, SecurityPolicy policy, AuditSink audit, DeadLetterStore store) {
        this.registry = registry;
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.listing = new QueryListing(registry, this.policy, this.audit);
        this.store = store == null ? DeadLetterStore.NONE : store;
    }

    /** The store behind this, for a surface that needs to say whether a queue is configured at all. */
    public DeadLetterStore store() {
        return store;
    }

    /**
     * One entry as this principal may see it.
     *
     * @param redacted the bytes and the decoder's sentence are not this caller's to see
     */
    public record Visible(DeadLetterEntry entry, boolean redacted) {

        /** The bytes, or nothing when they are withheld. */
        public byte[] raw() {
            return redacted ? new byte[0] : entry.letter().raw();
        }

        /** The decoder's sentence, or empty when it is withheld. */
        public String reason() {
            return redacted ? "" : entry.letter().reason();
        }

        /** Why the record is not here, or empty when it is. */
        public String withheld() {
            return redacted ? WITHHELD : "";
        }

        /** How large the record is. Disclosed either way: a length is not a row. */
        public int size() {
            return entry.letter().size();
        }
    }

    /** A page of a query's dead letters, with what the caller may know about the queue as a whole. */
    public record View(String query, List<Visible> entries, DeadLetterPage page, DeadLetterCounts counts) {

        public View {
            entries = List.copyOf(entries);
        }
    }

    /** What one replay did, and the id of the entry a second failure wrote. */
    public record Replayed(String id, DeadLetterEntry.Replay outcome, String detail, String newId) {}

    /**
     * A page of dead letters, newest first, redacted where the caller's read is row-filtered.
     *
     * @throws PravahaException {@code PRV-7002} when the policy denies the name, {@code PRV-8001}
     *     when it is allowed and there is no such query
     */
    public View page(Principal principal, String query, int offset, int limit) {
        QueryListing.Entry entry = require(principal, query, "dlq.list");
        DeadLetterPage page = store.page(query, offset, limit);
        List<Visible> visible = new ArrayList<>(page.entries().size());
        for (DeadLetterEntry letter : page.entries()) {
            visible.add(new Visible(letter, entry.restricted()));
        }
        return new View(entry.name(), visible, page, store.counts(query));
    }

    /** How deep the queue is. The number a dashboard polls, and never the bytes. */
    public DeadLetterCounts counts(Principal principal, String query) {
        require(principal, query, "dlq.count");
        return store.counts(query);
    }

    /**
     * One entry whole, or as whole as this caller may have it.
     *
     * @throws PravahaException {@code PRV-4091} when no entry with that id is in the queue
     */
    public Visible show(Principal principal, String query, String id) {
        QueryListing.Entry entry = require(principal, query, "dlq.show");
        DeadLetterEntry letter = store.find(query, id).orElseThrow(() -> noSuchLetter(query, id));
        return new Visible(letter, entry.restricted());
    }

    /**
     * Feeds one dead letter back through the query that rejected it.
     *
     * <p>A new row at the current frontier, not a rewind. A record that fails again returns to the
     * queue as a fresh entry and this says so, naming it, rather than retrying: a client that
     * retried the same id would loop on a record that can never decode.
     *
     * @throws PravahaException {@code PRV-7002} when the caller may not administer the view,
     *     {@code PRV-4091} when the id is not in the queue, {@code PRV-4092} when replaying it
     *     could not be correct
     */
    public Replayed replay(Principal principal, String query, String id) {
        // Administering, not reading. A replay changes the view every other reader sees, and
        // The owner, a grant or an admin: a reader, filtered or not, does not qualify as one.
        ContinuousQueryStatements.requireAdministrable(registry, audit, principal, query, "dlq.replay");
        RegisteredQuery registered = registry.find(query)
                .orElseThrow(() -> new PravahaException(
                        StateErrors.DLQ_REPLAY_REFUSED,
                        "'" + query + "' is not registered any more, so there is no query to feed the record back "
                                + "into. A dead letter belongs to the computation that rejected it: re-register the "
                                + "query and let its source deliver the corrected record."));
        DeadLetterEntry entry = store.find(query, id).orElseThrow(() -> noSuchLetter(query, id));
        String newestBefore = newestId(query);
        DeadLetterEntry.Replay outcome = registered
                .feed()
                .replay(
                        entry.letter().stream(),
                        entry.letter().raw(),
                        entry.letter().sourceOffset(),
                        entry.letter().schema(),
                        id);
        store.recordReplay(query, id, outcome);
        if (outcome == DeadLetterEntry.Replay.REPLAYED) {
            return new Replayed(
                    id,
                    outcome,
                    "the record decoded and is a row of '" + query + "' now, applied at the frontier the query "
                            + "has reached -- not at the offset it originally came from.",
                    "");
        }
        String newest = newestId(query);
        return new Replayed(
                id,
                outcome,
                "the record failed to decode again and has gone back on the queue as a new entry. It is not "
                        + "retried: replaying this id again would decode the same bytes with the same decoder.",
                newest.equals(newestBefore) ? "" : newest);
    }

    /** The id of the newest entry in the queue, or empty when there is none. */
    private String newestId(String query) {
        DeadLetterPage page = store.page(query, 0, 1);
        return page.entries().isEmpty() ? "" : page.entries().get(0).id();
    }

    /**
     * The listing entry for this name, or the refusal the listing would give.
     *
     * <p>Allowed-and-absent and hidden-by-provenance both arrive here as an empty {@link
     * Optional}, and both answer {@code PRV-8001}: telling them apart would say that a query
     * reading a stream this caller may not read exists.
     */
    private QueryListing.Entry require(Principal principal, String query, String action) {
        return listing.find(principal, query, action)
                .orElseThrow(() -> new PravahaException(
                        RegistryErrors.NO_SUCH_QUERY,
                        "'" + query + "' is not a query you may see on this server, so it has no dead letters "
                                + "here. GET /api/v1/queries lists the ones you may see."));
    }

    private static PravahaException noSuchLetter(String query, String id) {
        return new PravahaException(
                StateErrors.DLQ_NO_SUCH_LETTER,
                "'" + query + "' has no dead letter with the id '" + id + "'. Either the id is wrong, or the "
                        + "page it came from is stale, or retention has evicted it -- the queue's evicted count "
                        + "says whether that is plausible.");
    }
}
