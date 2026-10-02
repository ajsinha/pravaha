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

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * The error body for a request Tomcat refuses before any servlet sees it (TOMCATHTML-1).
 *
 * <p>An encoded {@code /} or {@code \} in a path, a {@code %00}, a header line past {@code
 * server.max-http-request-header-size} or too many headers are rejected by the connector, not by
 * Spring: no filter, no controller and no {@link ApiErrorController} runs, and the answer was
 * Tomcat's own HTML page ({@code <!doctype html>… HTTP Status 400 – Bad Request}). Nothing leaked,
 * but {@code application.yaml}'s contract is that every non-2xx response is an {@link
 * ApiDtos.ApiError}, and a client that parses one error shape broke on the second.
 *
 * <p>Tomcat renders those pages with the host's {@link ErrorReportValve}; this replaces it with one
 * that writes the same {@code ApiError} JSON as every other failure. A {@code 400} is {@link
 * ApiErrors#MALFORMED_REQUEST}; any other status the container produces on its own is {@link
 * ApiErrors#UNHANDLED_REQUEST}. Nothing from the exception is echoed -- the container's own message
 * can quote the rejected bytes -- and the server's version is not named, as the HTML page did not.
 */
@Configuration(proxyBeanMethods = false)
public class ContainerErrorReport {

    /** Installs {@link JsonErrorReportValve} as the host's error report valve, before the host starts. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> apiErrorReportValve() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }

    /**
     * Tomcat's error report, as an {@code ApiError}. Public with a no-argument constructor because
     * the host instantiates it by class name.
     */
    public static class JsonErrorReportValve extends ErrorReportValve {

        public JsonErrorReportValve() {
            setShowReport(false);
            setShowServerInfo(false);
        }

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int status = response.getStatus();
            // The same gate as Tomcat's own: only an error nothing has answered yet. A response an
            // endpoint or ApiErrorController already wrote is left exactly as it is.
            if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
                return;
            }
            ErrorCode code = status == 400 ? ApiErrors.MALFORMED_REQUEST : ApiErrors.UNHANDLED_REQUEST;
            String message = status == 400
                    ? "400 Bad Request: the server could not read this request -- an encoded '/' or '\\', a NUL "
                            + "or another character a path may not carry, or a header larger than the server "
                            + "accepts (server.max-http-request-header-size). It reached no endpoint."
                    : status + " from the HTTP server itself, before the request reached an endpoint.";
            String path = request.getDecodedRequestURI();
            if (path == null) {
                path = "";
            }
            try {
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                Writer writer = response.getReporter();
                if (writer != null) {
                    writer.write(json(code, message, path));
                    response.finishResponse();
                }
            } catch (IOException | IllegalStateException gone) {
                // The client has gone or the response cannot be written: there is no one to tell.
            }
        }

        static String json(ErrorCode code, String message, String path) {
            return "{\"code\":" + quoted(code.code())
                    + ",\"message\":" + quoted(message)
                    + ",\"helpUrl\":" + quoted(code.helpUrl())
                    + ",\"timestamp\":" + quoted(Instant.now().toString())
                    + ",\"path\":" + quoted(path) + "}";
        }

        private static String quoted(String value) {
            if (value == null) {
                return "null";
            }
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
