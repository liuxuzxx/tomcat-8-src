package org.apache.catalina.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;

/**
 * 感觉这个类的作用有点和Lifecycle的方法重叠，其实可以删除掉.当然后期如果出现比较复杂的
 * 还是可以使用一个资源管理类
 * 原来是这么个关系:Lifecycle具有LifecycleListener这个监听器，但是这个监听器监听的是LifecycleEvent事件
 */
public final class LifecycleSupport {
    private final Lifecycle lifecycle;

    private final List<LifecycleListener> listeners = new CopyOnWriteArrayList<>();

    public LifecycleSupport(Lifecycle lifecycle) {
        super();
        this.lifecycle = lifecycle;
    }


    public void addLifecycleListener(LifecycleListener listener) {
        listeners.add(listener);
    }

    public LifecycleListener[] findLifecycleListeners() {
        return listeners.toArray(new LifecycleListener[0]);
    }


    public void fireLifecycleEvent(String type, Object data) {
        LifecycleEvent event = new LifecycleEvent(lifecycle, type, data);
        for (LifecycleListener listener : listeners) {
            listener.lifecycleEvent(event);
        }
    }

    public void removeLifecycleListener(LifecycleListener listener) {
        listeners.remove(listener);
    }
}
