package org.lx.tomcat.listener;

import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * @author 刘旭
 * @description: 服务器状态的监听器
 * @Date 2019-05-18 15:51:10
 */
public class ServerStatusListener implements LifecycleListener {
    private static final Log log = LogFactory.getLog(ServerStatusListener.class);

    public ServerStatusListener(){
        log.info("ServerStatusListener启动初始化了");
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        log.info("生命周期的探测:"+event.getType());
    }
}
