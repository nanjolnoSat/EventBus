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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

public class HandlerPoster extends Handler implements Poster {

    //将要执行的任务加入并取出的队列
    private final PendingPostQueue queue;
    //方法最大的执行事件，在EventBus传递进来的是10毫秒
    //当一个方法执行的时间超过10毫秒，就会sendMessage。说实话，看不懂这样的作用
    private final int maxMillisInsideHandleMessage;
    //最终通过该对象调用EventBus的invokeSubscriber方法
    private final EventBus eventBus;
    //是否处于活跃，对于该对象来说，就是用来判断当前Handler是否在运行handleMessage
    private boolean handlerActive;

    protected HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        //从PendingPost池里面取出一个PendingPost
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            //将消息加入到消息队列
            queue.enqueue(pendingPost);
            //没有初始值，所以默认是false，当false的时候，就sendMessage
            if (!handlerActive) {
                handlerActive = true;
                if (!sendMessage(obtainMessage())) {
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
            long started = SystemClock.uptimeMillis();
            //进来之后就while循环
            while (true) {
                //不断地从PendingPost取出消息执行，从而减少sendMessage和handleMessage的调用次数
                //当取不到消息的时候，就结束运行，并将handlerActive置为false
                //这样的话上面的enqueue方法就会再次sendMessage
                PendingPost pendingPost = queue.poll();
                if (pendingPost == null) {
                    synchronized (this) {
                        // Check again, this time in synchronized
                        pendingPost = queue.poll();
                        if (pendingPost == null) {
                            handlerActive = false;
                            return;
                        }
                    }
                }
                eventBus.invokeSubscriber(pendingPost);
                long timeInMethod = SystemClock.uptimeMillis() - started;
                //当两次的时间大于或等于maxMillisInsideHandleMessage的时候，就sendMessage
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    //由于这个时候sendMessage，所以必须为true
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            handlerActive = rescheduled;
        }
    }
}