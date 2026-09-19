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

import java.io.IOException;

import org.springframework.boot.test.autoconfigure.filter.StandardAnnotationCustomizableTypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import com.ash.messaging.pravaha.spring.PravahaListener;

/**
 * Which of the application's scanned components a {@link PravahaTest} keeps: those declaring a
 * {@link PravahaListener} method, read from the class file without loading the class, and whatever
 * the annotation's own filters add or take away.
 */
final class PravahaTypeExcludeFilter extends StandardAnnotationCustomizableTypeExcludeFilter<PravahaTest> {

    PravahaTypeExcludeFilter(Class<?> testClass) {
        super(testClass);
    }

    @Override
    protected boolean defaultInclude(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        return reader.getAnnotationMetadata().hasAnnotatedMethods(PravahaListener.class.getName())
                || super.defaultInclude(reader, factory);
    }
}
