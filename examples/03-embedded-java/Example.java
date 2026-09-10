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

// Pravaha embedded in an ordinary Java application.
//
// Mode A of design section 22.2: in-process, no Spring, no cluster, no gateway. This is what lets a
// host application on any Spring version embed the engine -- the property the competitive position
// in design section 2.2 rests on.

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.embedded.PravahaEngine;

public final class Example {

    public static void main(String[] args) {
        Configuration configuration = Configuration.builder()
                .set("pravaha.node.id", "example-engine")
                .set("pravaha.runtime.lanes", "4")
                .build();

        // try-with-resources: close() stops the engine and closes every plugin.
        try (PravahaEngine engine = PravahaEngine.create(configuration)) {
            engine.start();

            System.out.println("engine   : " + engine.instanceId());
            System.out.println("state    : " + engine.state());
            System.out.println("lanes    : " + engine.configuration().getInt("pravaha.runtime.lanes", 1));
            System.out.println("plugins  : " + engine.plugins().names());

            // Several engines can coexist in one JVM, each with its own configuration and plugins.
            // Nothing here is a singleton, which is what makes that work and what keeps tests from
            // becoming order-dependent.
            try (PravahaEngine second = PravahaEngine.create(
                    Configuration.builder().set("pravaha.node.id", "second-engine").build())) {
                second.start();
                System.out.println("second   : " + second.instanceId() + " " + second.state());
            }
        }
    }
}
