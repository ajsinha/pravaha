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
package com.ash.messaging.pravaha.it.crash;

import java.nio.file.Path;

import com.ash.messaging.pravaha.common.io.StateOwnership;

/**
 * A node, in a real operating-system process, that can be killed.
 *
 * <p>Gates P4 and P7 both ask for something no test in this repository did: a node that is
 * <em>killed</em> rather than shut down. Every recovery and ownership test until now interrupted a
 * run in-process, which exercises the code that runs on the way down — and the whole point of
 * {@code SIGKILL} is that no such code runs. A marker left by a graceful stop is deleted; a marker
 * left by a kill is not, and that difference is the case the design exists for.
 *
 * <p>Arguments: the state directory, the node id, and the Flight port to record in the marker.
 * Prints {@code CLAIMED} once the directory is held and then blocks for ever, so the parent decides
 * when it dies. Anything that goes wrong prints {@code FAILED <message>} and exits non-zero, because
 * a child that dies silently is indistinguishable to the parent from one that was killed.
 */
public final class CrashNodeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("FAILED expected <stateDir> <nodeId> <port>");
            System.exit(2);
        }
        Path directory = Path.of(args[0]);
        String nodeId = args[1];
        int port = Integer.parseInt(args[2]);
        try {
            StateOwnership.claim(directory, StateOwnership.Owner.current(nodeId, "127.0.0.1", port));
        } catch (RuntimeException refused) {
            // Printed rather than thrown, so the parent can assert on the refusal text. A stack
            // trace on stderr would make the parent's job guesswork.
            System.out.println("REFUSED " + refused.getMessage().replace('\n', ' '));
            System.out.flush();
            System.exit(3);
            return;
        }
        System.out.println("CLAIMED " + ProcessHandle.current().pid());
        System.out.flush();
        // Held open until killed. The lease refresher runs on its own daemon thread, so the marker
        // stays live exactly as it would on a running node.
        Thread.sleep(Long.MAX_VALUE);
    }

    private CrashNodeMain() {}
}
