/**
 * The public Pravaha SPI.
 *
 * <p>This module has zero third-party dependencies and is compiled to Java 17 bytecode. That is
 * deliberate and load-bearing: it is what plugin authors compile against, the only package visible
 * from a plugin's parent classloader, and the module under semantic-versioning enforcement. Every
 * richer type -- buffers, Netty, Calcite -- stays behind it.
 */
package com.ash.messaging.pravaha.api;
