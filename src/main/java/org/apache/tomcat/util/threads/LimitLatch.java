package org.apache.tomcat.util.threads;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Shared latch that allows the latch to be acquired a limited number of times
 * after which all subsequent requests to acquire the latch will be placed in a
 * FIFO queue until one of the shares is returned.
 * tomcat果然是人才辈出，当时写的时候，估计是JDK没有提供现成的Latch，也就是门栓，结果自己给
 * 自己整了一个LimitLatch的限制门栓，其实和CountDownLatch的作用差不多，看代码的形式
 * 这个类的作用，我通过看源代码分析了一下，大致的功能如下：
 * 我就是对你的需要的数量进行一个限购。说白了，我就是提供一个数字上的限制。
 * 比如说，分配了800套房子，那么，我就绝对保证不会出现801个线程来抢这个房子
 * 如果出现了第801个人，那么请您等着，一直等到有线程出来，你才能进去。
 */
public class LimitLatch {
    private static final Log log = LogFactory.getLog(LimitLatch.class);
    private final Sync sync;
    private final AtomicLong count;
    private volatile long limit;
    private volatile boolean released = false;

    private class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1L;

        public Sync() {
        }

        @Override
        protected int tryAcquireShared(int ignored) {
            long newCount = count.incrementAndGet();
            if (!released && newCount > limit) {
                count.decrementAndGet();
                return -1;
            } else {
                return 1;
            }
        }

        @Override
        protected boolean tryReleaseShared(int arg) {
            count.decrementAndGet();
            return true;
        }
    }

    public LimitLatch(long limit) {
        this.limit = limit;
        this.count = new AtomicLong(0);
        this.sync = new Sync();
    }

    public long getCount() {
        return count.get();
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public void countUpOrAwait() throws InterruptedException {
        log.debug("Counting up[" + Thread.currentThread().getName() + "] latch=" + getCount());
        sync.acquireSharedInterruptibly(1);
    }

    public long countDown() {
        sync.releaseShared(0);
        long result = getCount();
        if (log.isDebugEnabled()) {
            log.debug("Counting down[" + Thread.currentThread().getName() + "] latch=" + result);
        }
        return result;
    }

    public boolean releaseAll() {
        released = true;
        return sync.releaseShared(0);
    }

    public void reset() {
        this.count.set(0);
        released = false;
    }

    public boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }
}
