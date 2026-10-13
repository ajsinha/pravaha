/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): the revenue board as a Spring Boot application.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application. It declares no beans of its own: a {@code @Bean} here would be part of every test
 * slice, {@code @PravahaTest}'s included, because the slice starts from this class.
 */
@SpringBootApplication
public class BoardApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(BoardApplication.class, args)));
    }
}
