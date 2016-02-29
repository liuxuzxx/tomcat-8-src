package org.lx.tomcat.util;

import java.util.Date;

/**
 * 为了方便我们的打印信息，所以制作了这个工具类
 * 
 * @author 刘旭
 * @date 创建时间：Feb 20, 2016 10:43:34 PM
 * @version 1.0
 */
public class SystemUtil {
	public static void printInfo(Object flagObject, String info) {
		System.out.println(flagObject.getClass().getName() + "-------" + info + "--------" + new Date());
	}
}
