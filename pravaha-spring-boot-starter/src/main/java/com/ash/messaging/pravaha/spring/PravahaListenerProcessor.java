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
package com.ash.messaging.pravaha.spring;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import com.ash.messaging.pravaha.embedded.PravahaEngine;

/**
 * Finds {@link PravahaListener} methods as beans are created, and subscribes them once the context
 * has started.
 *
 * <p>Two phases on purpose. Finding happens per bean, when the method's shape can be checked and a
 * wrong one refused with the bean's name in the message. Subscribing waits for the context's
 * lifecycle start, because the query a listener names is often registered by another bean's
 * initialisation, and subscribing earlier would find nothing to subscribe to.
 *
 * <p>Stops first on the way down -- listeners are detached and their threads drained before the
 * engine they listen to is closed.
 *
 * <p>Each listener's {@link PravahaListenerErrorHandler} is resolved as it subscribes: the bean its
 * {@link PravahaListener#errorHandler()} names, or else the application's -- the one handler bean
 * no listener names (the {@code @Primary} one if there are several), or, with none, the logging
 * handler {@code pravaha.listener.on-error} selects.
 */
public class PravahaListenerProcessor implements BeanPostProcessor, SmartLifecycle, BeanFactoryAware {

    private final ObjectProvider<PravahaEngine> engine;
    private final ObjectProvider<PravahaProperties> properties;
    private final List<ListenerContainer> containers = new ArrayList<>();
    private volatile boolean running;

    @SuppressWarnings("NullAway.Init") // BeanFactoryAware: Spring sets it before any bean is post-processed
    private BeanFactory beanFactory;

    public PravahaListenerProcessor(
            ObjectProvider<PravahaEngine> engine, ObjectProvider<PravahaProperties> properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        Map<Method, PravahaListener> annotated =
                MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<PravahaListener>)
                        method -> AnnotatedElementUtils.findMergedAnnotation(method, PravahaListener.class));
        if (annotated.isEmpty()) {
            return bean;
        }
        synchronized (containers) {
            annotated.forEach((method, listener) -> containers.add(new ListenerContainer(
                    beanName, bean, AopUtils.selectInvocableMethod(method, bean.getClass()), listener)));
        }
        return bean;
    }

    @Override
    public void start() {
        synchronized (containers) {
            if (running || containers.isEmpty()) {
                running = true;
                return;
            }
            PravahaEngine current = engine.getObject();
            PravahaProperties settings = properties.getIfAvailable(PravahaProperties::new);
            List<ListenerContainer> started = new ArrayList<>();
            try {
                PravahaListenerErrorHandler applicationWide = null;
                for (ListenerContainer container : containers) {
                    PravahaListenerErrorHandler handler;
                    if (container.errorHandlerName().isBlank()) {
                        if (applicationWide == null) {
                            applicationWide = applicationErrorHandler(settings);
                        }
                        handler = applicationWide;
                    } else {
                        handler = namedErrorHandler(container);
                    }
                    container.start(current, settings.getListener().getMaxPending(), handler);
                    started.add(container);
                }
            } catch (RuntimeException e) {
                started.forEach(ListenerContainer::close);
                throw e;
            }
            running = true;
        }
    }

    @Override
    public void stop() {
        synchronized (containers) {
            // Reverse order, so a container started later -- possibly depending on an earlier one's
            // side effects -- is let go of first.
            for (int i = containers.size() - 1; i >= 0; i--) {
                containers.get(i).close();
            }
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private PravahaListenerErrorHandler namedErrorHandler(ListenerContainer container) {
        try {
            return beanFactory.getBean(container.errorHandlerName(), PravahaListenerErrorHandler.class);
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException(
                    "@PravahaListener " + container.listenerName() + " names errorHandler '"
                            + container.errorHandlerName() + "', and no PravahaListenerErrorHandler bean has that name",
                    e);
        }
    }

    /** The handler for listeners that name none; see the class comment for the order. */
    private PravahaListenerErrorHandler applicationErrorHandler(PravahaProperties settings) {
        List<String> candidates = new ArrayList<>();
        if (beanFactory instanceof ListableBeanFactory listable) {
            List<String> named = containers.stream()
                    .map(ListenerContainer::errorHandlerName)
                    .filter(name -> !name.isBlank())
                    .toList();
            for (String name : listable.getBeanNamesForType(PravahaListenerErrorHandler.class)) {
                if (!named.contains(name)) {
                    candidates.add(name);
                }
            }
        }
        if (candidates.size() > 1 && beanFactory instanceof ConfigurableListableBeanFactory configurable) {
            List<String> primary = candidates.stream()
                    .filter(name -> configurable.containsBeanDefinition(name)
                            && configurable.getBeanDefinition(name).isPrimary())
                    .toList();
            if (primary.size() == 1) {
                candidates = primary;
            }
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("@PravahaListener methods that name no errorHandler have "
                    + candidates.size() + " PravahaListenerErrorHandler beans to choose from " + candidates
                    + "; mark one @Primary, or name one in each listener's errorHandler");
        }
        if (candidates.size() == 1) {
            return beanFactory.getBean(candidates.get(0), PravahaListenerErrorHandler.class);
        }
        return settings.getListener().getOnError() == PravahaListenerErrorHandler.Decision.STOP
                ? PravahaListenerErrorHandler.logAndStop()
                : PravahaListenerErrorHandler.logAndContinue();
    }

    /** The listeners found, for tests and for anything reporting on them. */
    public List<ListenerContainer> containers() {
        synchronized (containers) {
            return List.copyOf(containers);
        }
    }
}
