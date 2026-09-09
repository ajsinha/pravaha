/*
 * Copyright the Pravaha authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.api;

/**
 * Base class for every checked failure the engine raises.
 *
 * <p>Carries a stable {@link ErrorCode} so that messages can be documented, searched and linked
 * from the console (design section 24.4). An exception without a code is a bug in the throwing code, not a
 * convenience.
 */
public class PravahaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public PravahaException(ErrorCode errorCode, String message) {
        super(format(errorCode, message));
        this.errorCode = errorCode;
    }

    public PravahaException(ErrorCode errorCode, String message, Throwable cause) {
        super(format(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    private static String format(ErrorCode code, String message) {
        return code.code() + "  " + message;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Documentation URL for this failure. */
    public String helpUrl() {
        return errorCode.helpUrl();
    }
}
