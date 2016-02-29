/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.res;

import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * An internationalization / localization helper class which reduces
 * the bother of handling ResourceBundles and takes care of the
 * common cases of message formating which otherwise require the
 * creation of Object arrays and such.
 *
 * <p>The StringManager operates on a package basis. One StringManager
 * per package can be created and accessed via the getManager method
 * call.
 *
 * <p>The StringManager will look for a ResourceBundle named by
 * the package name given plus the suffix of "LocalStrings". In
 * practice, this means that the localized information will be contained
 * in a LocalStrings.properties file located in the package
 * directory of the class path.
 *
 * <p>Please see the documentation for java.util.ResourceBundle for
 * more information.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Mel Martinez [mmartinez@g1440.com]
 * @see java.util.ResourceBundle
 */
/**
 * 这个类从字面意思上看是字符串的管理，但是实际上是这个配置文件中的一个信息的管理，这个和我做tp系统的时候 出现的一个一些常量的管理有关系，
 * 我们探索一下这个常量的出现问题 首先是：我们把这个常量直接写死在这个使用的地方，就是即时字符串的意思，这样子，如果重复不是很大，没有问题
 * 如果重复很大，那么以后修改的时候会很麻烦，尤其是不在同一个类中的时候
 * 其次是：我们可以提取出来放到一个专门放置常量的类中，或者是一个放置常量的接口中，那么问题就很好解决了
 * 最后是：你放置在java文件中，那么你只要修改，就必须重新的编译这个文件
 * 最牛逼的做法：放置到一个配置文件中，随时的读取，随时的更改，没有任何问题，但是，你还是要手动的去动这个文件
 * 最最好的办法，放置在数据库中，这样子通过界面修改就行了真正的做到了配置，灵活
 * 
 * @author liuxu
 *
 */
public class StringManager {

	private static int LOCALE_CACHE_SIZE = 10;

	/**
	 * The ResourceBundle for this StringManager.
	 */
	private final ResourceBundle bundle;
	private final Locale locale;

	/**
	 * Creates a new StringManager for a given package. This is a private method
	 * and all access to it is arbitrated by the static getManager method call
	 * so that only one StringManager per package will be created.
	 *
	 * @param packageName
	 *            Name of package to create StringManager for.
	 */
	/**
	 * 虽然我不想这个时候就学习这个单利设计模式，但是这个设计模式就在我的眼前，我不恩能够不进行学习了
	 * 这个设置这个构造方法是private的，那么就是单例的意思了
	 * 从这个使用的角度来看，其实java的文件是分目录的，这种思想是很好的，分在这个目录，说明，这个是有关系的和联系的
	 * 
	 * @param packageName
	 *            配置常量文件放置的包名
	 * @param locale
	 */
	private StringManager(String packageName, Locale locale) {
		String bundleName = packageName + ".LocalStrings";
		ResourceBundle bnd = null;
		try {
			/**
			 * 我一直都在想这个java的异常信息是怎么进行一个处理和操作的 其实这个地方就是一个例子
			 */
			/**
			 * 我不明白的是，这句话没有异常跑出来，你待着这个异常干什么啊
			 * 这个是一个runtime异常，所以，要抓住，不然，下面的操作是没有办法进行操作的了
			 */
			bnd = ResourceBundle.getBundle(bundleName, locale);
		} catch (MissingResourceException ex) {
			// Try from the current loader (that's the case for trusted apps)
			// Should only be required if using a TC5 style classloader
			// structure
			// where common != shared != server
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			/**
			 * 曾经的我竟然嘲笑别人总是测试一个对象是否时null的，我认为，这种测试的null会扩大到一种
			 * 病态的情况，甚至说，你只要是使用这个对象，那么就需要测试这个对象是否是null的 但是，从一个角度来看，这个目的市委了代码的健壮性
			 * 如果说，一个对象是你new出来的，那么你就没有必要进行一个测试了，但是，如果是通过
			 * 间接的方式出来的，那么就必须进行一个null测试了
			 */
			if (cl != null) {
				try {
					bnd = ResourceBundle.getBundle(bundleName, locale, cl);
				} catch (MissingResourceException ex2) {
					// Ignore
					/**
					 * ignore是忽视和忽略的意思，就是说不问了，因为就像是我们的关闭io的异常一样子
					 * 他也会继续跑出来异常，但是这个时候，你要去抓住异常，那么将会是无限的异常抓住了
					 * 就是一个死循环的tru-catch异常信息了
					 */
				}
			}
		}
		bundle = bnd;
		// Get the actual locale, which may be different from the requested one
		/**
		 * 看看这个代码的健壮性多好，人家只要是使用这个对象，这个对象不是new出来的，直接就是测试是否时null
		 * 我们其实写代码的时候也是需要测试这个null的，毕竟，尤其是是从外界获取资源的时候 这么牛逼的作者，写出来的代码也是if-else满天飞啊
		 * 其实是从这个代码的结构能看出作者想的很缜密，不会有什么漏洞，这个据对是对底层和自己编码的经验的出来的， 不是一朝一夕的功力
		 */
		if (bundle != null) {
			Locale bundleLocale = bundle.getLocale();
			if (bundleLocale.equals(Locale.ROOT)) {
				this.locale = Locale.ENGLISH;
			} else {
				this.locale = bundleLocale;
			}
		} else {
			this.locale = null;
		}
	}

