/*
 * Copyright (C) 2012-2020 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import android.os.Looper;

import org.greenrobot.eventbus.android.AndroidLogger;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Creates EventBus instances with custom parameters and also allows to install a custom default EventBus instance.
 * Create a new builder using {@link EventBus#builder()}.
 */
@SuppressWarnings("unused")
public class EventBusBuilder {
    //EventBus所使用的线程池
    private final static ExecutorService DEFAULT_EXECUTOR_SERVICE = Executors.newCachedThreadPool();

    //下面大部分在EventBus的构造方法讲过了，只提重要的或没提过的
    boolean logSubscriberExceptions = true;
    boolean logNoSubscriberMessages = true;
    boolean sendSubscriberExceptionEvent = true;
    boolean sendNoSubscriberEvent = true;
    boolean throwSubscriberException;
    boolean eventInheritance = true;
    //在分析Finder的时候提到，当该变量为false时候，会尝试使用两种方式去找到订阅方法
    boolean ignoreGeneratedIndex;
    boolean strictMethodVerification;
    //这里直接使用上面的线程池常量
    ExecutorService executorService = DEFAULT_EXECUTOR_SERVICE;
    //找了一下EventBus对他的使用，只在下面的一个方法有用到，外部并没有使用，不清楚其作用
    List<Class<?>> skipMethodVerificationForClasses;
    //下面有一个addIndex方法，就是将SubscriberInfoIndex添加到这里来，最后拿给Finder使用
    List<SubscriberInfoIndex> subscriberInfoIndexes;
    Logger logger;
    MainThreadSupport mainThreadSupport;

    EventBusBuilder() {
    }

    /**
     * Default: true
     */
    public EventBusBuilder logSubscriberExceptions(boolean logSubscriberExceptions) {
        this.logSubscriberExceptions = logSubscriberExceptions;
        return this;
    }

    /**
     * Default: true
     */
    public EventBusBuilder logNoSubscriberMessages(boolean logNoSubscriberMessages) {
        this.logNoSubscriberMessages = logNoSubscriberMessages;
        return this;
    }

    /**
     * Default: true
     */
    public EventBusBuilder sendSubscriberExceptionEvent(boolean sendSubscriberExceptionEvent) {
        this.sendSubscriberExceptionEvent = sendSubscriberExceptionEvent;
        return this;
    }

    /**
     * Default: true
     */
    public EventBusBuilder sendNoSubscriberEvent(boolean sendNoSubscriberEvent) {
        this.sendNoSubscriberEvent = sendNoSubscriberEvent;
        return this;
    }

    /**
     * Fails if an subscriber throws an exception (default: false).
     * <p/>
     * Tip: Use this with BuildConfig.DEBUG to let the app crash in DEBUG mode (only). This way, you won't miss
     * exceptions during development.
     * 默认为false。如果为true，当在执行订阅方法的时候抛出异常的时候，会抛出一个EventBusException。
     * 建议使用BuildConfig.DEBUG来赋值，这样就不会在开发的时候错过异常。
     */
    public EventBusBuilder throwSubscriberException(boolean throwSubscriberException) {
        this.throwSubscriberException = throwSubscriberException;
        return this;
    }

    /**
     * By default, EventBus considers the event class hierarchy (subscribers to super classes will be notified).
     * Switching this feature off will improve posting of events. For simple event classes extending Object directly,
     * we measured a speed up of 20% for event posting. For more complex event hierarchies, the speed up should be
     * greater than 20%.
     * <p/>
     * However, keep in mind that event posting usually consumes just a small proportion of CPU time inside an app,
     * unless it is posting at high rates, e.g. hundreds/thousands of events per second.
     *
     * 文档太长，就不看了，默认为true。具体作用在解释EventBus源码的时候也提过了，在post的时候，会找到该
     * Event所有的父类和父接口，并对他们post。说实话，就我个人而言，在开发中还没遇过这样的需求，所以如果
     * 在开发的时候没有这种需求，还是手动调用EventBus.build()，然后置为false吧，提升效率。
     */
    public EventBusBuilder eventInheritance(boolean eventInheritance) {
        this.eventInheritance = eventInheritance;
        return this;
    }


    /**
     * Provide a custom thread pool to EventBus used for async and background event delivery. This is an advanced
     * setting to that can break things: ensure the given ExecutorService won't get stuck to avoid undefined behavior.
     * 也可以自己指定EventBus的线程池
     */
    public EventBusBuilder executorService(ExecutorService executorService) {
        this.executorService = executorService;
        return this;
    }

