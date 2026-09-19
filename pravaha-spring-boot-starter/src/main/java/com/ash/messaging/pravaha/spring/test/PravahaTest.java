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
package com.ash.messaging.pravaha.spring.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.ash.messaging.pravaha.spring.PravahaAutoConfiguration;

/**
 * A test slice for an application's Pravaha code: an embedded engine with the starter's
 * auto-configuration and nothing else, and a {@link PravahaTester} to drive it.
 *
 * <pre>
 * &#64;PravahaTest(properties = {
 *         "pravaha.streams.orders.schema=order_id:STRING,amount:INT64",
 *         "pravaha.queries.order_stats.sql=SELECT COUNT(*) AS orders, SUM(amount) AS revenue FROM orders",
 *         "pravaha.queries.order_stats.keys=orders"})
 * class OrderStatsTest {
 *
 *     &#64;Autowired PravahaTester pravaha;
 *     &#64;Autowired Dashboard dashboard;          // a &#64;Component with a &#64;PravahaListener method
 *
 *     &#64;Test
 *     void revenueAddsUp() {
 *         pravaha.push("orders", new Object[] {"o-1", 40L}, new Object[] {"o-2", 60L});
 *         pravaha.awaitListeners("order_stats");
 *         assertThat(dashboard.revenue()).isEqualTo(100);
 *     }
 * }
 * </pre>
 *
 * <p><strong>What is in the context.</strong> Only {@link PravahaAutoConfiguration} and this slice's
 * own {@link PravahaTestAutoConfiguration}: no web server, no data source, no other auto-configured
 * beans. Of the application's scanned components, only those with a {@code @PravahaListener} method
 * are kept -- they are what a Pravaha test is testing -- plus whatever {@link #includeFilters()}
 * adds. Beans the test declares itself ({@code @Import}, a nested {@code @TestConfiguration}) are
 * kept as always.
 *
 * <p><strong>In memory by default.</strong> The slice clears {@code pravaha.checkpoint.directory},
 * {@code pravaha.registry.journal} and {@code pravaha.dlq.directory}, so an {@code application.yaml}
 * pointing them at real directories does not reach a test. A test that sets one in {@link
 * #properties()} gets it. {@link #checkpoints()} turns checkpointing on in a fresh temporary
 * directory, deleted when the context closes.
 *
 * <p><strong>No sleeping.</strong> A push is applied and committed before {@link PravahaTester#push}
 * returns, so the views answer at once; {@link PravahaTester#awaitListeners} waits until every
 * listener has been handed what was committed, and {@link PravahaTester#awaitView} waits on the
 * view's commits for an answer that arrives from a bound source. Neither polls.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(PravahaTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(PravahaTypeExcludeFilter.class)
@ImportAutoConfiguration({PravahaAutoConfiguration.class, PravahaTestAutoConfiguration.class})
public @interface PravahaTest {

    /** Properties in {@code key=value} form, added to the environment before the context starts. */
    String[] properties() default {};

    /**
     * Checkpoint into a temporary directory, deleted when the context closes. Ignored when {@link
     * #properties()} sets {@code pravaha.checkpoint.directory}.
     */
    boolean checkpoints() default false;

    /** Whether components with a {@code @PravahaListener} method are kept. */
    boolean useDefaultFilters() default true;

    /** More of the application's scanned components to keep. */
    Filter[] includeFilters() default {};

    /** Scanned components to leave out, even ones the default would keep. */
    Filter[] excludeFilters() default {};

    /** Auto-configuration to leave out, as {@code @ImportAutoConfiguration#exclude} would. */
    @AliasFor(annotation = ImportAutoConfiguration.class, attribute = "exclude")
    Class<?>[] excludeAutoConfiguration() default {};
}
