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

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Java and Android.
 * Events are posted ({@link #post(Object)}) to the bus, which delivers it to subscribers that have a matching handler
 * method for the event type.
 * To receive events, subscribers must register themselves to the bus using {@link #register(Object)}.
 * Once registered, subscribers receive events until {@link #unregister(Object)} is called.
 * Event handling methods must be annotated by {@link Subscribe}, must be public, return nothing (void),
 * and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /**
     * Log tag, apps may override it.
     */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;

    //使用ThreadLocal保证一个线程只有PostingThreadState对象
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    //切换到主线程的Poster
    // @Nullable
    private final Poster mainThreadPoster;
    //切换到子线程的Poster
    private final BackgroundPoster backgroundPoster;
    //无论当前是什么线程，都直接扔到这里执行
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    //线程池，backgroundPoster和asyncPoster最终都是交给该线程池去执行任务的
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;

    /**
     * Convenience singleton for apps using a process-wide EventBus instance.
     */
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /**
     * For unit test primarily.
     * 清楚缓存
     */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        //用于打印日志
        logger = builder.getLogger();
        //Key为Class<?>，存储Event类型。Value为CopyOnWriteArrayList<Subscription>，存储方法信息列表。
        //在register方法会将订阅者的方法存起来，在unregister会移除
        //当调用post方法接收到通知之后，就根据Event类型获取到方法信息列表，再使用反射调用方法。
        subscriptionsByEventType = new HashMap<>();
        //Key为Object，存储订阅者。Value为List<Class<?>，存储该订阅者所有的Event类型。
        //主要用于在调用unregister方法之后，从subscriptionsByEventType中移除相应的数据。
        typesBySubscriber = new HashMap<>();
        //Key为Class<?>，表示Event的类型。Value为Object，表示Event本身。
        //sticky用百度翻译出来是 粘性的。直接说作用吧，不知道怎么翻译比较好。
        //假设在启动某个Activity之前postSticky了一个 Event 对象，在启动一个新的Activity并register过程中
        //该Activity的某个订阅方法的sticky为true，并且该方法接收的对象是 Event
        //那调用register过程中就会调用该订阅方法
        //具体留到下面分析register方法的时候通过源码说明清楚。
        stickyEvents = new ConcurrentHashMap<>();
        //下面这几个都和线程相关的，先不讲，后面线程切换再统一讲。
        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        //indexCount没什么用，只用在toString()方法里面。重要的是builder里面的subscriberInfoIndexes
        //留到下面的Finder说明清楚，因为这个和Finder的关系比较大。
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        //重要！！！EventBus内部通过订阅者找到订阅方法就是通过该对象进行查找。
        //以下内容可以留到下面的Finder解析再看，这里可以不看，这里只是简单介绍。
        //可以看到，这里在声明的时候传递了builder.subscriberInfoIndexes
        //这里先简单介绍subscriberInfoIndexes的作用，后面再通过源码详细介绍。
        //在介绍subscriberInfoIndexes之前
        //有一件事需要先说明，EventBus扫描一个类里面有哪些方法有两种方式。
        //一种是使用反射，遍历所有方法，找到符合规定的方法。而另一种，就是使用subscriberInfoIndexes。
        //要设置subscriberInfoIndexes的值，只能手动创建EventBus
        //EventBus内部提供了build()方法来作为建造者模式创建。
        //在build方法里面有一个方法addIndex
        //需要传递一个SubscriberInfoIndex对象，而该对象最终获取到的是
        //一个SubscriberInfo对象，该对象有几个方法，需要返回订阅者和订阅方法。
        //所以这种方式和反射类似，只不过从性能的角度分析，这种方式性能更好，因为反射是需要遍历所有方法。
        //而这种方式直接将需要的方法告诉EventBus。但这种方式写起来太麻烦了
        //就我个人而言，如果不是对性能造成严重的影响或者对性能有比较高的要求，我是不会这样写的
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        //当invoke方法的时候，是否通过logger打印出来，默认为true。
        logSubscriberExceptions = builder.logSubscriberExceptions;
        //当一个Event没有找到订阅对象的时候，是否通过logger打印出来，默认为true
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        //当invoke方法的时候，出现InvocationTargetException且不是SubscriberExceptionEvent
        //是否post一个SubscriberExceptionEvent事件，默认为true
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        //当一个Event没有找到订阅对象的时候，是否通过post一个NoSubscriberEvent事件，默认为true
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        //当invoke方法的时候，出现InvocationTargetException且不是SubscriberExceptionEvent
        //是否抛出异常，默认为false
        throwSubscriberException = builder.throwSubscriberException;
        //事件继承。
        //下面这段话可以不看，实际上看了也没什么意义，因为有可能看完就忘了，留到后面再回过头过来看
        //对于register的subscribe方法，如果为true。则只要上面的stickyEvents存在订阅方法的Event相同的类或者
        //子类就会调用订阅方法
        //对于post方法，如果为true，在post一个事件的时候，会找到该Event的所有Interface和父类
        //然后将这些类都post出去
        //默认为true
        eventInheritance = builder.eventInheritance;
        //线程池，ThreadMode为BACKGROUND和ASYNC最终都会交给该线程池执行
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    public void register(Object subscriber) {
        Class<?> subscriberClass = subscriber.getClass();
        //这里代码比较复杂，先放一边，直接说结论。
        //调用这个方法之后，就能找到订阅者所有符合要求的方法，并获取到这些方法必要的信息，
        //存储到SubscriberMethod里面，并返回一个SubscriberMethod的List。
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    // Must be called in synchronized block
    //必须在同步代码块里面调用
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        //校验订阅者和订阅方法
        Class<?> eventType = subscriberMethod.eventType;
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        //取出订阅方法的列表
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        //当列表为空的时候，就new一个，并put进去
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            //当不为空的时候，就判断是否存在相同的订阅方法，如果存在就抛异常
            //判断方式实际上就是调用Subscription的equlas方法，而Subscription内部又会调用SubscriberMethod的equals方法
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        //如果可以执行到这里，表示不存在相同的订阅方法，那就将遍历到的订阅方法插入到合适的位置上
        int size = subscriptions.size();
        //遍历subscriptions，这里看到结束的条件为i<=size
        //看起来好像会出现溢出一样，这里往下看就能知道为什么这样做了。
        for (int i = 0; i <= size; i++) {
            //先看后半段的判断，根据方法执行的优先级将方法插入到合适的位置
            //在i=size之前，也就是说遍历了整个subscriptions都没找到合适的位置
            //就将订阅方法插入到subscriptions的末尾
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        //将订阅者作为key，Event类型的Class列表作为Value存起来。
        //这里只是做一个存储操作，然后当调用unregister方法的时候，typesBySubscriber就会发挥出它的作用。
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);

        //如果订阅方法的sticky为true，就会执行下面的代码
        if (subscriberMethod.sticky) {
            //在构造方法已经对这个字段解释过了，这里再根据源码解释一次
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                //遍历stickyEvents里面的数据，stickyEvents这个字段数据会在调用postSticky方法之后新增数据
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    //当订阅方法的Event和遍历到的Event相同或者是父类，就会post。
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }

    /**
     * Checks if the current thread is running in the main thread.
     * 校验当前是否在主线程运行
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     * 如果没有主线程（例如非安卓设备），会一直返回true。
     * 在这种情况下，主线程订阅者总是运行在posting线程（即调用线程）
     * 而后台订阅者总是运行在backgroundPoster（这是一个成员变量）。
     */
    private boolean isMainThread() {
        return mainThreadSupport == null || mainThreadSupport.isMainThread();
    }

    //判断对象是否已经注册过了
    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /**
     * Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber.
     */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        //通过EventType获取到所有订阅方法
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                //如果订阅者和取消订阅者相同，就移除
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /**
     * Unregisters the given subscriber from all event classes.
     */
    // 将订阅者所有的事件的Class取消注册
    public synchronized void unregister(Object subscriber) {
        //这个字段就是在register里面保存事件的，在取消注册的时候就发挥出它的作用
        //这里取出订阅者所有的事件，并根据事件的Class清理相应的方法
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        //不为空表示存在事件
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                //根据事件类型清理订阅方法
                unsubscribeByEventType(subscriber, eventType);
            }
            //清理完订阅方法后就移除
            typesBySubscriber.remove(subscriber);
        } else {
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /**
     * Posts the given event to the event bus.
     */
    public void post(Object event) {
        //获取一个当前线程的PostingThreadState对象
        PostingThreadState postingState = currentPostingThreadState.get();
        //获取Event Queue
        List<Object> eventQueue = postingState.eventQueue;
        //将接收到的Event加入到EventQueue
        eventQueue.add(event);

        //如果现在在Posting，就结束方法

        //从上面这几行代码可以得到如下信息
        //1：在单线程的情况下，posting过程只会执行一次
        //2：如果在执行的过程中，谁调用了post方法，会将Event添加到Event Queue并结束方法
        //3：再从下面的while (!eventQueue.isEmpty()) 可以得出，只要当前任务没有执行完成
        //就会不断地从EventQueue取出Event并执行
        //4：在多线程的情况下，以上限制将不起到任何作用，因为ThreadLocal只保证一个线程
        //获取到的对象是同一个，并不保证多线程获取到的都是同一个
        if (!postingState.isPosting) {
            //在post之前，先确定调用线程是否为主线程
            postingState.isMainThread = isMainThread();
            //打开posting开关
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                //不断地从Event Queue取出消息，并post，当外部新增消息的时候，也可以获取到
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                //恢复变量的状态
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     * 从订阅者的事件处理方法调用后，进一步的事件传递将被取消。后续订阅者将不会收到该事件。事件通常被高优先级订
     * 阅者取消(see{@link Subscribe#priority()})。取消仅限于在发布线程{@link ThreadMode# post}中运行的事
     * 件处理方法。
     */
    public void cancelEventDelivery(Object event) {
        //获取当前线程的PostingThreadState对象
        PostingThreadState postingState = currentPostingThreadState.get();
        //如果现在不是在Posting
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
            //如果event == null
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
            //如果正在posting的Event和传递过来的Event不是同一个
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
            //如果现在在Posting的方法的ThreadMode不是POSTING
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        //上面所有条件都为false的时候，才能cancel
        //所以可以看出，cancel的条件是比较严格的，所以最好谨慎使用
        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     * <p>
     * 从removeStickyEvent(Object event)可以看到，需要存在并且是同一个
     * 如果在post的时候没有存起来呢，那就可以使用该方法获取，再移除
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     * <p>
     * 使用Class的方法将事件从stickyEvents移除
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            //如果存在该事件，并且两个事件是同一个，才能移除
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     * <p>
     * 移除所有
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    //某个Event是否存在订阅者
    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        //找到所有父类和接口
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    //从subscriptionsByEventType取出Subscription列表
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                //当不为空的时候返回true
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        //从构造方法的注释可知，当该变量为true的时候，就会一直找Event的父类，并post
        if (eventInheritance) {
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                //当有一个为true时就为true
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        //当没找到订阅方法的时候
        if (!subscriptionFound) {
            //根据配置决定是否打印log
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            //根据配置决定是否post一个NoSubscriberEvent，这里判断eventClass != NoSubscriberEvent.class
            //应该是为了防止递归调用
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        //当订阅方法不为空的时候，遍历
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {
                //这里记录这些数据是为了实现中断的功能
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted;
                try {
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        //根据不同的ThreadMode执行不同的代码
        switch (subscription.subscriberMethod.threadMode) {
            //POSTING表示不管当前是什么线程，都执行方法
            case POSTING:
                invokeSubscriber(subscription, event);
                break;
            //只在主线程执行
            case MAIN:
                //当当前是主线程的时候，直接执行
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    //如果不是，将任务插入到主线程
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            //无论在哪个线程，都将任务插入到主线程的队列
            case MAIN_ORDERED:
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            //后台线程
            case BACKGROUND:
                //当当前是主线程的时候，切换到子线程
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {//否则直接执行
                    invokeSubscriber(subscription, event);
                }
                break;
            //不管怎么样，直接切换到子线程执行
            case ASYNC:
                asyncPoster.enqueue(subscription, event);
                break;
            //未知线程模式，抛异常
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * Looks up all Class objects including super classes and interfaces. Should also work for interfaces.
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            //EventType列表
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            //只要当前类不为空，就继续找
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    //找到当前类所有的接口，并添加进去
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    //将父类的Class对象赋到当前类
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /**
     * Recurses through super interfaces.
     */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                //递归获取接口的父接口
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        //获取存储在PendingPost里面的Event
        Object event = pendingPost.event;
        //获取存储在PendingPost里面的Subscription
        Subscription subscription = pendingPost.subscription;
        //回收PendingPost并放入到PendingPost池里面，供下次使用
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /**
     * For ThreadLocal, much faster to set (and get multiple values).
     */
    final static class PostingThreadState {
        //Event队列
        final List<Object> eventQueue = new ArrayList<>();
        //是否还在post
        boolean isPosting;
        //post所在线程是否为主线程
        boolean isMainThread;
        //subscription和Event是在取消的时候用到，包括canceled
        Subscription subscription;
        Object event;
        //是否取消post
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
