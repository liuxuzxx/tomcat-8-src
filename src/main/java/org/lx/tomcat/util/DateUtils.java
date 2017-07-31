package org.lx.tomcat.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by liuxu on 27/03/17.
 * 日期类型的操作工作类
 */
public class DateUtils {

    private static final String DATE_FORMAT_STR = "yyyy-MM-dd hh:mm:ss";
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat(DATE_FORMAT_STR);

    /**
     * 格式化日期类型为指定的字符串形式
     *
     * @param date 需要格式化的日期
     * @return 格式化之后的日期字符串
     */
    public static String formatDate(Date date) {
        return DATE_FORMAT.format(date);
    }

    /**
     * 格式化当前的日期为指定的字符串形式
     *
     * @return 格式化的日期字符串
     */
    public static String formatCurrentDate() {
        return formatDate(new Date());
    }
}