    /**
     * Method name verification is done for methods starting with onEvent to avoid typos; using this method you can
     * exclude subscriber classes from this check. Also disables checks for method modifiers (public, not static nor
     * abstract).
     *
     * 对以onEvent开头的方法进行方法名验证，以避免拼写错误；使用此方法，您可以从此检查中排除订阅者。还禁止
     * 检查方法修饰符(public、not static、not abstract)。
     *
     * 这个就是上面提到的不知道有什么作用的的字段，我在接触EventBus的时候就可以自定义方法名称了，所以对需
     * 要onEvent开头这件事不清楚。
     * 我猜测是不是EventBus不想删掉这段代码，所以才会出现看到这个字段，又没有找到使用的地方。
     */
    public EventBusBuilder skipMethodVerificationFor(Class<?> clazz) {
        if (skipMethodVerificationForClasses == null) {
            skipMethodVerificationForClasses = new ArrayList<>();
        }
        skipMethodVerificationForClasses.add(clazz);
        return this;
    }

    /**
     * Forces the use of reflection even if there's a generated index (default: false).
     */
    public EventBusBuilder ignoreGeneratedIndex(boolean ignoreGeneratedIndex) {
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
        return this;
    }

    /**
     * Enables strict method verification (default: false).
     */
    public EventBusBuilder strictMethodVerification(boolean strictMethodVerification) {
        this.strictMethodVerification = strictMethodVerification;
        return this;
    }

    /**
     * Adds an index generated by EventBus' annotation preprocessor.
     */
    public EventBusBuilder addIndex(SubscriberInfoIndex index) {
        if (subscriberInfoIndexes == null) {
            subscriberInfoIndexes = new ArrayList<>();
        }
        subscriberInfoIndexes.add(index);
        return this;
    }

    /**
     * Set a specific log handler for all EventBus logging.
     * <p/>
     * By default all logging is via {@link android.util.Log} but if you want to use EventBus
     * outside the Android environment then you will need to provide another log target.
     */
    public EventBusBuilder logger(Logger logger) {
        this.logger = logger;
        return this;
    }

    Logger getLogger() {
        if (logger != null) {
            return logger;
        } else {
            return Logger.Default.get();
        }
    }

    MainThreadSupport getMainThreadSupport() {
        if (mainThreadSupport != null) {
            return mainThreadSupport;
        } else if (AndroidLogger.isAndroidLogAvailable()) {
            Object looperOrNull = getAndroidMainLooperOrNull();
            return looperOrNull == null ? null :
                    new MainThreadSupport.AndroidHandlerMainThreadSupport((Looper) looperOrNull);
        } else {
            return null;
        }
    }

    static Object getAndroidMainLooperOrNull() {
        try {
            return Looper.getMainLooper();
        } catch (RuntimeException e) {
            // Not really a functional Android (e.g. "Stub!" maven dependencies)
            return null;
        }
    }

    /**
     * Installs the default EventBus returned by {@link EventBus#getDefault()} using this builders' values. Must be
     * done only once before the first usage of the default EventBus.
     *
     * @throws EventBusException if there's already a default EventBus instance in place
     *
     * 在构建完EventBus之后，可以使用该方法将构建完成的EvnetBus对象存储到EvnetBus的defaultInstance里面
     * 下次要使用EventBus的时候就直接调用EventBus.getDefault()就可以了。
     * 不过需要注意的是，如果EventBus.defaultInstance不等于空就会抛出异常。所以这个方法要谨慎调用。
     *
     * 有了这个方法，就没有必要使用Application将自己构建的EventBus存起来，而是在构建完成之后调用这个方法
     * 存到EventBus的defaultInstance里面，下次使用的时候就直接EventBus.getDefault()。
     */
    public EventBus installDefaultEventBus() {
        synchronized (EventBus.class) {
            if (EventBus.defaultInstance != null) {
                throw new EventBusException("Default instance already exists." +
                        " It may be only set once before it's used the first time to ensure consistent behavior.");
            }
            EventBus.defaultInstance = build();
            return EventBus.defaultInstance;
        }
    }

    /**
     * Builds an EventBus based on the current configuration.
     */
    public EventBus build() {
        return new EventBus(this);
    }

}
