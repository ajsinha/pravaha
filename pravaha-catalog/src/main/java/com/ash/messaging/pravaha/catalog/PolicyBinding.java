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
package com.ash.messaging.pravaha.catalog;

import java.time.Instant;
import java.util.Map;

/**
 * Where a policy applies (ADR-059 §4): one object, or every object of the policy's tenant carrying a tag
 * -- now and later, since a tag binding is asked at every decision rather than copied onto objects.
 *
 * @param policy the policy's full name
 * @param object the object's full name, for a direct binding; empty for a tag binding
 * @param tagKey for a tag binding, the tag's key; empty for a direct one
 * @param tagValue for a tag binding, the value the tag must have, or empty for any value
 */
public record PolicyBinding(
        String policy, String object, String tagKey, String tagValue, String boundBy, Instant boundAt) {

    public PolicyBinding {
        object = object == null ? "" : object;
        tagKey = tagKey == null ? "" : tagKey;
        tagValue = tagValue == null ? "" : tagValue;
    }

    /** A binding to one object. */
    public static PolicyBinding toObject(String policy, String object, String by, Instant at) {
        return new PolicyBinding(policy, object, "", "", by, at);
    }

    /** A binding to every object carrying {@code key} (with {@code value}, when not empty). */
    public static PolicyBinding toTag(String policy, String key, String value, String by, Instant at) {
        return new PolicyBinding(policy, "", key, value, by, at);
    }

    public boolean byTag() {
        return !tagKey.isEmpty();
    }

    /** Whether this binding reaches an object carrying {@code tags}. */
    public boolean matchesTags(Map<String, String> tags) {
        return byTag() && tags.containsKey(tagKey) && (tagValue.isEmpty() || tagValue.equals(tags.get(tagKey)));
    }

    /** The same policy bound to the same place, whoever bound it and when. */
    public boolean samePlace(PolicyBinding other) {
        return policy.equals(other.policy)
                && object.equals(other.object)
                && tagKey.equals(other.tagKey)
                && tagValue.equals(other.tagValue);
    }

    /** {@code VIEW acme.sales.v}-style for an object; {@code TAG 'pii'} or {@code TAG 'domain=payments'}. */
    public String target() {
        return byTag() ? "TAG '" + tag() + "'" : object;
    }

    /** {@code key} or {@code key=value}. */
    public String tag() {
        return tagValue.isEmpty() ? tagKey : tagKey + "=" + tagValue;
    }
}
