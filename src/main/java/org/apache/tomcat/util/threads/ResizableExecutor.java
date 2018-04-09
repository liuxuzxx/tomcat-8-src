
package org.apache.tomcat.util.threads;

import java.util.concurrent.Executor;

/**
 * 正所谓：艺高人胆大。tomcat都是自己给自己制作轮子，这轮子一造还不小。线程池，自己都敢造
 */
public interface ResizableExecutor extends Executor {

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    int getPoolSize();

    int getMaxThreads();

    /**
     * Returns the approximate number of threads that are actively executing
     * tasks.
     *
     * @return the number of threads
     */
    int getActiveCount();

    boolean resizePool(int corePoolSize, int maximumPoolSize);

    boolean resizeQueue(int capacity);

}