	/**
	 * Get a string from the underlying resource bundle or return null if the
	 * String is not found.
	 *
	 * @param key
	 *            to desired resource String
	 *
	 * @return resource String matching <i>key</i> from underlying bundle or
	 *         null if not found.
	 *
	 * @throws IllegalArgumentException
	 *             if <i>key</i> is null
	 */
	public String getString(String key) {
		if (key == null) {
			String msg = "key may not have a null value";
			/**
			 * 这个地方也是跑出来一个runtime的异常 我们需要说明几点：
			 * 一、runtime异常是不需要在这个方法上声明的，是可以直接跑出去的,就是说runtime的异常是归
			 * jvm处理的，不是程序员管的事情，那么非runtime异常就是程序员自己处理的了
			 * 二、其实不就是一个null吗，为什么这么狠心跑出去一个runtime异常信息，真是让人觉得狠心啊
			 */
			throw new IllegalArgumentException(msg);
		}

		String str = null;

		try {
			// Avoid NPE if bundle is null and treat it like an MRE
			/**
			 * 这个作者也是太担心了吧，使用就要检测这个是否时null的，真是让人崩溃啊
			 */
			if (bundle != null) {
				str = bundle.getString(key);
			}
		} catch (MissingResourceException mre) {
			// bad: shouldn't mask an exception the following way:
			// str = "[cannot find message associated with key '" + key +
			// "' due to " + mre + "]";
			// because it hides the fact that the String was missing
			// from the calling code.
			// good: could just throw the exception (or wrap it in another)
			// but that would probably cause much havoc on existing
			// code.
			// better: consistent with container pattern to
			// simply return null. Calling code can then do
			// a null check.
			str = null;
			/**
			 * 从来不会无意义的打印这个异常信息:mre.printStackTrace();这种做法只有提示，没有任何作用
			 * 但是一个问题产生了，如果是null，那么使用方法的人，得到的是否也是需要检验的，我觉得返回一个""最好了
			 */
		}

		return str;
	}

	/**
	 * Get a string from the underlying resource bundle and format it with the
	 * given set of arguments.
	 *
	 * @param key
	 *            The key for the required message
	 * @param args
	 *            The values to insert into the message
	 *
	 * @return The request string formatted with the provided arguments or the
	 *         key if the key was not found.
	 */
	public String getString(final String key, final Object... args) {
		String value = getString(key);
		if (value == null) {
			value = key;
		}

		MessageFormat mf = new MessageFormat(value);
		mf.setLocale(locale);
		return mf.format(args, new StringBuffer(), null).toString();
	}

	/**
	 * Identify the Locale this StringManager is associated with.
	 *
	 * @return The Locale associated with the StringManager
	 */
	public Locale getLocale() {
		return locale;
	}

	// --------------------------------------------------------------
	// STATIC SUPPORT METHODS
	// --------------------------------------------------------------

	private static final Map<String, Map<Locale, StringManager>> managers = new Hashtable<>();

	/**
	 * Get the StringManager for a given class. The StringManager will be
	 * returned for the package in which the class is located. If a manager for
	 * that package already exists, it will be reused, else a new StringManager
	 * will be created and returned.
	 *
	 * @param clazz
	 *            The class for which to retrieve the StringManager
	 *
	 * @return The instance associated with the package of the provide class
	 */
	/**
	 * 这个能获取任何一个class下面的包的常量配置文件信息
	 * 
	 * @param clazz
	 * @return
	 */
	public static final StringManager getManager(Class<?> clazz) {
		return getManager(clazz.getPackage().getName());
	}

	/**
	 * Get the StringManager for a particular package. If a manager for a
	 * package already exists, it will be reused, else a new StringManager will
	 * be created and returned.
	 *
	 * @param packageName
	 *            The package name
	 *
	 * @return The instance associated with the given package and the default
	 *         Locale
	 */
	public static final StringManager getManager(String packageName) {
		return getManager(packageName, Locale.getDefault());
	}

	/**
	 * Get the StringManager for a particular package and Locale. If a manager
	 * for a package/Locale combination already exists, it will be reused, else
	 * a new StringManager will be created and returned.
	 *
	 * @param packageName
	 *            The package name
	 * @param locale
	 *            The Locale
	 *
	 * @return The instance associated with the given package and Locale
	 */
	public static final synchronized StringManager getManager(String packageName, Locale locale) {

		Map<Locale, StringManager> map = managers.get(packageName);
		if (map == null) {
			/*
			 * Don't want the HashMap to be expanded beyond LOCALE_CACHE_SIZE.
			 * Expansion occurs when size() exceeds capacity. Therefore keep
			 * size at or below capacity. removeEldestEntry() executes after
			 * insertion therefore the test for removal needs to use one less
			 * than the maximum desired size
			 *
			 */
			/**
			 * 自己组建一个map，就是用自己的方式进行c++方式的编程
			 */
			map = new LinkedHashMap<Locale, StringManager>(LOCALE_CACHE_SIZE, 1, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<Locale, StringManager> eldest) {
					if (size() > (LOCALE_CACHE_SIZE - 1)) {
						return true;
					}
					return false;
				}
			};
			managers.put(packageName, map);
		}

		StringManager mgr = map.get(locale);
		if (mgr == null) {
			mgr = new StringManager(packageName, locale);
			map.put(locale, mgr);
		}
		return mgr;
	}

	/**
	 * Retrieve the StringManager for a list of Locales. The first StringManager
	 * found will be returned.
	 *
	 * @param packageName
	 *            The package for which the StringManager was requested
	 * @param requestedLocales
	 *            The list of Locales
	 *
	 * @return the found StringManager or the default StringManager
	 */
	public static StringManager getManager(String packageName, Enumeration<Locale> requestedLocales) {
		while (requestedLocales.hasMoreElements()) {
			Locale locale = requestedLocales.nextElement();
			StringManager result = getManager(packageName, locale);
			if (result.getLocale().equals(locale)) {
				return result;
			}
		}
		// Return the default
		return getManager(packageName);
	}
}
