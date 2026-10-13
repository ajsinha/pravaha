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
package com.ash.messaging.pravaha.server.folders;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ENVCONFIGDIR-1: the command line's old variable is not taken, alone, as the server's configuration root. */
class CliDirectoryVariableTest {

    /** An environment whose system-environment source holds {@code variables}, as Spring Boot lays one out. */
    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(variables)));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    @Test
    void theVariableAloneIsRefusedNamingTheClashAndTheFix() {
        StandardEnvironment environment = environment(Map.of("PRAVAHA_CONFIG_DIR", "/home/dana/.config/pravaha"));
        assertThat(Binder.get(environment)
                        .bind("pravaha.config.dir", String.class)
                        .get())
                .as("the clash itself: Spring binds the variable to the server's setting")
                .isEqualTo("/home/dana/.config/pravaha");
        assertThatThrownBy(() -> CliDirectoryVariable.refuseIfAlone(environment))
                .hasMessageContaining("PRV-1069")
                .hasMessageContaining("PRAVAHA_CONFIG_DIR=/home/dana/.config/pravaha")
                .hasMessageContaining("PRAVAHA_CLI_DIR")
                .hasMessageContaining("--pravaha.config.dir=");
    }

    @Test
    void anExplicitSettingIsADeliberateChoice() {
        StandardEnvironment commandLine = environment(Map.of("PRAVAHA_CONFIG_DIR", "/home/dana/.config/pravaha"));
        commandLine
                .getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource("--pravaha.config.dir=/srv/config"));
        assertThatCode(() -> CliDirectoryVariable.refuseIfAlone(commandLine)).doesNotThrowAnyException();

        StandardEnvironment yamlSame = environment(Map.of("PRAVAHA_CONFIG_DIR", "/srv/config"));
        yamlSame.getPropertySources()
                .addLast(new MapPropertySource("application.yaml", Map.of("pravaha.config.dir", "/srv/config")));
        assertThatCode(() -> CliDirectoryVariable.refuseIfAlone(yamlSame)).doesNotThrowAnyException();
    }

    @Test
    void aYamlSettingTheVariableOverridesIsStillRefused() {
        StandardEnvironment environment = environment(Map.of("PRAVAHA_CONFIG_DIR", "/home/dana/.config/pravaha"));
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("application.yaml", Map.of("pravaha.config.dir", "/srv/config")));
        assertThatThrownBy(() -> CliDirectoryVariable.refuseIfAlone(environment))
                .hasMessageContaining("PRV-1069");
    }

    @Test
    void noVariableNoRefusal() {
        assertThatCode(() -> CliDirectoryVariable.refuseIfAlone(environment(Map.of("PRAVAHA_CLI_DIR", "/x"))))
                .doesNotThrowAnyException();
    }
}
