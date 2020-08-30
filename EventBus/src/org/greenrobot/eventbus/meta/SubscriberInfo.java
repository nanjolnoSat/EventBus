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
    //如果是其他SubscriberInfo的父，即存在某个SubscriberInfo的getSuperSubscriberInfo()返回该SubscriberInfo对象
    //这种情况下该方法返回的对象才能起到作用，这种情况下必须和getSubscriberMethods()里面Class.getMethod的Class
    //是同一个对象，如果不清楚会不会被其他SubscriberInfo.getSuperSubscriberInfo()引用，那就别返回空吧
    //如果可以确定不被引用，那就可以偷懒，返回null
    Class<?> getSubscriberClass();

    //获取订阅方法
    SubscriberMethod[] getSubscriberMethods();

    //父的SubscriberInfo
    SubscriberInfo getSuperSubscriberInfo();

    boolean shouldCheckSuperclass();
}
