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
package com.ash.messaging.pravaha.api.plugin;

/**
 * A source whose positions are ordered, so one reader can be shared by queries at different positions
 * without a record reaching any of them twice or out of order (ADR-054).
 *
 * <p>Returned by {@link StreamSourcePlugin#orderedPositions()} when a binding's positions are ordered, and
 * only then; the plugin's readers are then {@link BoundedPartitionReader}s. The two go together: the
 * order tells a query joining behind a running reader from one joining ahead of it, and the bounded read
 * is what lets the one behind stop exactly where the running reader stands.
 *
 * <p>A method rather than an interface the plugin implements, because whether positions are ordered can
 * depend on how the binding is configured: a followed file restarts its line count when the file is
 * replaced, so its positions are ordered only while it is not followed.
 */
public interface OrderedPositions {

    /**
     * Negative when {@code a} comes before {@code b}, zero when they name the same position, positive
     * when {@code a} comes after. {@link SourceOffset#BEGINNING} comes before every other position.
     *
     * <p>Both are positions this plugin's readers handed out for the same partition. A token it cannot
     * read is refused with an exception rather than guessed at: a wrong order would put two readers
     * over the same records.
     */
    int compare(SourceOffset a, SourceOffset b);
}
