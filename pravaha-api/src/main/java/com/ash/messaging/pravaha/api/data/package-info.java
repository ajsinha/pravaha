/**
 * The data model: types, schemas, and zero-copy row access.
 *
 * <p>Nothing here allocates on a per-record basis. Rows are flyweights over an off-heap arena and
 * fields are addressed by ordinal, which is the difference between roughly 500 and roughly 5000
 * CPU cycles per record (design section 29.1).
 */
package com.ash.messaging.pravaha.api.data;
