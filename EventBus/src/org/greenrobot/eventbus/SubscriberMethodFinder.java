/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        //在EventBus里面用了很多句话描述了这个字段的作用，这里再简单描述
        //当该对象不为空且ignoreGeneratedIndex为false的时候就不会通过反射的方式去扫描符合规则的方法
        //而是直接使用这个列表提供的方法，至于为什么可以这样做， 看代码分析
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        //当使用反射遍历到的放大不符合规则的时候，且该变量为true，直接抛出EventBusException
        //默认为false
        this.strictMethodVerification = strictMethodVerification;
        //默认为false
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        //如果订阅者存在缓存，则直接从缓存获取
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }

        if (ignoreGeneratedIndex) {
            //使用反射的方式获取订阅方法
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            //SubscriberInfo和反射都用，SubscriberInfo先用
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        //如果找不到订阅方法，则抛出异常
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            //如果找到就将订阅方法缓存起来，供下次使用
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        //如果FindState池中存在FindState对象，则取出，否则new一个
        FindState findState = prepareFindState();
        //设置FindState必要的数据
        findState.initForSubscriber(subscriberClass);
        /*
         * 跳出while循环有3种情况
         * 订阅者为空
         * 订阅者的父类为空
         * 已经遍历到java的核心库或者android的核心库
         * */
        while (findState.clazz != null) {
            //通过SubscriberInfo的方式寻找订阅方法
            findState.subscriberInfo = getSubscriberInfo(findState);
            //当findState.subscriberInfo不为空的时候，表示可能存在订阅方法
            if (findState.subscriberInfo != null) {
                //取出订阅方法数组
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    //对订阅方法校验，从上面的checkAdd的代码可知，并没有检查该方法是否存在Subscribe注解
                    //所以这种方式返回的方法，是不需要该注解的
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                //使用反射的放射寻找订阅方法
                findUsingReflectionInSingleClass(findState);
            }
            //将findState.clazz移向findState.clazz的父类
            findState.moveToSuperclass();
        }
        //返回List<SubscriberMethod>和将FindState对象放入到FindState池中
        return getMethodsAndRelease(findState);
    }

    /**
     * 返回List<SubscriberMethod>和将FindState对象放入到FindState池中
     */
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        //克隆一份List<SubscriberMethod>
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        //初始化FindState里面的数据
        findState.recycle();
        //将FindState对象放入FindState池中
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    /**
     * 如果FindState池中存在FindState对象，则取出，否则new一个
     */
    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    /**
     * 通过SubscriberInfo的方式寻找订阅方法
     */
    private SubscriberInfo getSubscriberInfo(FindState findState) {
        //这一段可以先不看，因为第一次执行的时候一定为null，可以先看下面再回头看这里
        //下面那段代码获取到SubscriberInfo之后，在while循环里面获取到Class对象之后就会第二次执行这里
        //这个时候findState.subscriberInfo就有可能不为空，当不为空，且存在父SubscriberInfo
        //并且父SubscriberInfo的订阅类和clazz一样，就返回
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }

        //Finder的indexes不为空，这个就是构造方法接收的那个List
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                //会传递Class对象获取到对应的SubscriberInfo，当获取到属于该类的SubscriberInfo，就返回
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        //如果FindState池中存在FindState对象，则取出，否则new一个
        FindState findState = prepareFindState();
        //设置FindState必要的数据
        findState.initForSubscriber(subscriberClass);
        /*
         * 跳出while循环有3种情况
         * 订阅者为空
         * 订阅者的父类为空
         * 已经遍历到java的核心库或者android的核心库
         * */
        while (findState.clazz != null) {
            //使用反射的放射寻找订阅方法
            findUsingReflectionInSingleClass(findState);
            //将findState.clazz移向findState.clazz的父类
            findState.moveToSuperclass();
        }
        //返回List<SubscriberMethod>和将FindState对象放入到FindState池中
        return getMethodsAndRelease(findState);
    }

    /**
     * 使用反射的放射寻找订阅方法
     */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        //获取方法列表，当使用getDeclaredMethods获取失败的时候，就使用getMethods获取
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            findState.skipSuperClasses = true;
        }
        //遍历方法列表
        for (Method method : methods) {
            int modifiers = method.getModifiers();
            //如果是public类型，但非abstract、static等
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                //获取方法参数，当只有1个参数的时候，这个方法才有可能符合要求
                if (parameterTypes.length == 1) {
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    //当没有Subscribe，则不是目标方法
                    if (subscribeAnnotation != null) {
                        //下面就是对方法的校验和SubscriberMethod的封装，没什么难度，就不做过多说明了
                        Class<?> eventType = parameterTypes[0];
                        if (findState.checkAdd(method, eventType)) {
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                    //不符合上面的if，并且有Subscribe这个注解，并且strictMethodVerification为true，就抛出异常
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
                //不符合上面的if，并且有Subscribe这个注解，并且strictMethodVerification为true，就抛出异常
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    /**
     * 清除缓存
     */
    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    static class FindState {
        //存储订阅方法，找到订阅方法之后会存储到这边来
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        //下面这3个字段是校验相关的，都在FindState里面调用，下面遇到的时候再讲清楚
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        //订阅者的Class对象
        Class<?> subscriberClass;
        //由于当skipSuperClasses为false的时候，会不断从父类中查到订阅方法
        //所以不能使用subscriberClass字段来找，所以单独用一个字段来存储要找的Class对象
        Class<?> clazz;
        boolean skipSuperClasses;
        //存储订阅方法的信息
        SubscriberInfo subscriberInfo;

        //初始化，先说明一下，之所以初始化的时候不调用recycle方法
        //是因为在find完成之后，会调用recycle方法，并缓存到FindState池里面
        //这个等下分析find方法的时候会提到
        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        //回收操作
        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        //在订阅方法add之前，做一次校验返回true表示可以add。
        //随便找了一段代码，这是参数传递
        //findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            // 2级检查:第一级仅使用事件类型，第二级需要时使用完整签名。
            // 通常，一个订阅者不会监听相同Event的方法

            //final Map<Class, Object> anyMethodByEventType = new HashMap<>();
            Object existing = anyMethodByEventType.put(eventType, method);
            //当为null的时候，表示该Event只有一个方法
            if (existing == null) {
                return true;
            } else {
                //执行到这里表示有两个方法及以上在监听同一个Event

                //这if里面的内容就是校验方法签名，如果叫校验通过，就将当前FindState put进去
                //所以在第三次执行的时候，exitsting就不是Method对象，而是FindState
                //说实话，看不懂为什么要这样写，水平不足
                //只是可以明白一个作用，那就是这样做了之后，就减少一次校验操作
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            //final StringBuilder methodKeyBuilder = new StringBuilder(128);
            //从这里可以看到methodKeyBuilder使用成员变量是起到一个缓存的作用，这样就不用频繁new StringBuilder
            methodKeyBuilder.setLength(0);
            //append之后会将StringBuilder作为Key，所以需要记住
            //这个key包含Method的name和Event的name
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());


            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            //final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            //当该Method上次所在的类是这次所在的类的本身或者是父类，就没问题

            //这样做其实是有原因的，这里回到上面的checkAdd方法，来分析为什么要进入这个方法校验一次
            //在checkAdd里面发现了有两个方法在监听同一个Event，按照EventBus的文档说明，可以看出
            //EventBus的开发人员认为一般情况下不会存在两个方法监听同一个Event
            //但为了防止出现这总情况，只要合法就允许这样做
            //那什么情况下是合法呢
            //1，方法名称不一样：只要方法名称不一样，那StringBuilder拼接出来的key也就不一样，所以methodClassOld肯定为空，返回true
            //2，这个方法是父类的一个方法：只要是父类的方法，那名称一样也没问题
            //不过根据我实际测试，不清楚是EventBus没想到这个问题，还是其他原因
            //我使用addIndex这种方式，添加两个完全一样的方法，在register的subscribe的时候直接抛出异常
            //也就是说这种场景下，也会将这个方法添加到方法列表，并不会过滤掉完全相同的方法
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                // 否则就恢复现场
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        //如果有父类，并且不忽略父类，就将clazz设置为父类
        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                // 这里的注释的意思其实也很明确了，跳过java核心库和android核心库
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
    }

}
