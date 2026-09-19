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
package com.ash.messaging.pravaha.spring.actuate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint over HTTP, in a real web application: absent under Boot's default exposure, readable
 * once included, and refusing every method but GET.
 */
class PravahaEndpointWebTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class WebApplication {}

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpRequest.Builder get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    @Nested
    @SpringBootTest(classes = WebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @org.springframework.test.context.TestPropertySource(
            properties = {
                "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
                "pravaha.queries.big_txn.keys=user_id",
                "management.endpoint.health.show-components=always"
            })
    class ByDefault {

        @LocalServerPort
        int port;

        @Test
        void theEndpointIsNotServedButTheHealthContributionIs() throws Exception {
            assertThat(send(get(port, "/actuator/pravaha")).statusCode()).isEqualTo(404);
            HttpResponse<String> health = send(get(port, "/actuator/health"));
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"pravaha\"");
        }
    }

    @Nested
    @SpringBootTest(classes = WebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @org.springframework.test.context.TestPropertySource(
            properties = {
                "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                "pravaha.queries.big_txn.sql=SELECT user_id, amount FROM txn WHERE amount > 100",
                "pravaha.queries.big_txn.keys=user_id",
                "management.endpoints.web.exposure.include=health,pravaha"
            })
    class Included {

        @LocalServerPort
        int port;

        @Test
        void theEndpointAnswersReadsAndRefusesEverythingElse() throws Exception {
            HttpResponse<String> all = send(get(port, "/actuator/pravaha"));
            assertThat(all.statusCode()).isEqualTo(200);
            assertThat(all.body()).contains("\"big_txn\"", "\"state\":\"RUNNING\"", "\"lane\":\"own\"");

            HttpResponse<String> one = send(get(port, "/actuator/pravaha/big_txn"));
            assertThat(one.statusCode()).isEqualTo(200);
            assertThat(one.body()).contains("amount > 100");

            assertThat(send(get(port, "/actuator/pravaha/nope")).statusCode()).isEqualTo(404);

            for (String method : new String[] {"POST", "PUT", "DELETE"}) {
                HttpResponse<String> refused = send(get(port, "/actuator/pravaha/big_txn")
                        .method(method, HttpRequest.BodyPublishers.ofString("{}")));
                assertThat(refused.statusCode()).as(method).isEqualTo(405);
            }
        }
    }
}
