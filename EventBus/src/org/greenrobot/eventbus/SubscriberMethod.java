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

import java.lang.reflect.Method;

/** Used internally by EventBus and generated subscriber indexes. */
public class SubscriberMethod {
    //调用的方法
    final Method method;
    //线程模式，留到post的时候再说
    final ThreadMode threadMode;
    //该方法的Event类型，即参数类型
    final Class<?> eventType;
    //优先级，默认为0
    final int priority;
    //百度翻译：粘性的。默认为false。
    final boolean sticky;
    /** Used for efficient comparison */
    //用于比较，下面的equals方法用到
    String methodString;

    public SubscriberMethod(Method method, Class<?> eventType, ThreadMode threadMode, int priority, boolean sticky) {
        this.method = method;
        this.threadMode = threadMode;
        this.eventType = eventType;
        this.priority = priority;
        this.sticky = sticky;
    }

    //重要，当比较两个方法是否相同的时候会用到。
    @Override
    public boolean equals(Object other) {
        //当两个SubscriberMethod对象是同一个的时候，返回true
        if (other == this) {
            return true;
        } else if (other instanceof SubscriberMethod) {
            //将方法的调用类的全限定名、方法名称、Event类型存储到methodString里面，然后再比较
            //两个对象的methodString是否一致，如果一致，表示两个方法完全一样
            checkMethodString();
            SubscriberMethod otherSubscriberMethod = (SubscriberMethod)other;
            //这里也会调用一次check，防止methodString为空
            otherSubscriberMethod.checkMethodString();
            // Don't use method.equals because of http://code.google.com/p/android/issues/detail?id=7811#c6
            return methodString.equals(otherSubscriberMethod.methodString);
        } else {
            return false;
        }
    }

    //当两个对象不相同的时候，就通过：调用者的全限定名、方法名称、参数的权限定名。判断两个对象是否一致。
    private synchronized void checkMethodString() {
        if (methodString == null) {
            // Method.toString has more overhead, just take relevant parts of the method
            StringBuilder builder = new StringBuilder(64);
            builder.append(method.getDeclaringClass().getName());
            builder.append('#').append(method.getName());
            builder.append('(').append(eventType.getName());
            methodString = builder.toString();
        }
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }
}