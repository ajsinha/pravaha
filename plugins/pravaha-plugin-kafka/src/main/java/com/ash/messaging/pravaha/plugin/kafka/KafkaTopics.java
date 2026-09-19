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
package com.ash.messaging.pravaha.plugin.kafka;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * What the sink checks and creates on the brokers when it opens.
 *
 * <p>The target topic must exist: the sink never creates it, since its partition count and, in
 * upsert mode, its {@code cleanup.policy=compact} are decisions for whoever owns the topic. An upsert
 * sink writing to a topic that is not compacted is allowed and logged -- the changes are all there,
 * but retention will eventually delete keys that were never retracted.
 *
 * <p>The staging topic is created when missing, with one partition, {@code cleanup.policy=delete} and
 * {@code staging.retention.ms}. One partition because a checkpoint's changes are named by one offset
 * range and must be replayed in the order they were written. An existing staging topic that is
 * compacted is refused: compaction would keep one record per key and delete the changes in between,
 * which a commit after a restart needs.
 */
final class KafkaTopics {

    private static final System.Logger LOG = System.getLogger(KafkaTopics.class.getName());
    private static final long TIMEOUT_SECONDS = 60;

    private KafkaTopics() {}

    static void prepare(KafkaSinkOptions options) {
        try (Admin admin = Admin.create(options.admin())) {
            if (!exists(admin, options.topic, options)) {
                throw new PravahaException(
                        KafkaErrors.CONNECT_FAILED,
                        "sink '" + options.instanceName + "': topic '" + options.topic + "' does not exist. This sink "
                                + "does not create its target topic; create it"
                                + (options.changelog
                                        ? ""
                                        : " with cleanup.policy=compact, since in upsert mode "
                                                + "the topic is a table keyed by key.columns")
                                + ".");
            }
            if (!options.changelog) {
                cleanupPolicy(admin, options.topic).ifPresent(policy -> {
                    if (!policy.contains(TopicConfig.CLEANUP_POLICY_COMPACT)) {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                "sink ''{0}'' writes upserts and tombstones to topic ''{1}'', whose cleanup.policy is "
                                        + "{2}: retention will delete keys the query never retracted. Set "
                                        + "cleanup.policy=compact to keep the topic equal to the query''s answer.",
                                options.instanceName,
                                options.topic,
                                policy);
                    }
                });
            }
            if (options.transactional) {
                prepareStaging(admin, options);
            }
        } catch (PravahaException e) {
            throw e;
        } catch (ExecutionException e) {
            throw unreachable(options, e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException | RuntimeException e) {
            throw unreachable(options, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(options, e);
        }
    }

    private static void prepareStaging(Admin admin, KafkaSinkOptions options)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (exists(admin, options.stagingTopic, options)) {
            Optional<String> policy = cleanupPolicy(admin, options.stagingTopic);
            if (policy.isPresent() && policy.get().contains(TopicConfig.CLEANUP_POLICY_COMPACT)) {
                throw new PravahaException(
                        KafkaErrors.STAGING_UNUSABLE,
                        "sink '" + options.instanceName + "': staging topic '" + options.stagingTopic
                                + "' is compacted (cleanup.policy=" + policy.get() + "). Compaction keeps the last "
                                + "change per key and deletes the rest, and a commit after a restart replays every "
                                + "change a checkpoint staged; use a topic with cleanup.policy=delete.");
            }
            return;
        }
        NewTopic staging = new NewTopic(options.stagingTopic, Optional.of(1), Optional.empty())
                .configs(Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG,
                        Long.toString(options.stagingRetentionMs)));
        try {
            admin.createTopics(List.of(staging)).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.log(
                    System.Logger.Level.INFO,
                    "sink ''{0}'' created its staging topic ''{1}'' (1 partition, retention.ms={2})",
                    options.instanceName,
                    options.stagingTopic,
                    options.stagingRetentionMs);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                return; // another process created it between the check and the create
            }
            throw new PravahaException(
                    KafkaErrors.STAGING_UNUSABLE,
                    "sink '" + options.instanceName + "' cannot create its staging topic '" + options.stagingTopic
                            + "': " + (e.getCause() == null ? e : e.getCause()).getMessage() + ". Create it yourself "
                            + "(1 partition, cleanup.policy=delete, retention of days), or set transactional: false.",
                    e);
        }
    }

    private static boolean exists(Admin admin, String topic, KafkaSinkOptions options)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            Map<String, TopicDescription> described =
                    admin.describeTopics(List.of(topic)).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return described.containsKey(topic);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return false;
            }
            throw e;
        }
    }

    /** The topic's cleanup.policy, or empty when this principal may not describe its configs. */
    private static Optional<String> cleanupPolicy(Admin admin, String topic) throws InterruptedException {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try {
            Config config = admin.describeConfigs(List.of(resource))
                    .all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(resource);
            ConfigEntry entry = config == null ? null : config.get(TopicConfig.CLEANUP_POLICY_CONFIG);
            return entry == null ? Optional.empty() : Optional.ofNullable(entry.value());
        } catch (ExecutionException | TimeoutException e) {
            return Optional.empty();
        }
    }

    private static PravahaException unreachable(KafkaSinkOptions options, Throwable cause) {
        return new PravahaException(
                KafkaErrors.CONNECT_FAILED,
                "sink '" + options.instanceName + "' cannot check its topics on " + options.bootstrapServers + ": "
                        + cause.getMessage(),
                cause);
    }
}
