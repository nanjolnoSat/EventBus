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
package org.greenrobot.eventbus.meta;

import org.greenrobot.eventbus.SubscriberMethod;

/**
 * Base class for generated index classes created by annotation processing.
 */
public interface SubscriberInfo {
    //如果getSuperSubscriberInfo才有用，在SubscriberMethodFinder里面会判断订阅者的父class
    //和SubscriberInfo.getSuperSubscriberInfo().getSubscriberClass()是否为同一个

    //不过，如果担心出问题，那就返回getSubscriberMethods()使用的Class同一个对象那就绝对没问题
    //但就我个人而言，如果getSuperSubscriberInfo()返回空，那该方法我也会直接返回空
    Class<?> getSubscriberClass();

    //获取订阅方法
    SubscriberMethod[] getSubscriberMethods();

    //父的SubscriberInfo
    SubscriberInfo getSuperSubscriberInfo();

    boolean shouldCheckSuperclass();
}
