package org.lx.tomcat.util;

import java.util.Date;

/**
 * 为了方便我们的打印信息，所以制作了这个工具类
 *
 * @author 刘旭
 * @version 1.0
 * @date 创建时间：Feb 20, 2016 10:43:34 PM
 */
public class SystemUtil {
    public static void printInfo(Object flagObject, String info) {
        System.out.println(flagObject.getClass().getName() + "-------" + info + "--------" + new Date());
    }

    /**
     * 打印一些日志基本信息
     *
     * @param target   目标对象
     * @param logInfos 日志信息集合
     */
    public static void logInfo(Object target, String... logInfos) {
        StringBuilder logs = new StringBuilder();
        logs.append("[LOG INFO] ");
        logs.append(DateUtils.formatCurrentDate());
        logs.append(" :");
        logs.append(target.getClass());
        logs.append("  ");
        for (String temp : logInfos) {
            logs.append(temp);
            logs.append("  ");
        }
        System.out.println(logs.toString());
    }
}
