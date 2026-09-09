/**
 * Off-heap memory access.
 *
 * <p>The one place in the codebase where a low-level memory API is named. Everything above this
 * package talks to {@link com.ash.messaging.pravaha.common.memory.MemoryAccess} and
 * {@link com.ash.messaging.pravaha.common.memory.MemoryRegion}, so moving from Agrona to the
 * Foreign Function and Memory API is a configuration change rather than a migration
 * (design section 4.6).
 */
package com.ash.messaging.pravaha.common.memory;
