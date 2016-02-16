/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
/**
 * 我们就从这个最基本的风格看起来，这个程序就没有一个警告，这个是牛逼的程序牛逼的地方，人家一个警告都不给你出现
 * 这样子，我们的程序的健壮性从一个没有warning就能体现出来了，但是虽然说没用warning不能说明多么大的问题，我们
 * 还是说不要让我们的程序出现一个warning，就像是误差一样子，我们尽可能的减少吧！
 * 
 * @author liuxu
 *
 */
public final class Bootstrap {

	private static final Log log = LogFactory.getLog(Bootstrap.class);

	/**
	 * Daemon object used by main.
	 */
	private static Bootstrap daemon = null;

	private static final File catalinaBaseFile;
	private static final File catalinaHomeFile;

	private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");

	static {
		// Will always be non-null
		/**
		 * 这个获取的是这个classpath路径，就是本java工程的classpath路径
		 */
		String userDir = System.getProperty("user.dir");

		// Home first
		String home = System.getProperty(Globals.CATALINA_HOME_PROP);
		File homeFile = null;

		if (home != null) {
			File f = new File(home);
			try {
				homeFile = f.getCanonicalFile();
			} catch (IOException ioe) {
				homeFile = f.getAbsoluteFile();
			}
		}

		if (homeFile == null) {
			// First fall-back. See if current directory is a bin directory
			// in a normal Tomcat install
			/**
			 * 这个地方应该是这个逻辑：首先是这个当前的目录下面是否有这个已经编译好的jar。就是
			 * 已经编译好的tomcat的包，如果有，直接的进行一个调用使用
			 */
			File bootstrapJar = new File(userDir, "bootstrap.jar");

			/**
			 * 这个其实是File会经常使用的问题，就是，我们写File关系东西的时候，总是会抓住很多的异常
			 * 其中异常顺序就是：文件是否存在，操作文件异常（包括读取异常和写入异常）
			 * 那么问题出现了，我们怎么处理这个异常信息啊，我的一般做法就是：抓住这个异常，然后处理这个异常
			 * 但是，如果是在底层，你处理这个异常了，那么就是说相当于你是吞掉了这个异常信息，那么上层的环境 是不恩能够获取这个异常信息的
			 * 这个异常处理涉及到问题：如果真的出现这个异常，是不是说系统就跑步起来了，还是说，如果出现异常，我们
			 * 还是可以有补救方案的，比如说，我们的很多框架都会默认的加载一些配置文件，但是如果这个配置文件不存在
			 * 我们一定要退出系统了吗，或者是跑出去这个异常信息，这个是错误的做法，我们应该还要流一个后手，就是，
			 * 我们一定要允许用户配置自己的配置文件路径，如果不存在默认的，我们就加载用户提供的配置文件，如果都不存在
			 * 那么对不起，系统没有这个文件是不恩能够启动的，之后跑出去异常，然后是终止这个程序的启动
			 * 这个感觉就是：这个tomcat是一定要获取这个file对象的，就是一直的补救这个file对象
			 */
			if (bootstrapJar.exists()) {
				File f = new File(userDir, "..");
				try {
					/**
					 * 忍不住想在这个地方添加一个说明信息
					 * 就是这个getAbsolutePath和getCanonicalPath的区别就是：
					 * 上一个得到是你的绝对地址，但是这个绝对地址太绝对了，如果你在
					 * 进行一个File对象的构造的时候，如果你传入了../的上一个文件夹东西，
					 * 那么这个绝对地址真的是绝对的给你返回这个信息 但是这个下面的权威的地址就会给你一个解析好的地址，真的是权威的地址啊
					 */
					homeFile = f.getCanonicalFile();
				} catch (IOException ioe) {
					homeFile = f.getAbsoluteFile();
				}
			}
		}

		if (homeFile == null) {
			// Second fall-back. Use current directory
			File f = new File(userDir);
			try {
				homeFile = f.getCanonicalFile();
			} catch (IOException ioe) {
				homeFile = f.getAbsoluteFile();
			}
		}

		catalinaHomeFile = homeFile;
		System.setProperty(Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

		// Then base
		String base = System.getProperty(Globals.CATALINA_BASE_PROP);
		if (base == null) {
			catalinaBaseFile = catalinaHomeFile;
		} else {
			File baseFile = new File(base);
			try {
				baseFile = baseFile.getCanonicalFile();
			} catch (IOException ioe) {
				baseFile = baseFile.getAbsoluteFile();
			}
			catalinaBaseFile = baseFile;
		}
		System.setProperty(Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
	}

	// -------------------------------------------------------------- Variables

	/**
	 * Daemon reference.
	 */
	private Object catalinaDaemon = null;

	ClassLoader commonLoader = null;
	ClassLoader catalinaLoader = null;
	ClassLoader sharedLoader = null;

	// -------------------------------------------------------- Private Methods

	private void initClassLoaders() {
		try {
			commonLoader = createClassLoader("common", null);
			if (commonLoader == null) {
				// no config file, default to this loader - we might be in a
				// 'single' env.
				commonLoader = this.getClass().getClassLoader();
			}
			catalinaLoader = createClassLoader("server", commonLoader);
			sharedLoader = createClassLoader("shared", commonLoader);
		} catch (Throwable t) {
			handleThrowable(t);
			log.error("Class loader creation threw exception", t);
			System.exit(1);
		}
	}

	private ClassLoader createClassLoader(String name, ClassLoader parent) throws Exception {

		String value = CatalinaProperties.getProperty(name + ".loader");
		if ((value == null) || (value.equals("")))
			return parent;

		value = replace(value);

		List<Repository> repositories = new ArrayList<>();

		String[] repositoryPaths = getPaths(value);

		for (String repository : repositoryPaths) {
			// Check for a JAR URL repository
			try {
				@SuppressWarnings("unused")
				URL url = new URL(repository);
				repositories.add(new Repository(repository, RepositoryType.URL));
				continue;
			} catch (MalformedURLException e) {
				// Ignore
			}

			// Local repository
			if (repository.endsWith("*.jar")) {
				repository = repository.substring(0, repository.length() - "*.jar".length());
				repositories.add(new Repository(repository, RepositoryType.GLOB));
			} else if (repository.endsWith(".jar")) {
				repositories.add(new Repository(repository, RepositoryType.JAR));
			} else {
				repositories.add(new Repository(repository, RepositoryType.DIR));
			}
		}

		return ClassLoaderFactory.createClassLoader(repositories, parent);
	}

	/**
	 * System property replacement in the given string.
	 *
	 * @param str
	 *            The original string
	 * @return the modified string
	 */
	protected String replace(String str) {
		// Implementation is copied from ClassLoaderLogManager.replace(),
		// but added special processing for catalina.home and catalina.base.
		String result = str;
		int pos_start = str.indexOf("${");
		if (pos_start >= 0) {
			StringBuilder builder = new StringBuilder();
			int pos_end = -1;
			while (pos_start >= 0) {
				builder.append(str, pos_end + 1, pos_start);
				pos_end = str.indexOf('}', pos_start + 2);
				if (pos_end < 0) {
					pos_end = pos_start - 1;
					break;
				}
				String propName = str.substring(pos_start + 2, pos_end);
				String replacement;
				if (propName.length() == 0) {
					replacement = null;
				} else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
					replacement = getCatalinaHome();
				} else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
					replacement = getCatalinaBase();
				} else {
					replacement = System.getProperty(propName);
				}
				if (replacement != null) {
					builder.append(replacement);
				} else {
					builder.append(str, pos_start, pos_end + 1);
				}
				pos_start = str.indexOf("${", pos_end + 1);
			}
			builder.append(str, pos_end + 1, str.length());
			result = builder.toString();
		}
		return result;
	}

	/**
	 * Initialize daemon.
	 */
	public void init() throws Exception {

		initClassLoaders();

		Thread.currentThread().setContextClassLoader(catalinaLoader);

		SecurityClassLoad.securityClassLoad(catalinaLoader);

		// Load our startup class and call its process() method
		if (log.isDebugEnabled())
			log.debug("Loading startup class");
		Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
		Object startupInstance = startupClass.newInstance();

		// Set the shared extensions class loader
		if (log.isDebugEnabled())
			log.debug("Setting startup class properties");
		String methodName = "setParentClassLoader";
		Class<?> paramTypes[] = new Class[1];
		paramTypes[0] = Class.forName("java.lang.ClassLoader");
		Object paramValues[] = new Object[1];
		paramValues[0] = sharedLoader;
		Method method = startupInstance.getClass().getMethod(methodName, paramTypes);
		method.invoke(startupInstance, paramValues);

		catalinaDaemon = startupInstance;

	}

	/**
	 * Load daemon. daemon:在英语中的意思是：“守护神，半人半兽的精灵，”但是在计算机中，这个是守护进程的意思
	 * 就是说，这个进程是一个持续运行的进程，估计这个java应该是能让一个进程成为一个守护进程的
	 * 但是这个词语让我想起来了：demo，这个英语单词的意思应该是例子的意思，因为是，大家都喜欢说，给我
	 * 一个demo，不过，我还是需要上网查询这个单词的意思：演示，样例
	 */
	private void load(String[] arguments) throws Exception {

		// Call the load() method
		String methodName = "load";
		Object param[];
		Class<?> paramTypes[];
		if (arguments == null || arguments.length == 0) {
			paramTypes = null;
			param = null;
		} else {
			paramTypes = new Class[1];
			paramTypes[0] = arguments.getClass();
			param = new Object[1];
			param[0] = arguments;
		}
		Method method = catalinaDaemon.getClass().getMethod(methodName, paramTypes);
		if (log.isDebugEnabled())
			log.debug("Calling startup class " + method);
		method.invoke(catalinaDaemon, param);

	}

	/**
	 * getServer() for configtest
	 */
	private Object getServer() throws Exception {

		String methodName = "getServer";
		Method method = catalinaDaemon.getClass().getMethod(methodName);
		return method.invoke(catalinaDaemon);

	}

	// ----------------------------------------------------------- Main Program

	/**
	 * Load the Catalina daemon.
	 */
	public void init(String[] arguments) throws Exception {

		init();
		load(arguments);

	}

	/**
	 * Start the Catalina daemon.
	 */
	public void start() throws Exception {
		if (catalinaDaemon == null)
			init();

		Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the Catalina Daemon.
	 */
	public void stop() throws Exception {

		Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the standalone server.
	 */
	public void stopServer() throws Exception {

		Method method = catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
		method.invoke(catalinaDaemon, (Object[]) null);

	}

	/**
	 * Stop the standalone server.
	 */
	public void stopServer(String[] arguments) throws Exception {

		Object param[];
		Class<?> paramTypes[];
		if (arguments == null || arguments.length == 0) {
			paramTypes = null;
			param = null;
		} else {
			paramTypes = new Class[1];
			paramTypes[0] = arguments.getClass();
			param = new Object[1];
			param[0] = arguments;
		}
		Method method = catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
		method.invoke(catalinaDaemon, param);

	}

	/**
	 * Set flag.
	 */
	public void setAwait(boolean await) throws Exception {

		Class<?> paramTypes[] = new Class[1];
		paramTypes[0] = Boolean.TYPE;
		Object paramValues[] = new Object[1];
		paramValues[0] = Boolean.valueOf(await);
		Method method = catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
		method.invoke(catalinaDaemon, paramValues);

	}

	public boolean getAwait() throws Exception {
		Class<?> paramTypes[] = new Class[0];
		Object paramValues[] = new Object[0];
		Method method = catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
		Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
		return b.booleanValue();
	}

	/**
	 * Destroy the Catalina Daemon.
	 */
	public void destroy() {

		// FIXME

	}

	/**
	 * Main method and entry point when starting Tomcat via the provided
	 * scripts.
	 *
	 * @param args
	 *            Command line arguments to be processed
	 */
	public static void main(String args[]) {
		System.out.println("tomcat开始启动了，就是从这个");
		if (daemon == null) {
			// Don't set daemon until init() has completed
			Bootstrap bootstrap = new Bootstrap();
			try {
				bootstrap.init();
			} catch (Throwable t) {
				handleThrowable(t);
				t.printStackTrace();
				return;
			}
			daemon = bootstrap;
		} else {
			// When running as a service the call to stop will be on a new
			// thread so make sure the correct class loader is used to prevent
			// a range of class not found exceptions.
			Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
		}

		try {
			String command = "start";
			if (args.length > 0) {
				command = args[args.length - 1];
			}

			if (command.equals("startd")) {
				args[args.length - 1] = "start";
				daemon.load(args);
				daemon.start();
			} else if (command.equals("stopd")) {
				args[args.length - 1] = "stop";
				daemon.stop();
			} else if (command.equals("start")) {
				daemon.setAwait(true);
				daemon.load(args);
				daemon.start();
			} else if (command.equals("stop")) {
				daemon.stopServer(args);
			} else if (command.equals("configtest")) {
				daemon.load(args);
				if (null == daemon.getServer()) {
					System.exit(1);
				}
				System.exit(0);
			} else {
				log.warn("Bootstrap: command \"" + command + "\" does not exist.");
			}
		} catch (Throwable t) {
			// Unwrap the Exception for clearer error reporting
			if (t instanceof InvocationTargetException && t.getCause() != null) {
				t = t.getCause();
			}
			handleThrowable(t);
			t.printStackTrace();
			System.exit(1);
		}

	}

	/**
	 * Obtain the name of configured home (binary) directory. Note that home and
	 * base may be the same (and are by default).
	 */
	public static String getCatalinaHome() {
		return catalinaHomeFile.getPath();
	}

	/**
	 * Obtain the name of the configured base (instance) directory. Note that
	 * home and base may be the same (and are by default). If this is not set
	 * the value returned by {@link #getCatalinaHome()} will be used.
	 */
	public static String getCatalinaBase() {
		return catalinaBaseFile.getPath();
	}

	/**
	 * Obtain the configured home (binary) directory. Note that home and base
	 * may be the same (and are by default).
	 */
	public static File getCatalinaHomeFile() {
		return catalinaHomeFile;
	}

	/**
	 * Obtain the configured base (instance) directory. Note that home and base
	 * may be the same (and are by default). If this is not set the value
	 * returned by {@link #getCatalinaHomeFile()} will be used.
	 */
	public static File getCatalinaBaseFile() {
		return catalinaBaseFile;
	}

	// Copied from ExceptionUtils since that class is not visible during start
	private static void handleThrowable(Throwable t) {
		if (t instanceof ThreadDeath) {
			throw (ThreadDeath) t;
		}
		if (t instanceof VirtualMachineError) {
			throw (VirtualMachineError) t;
		}
		// All other instances of Throwable will be silently swallowed
	}

	// Protected for unit testing
	protected static String[] getPaths(String value) {

		List<String> result = new ArrayList<>();
		Matcher matcher = PATH_PATTERN.matcher(value);

		while (matcher.find()) {
			String path = value.substring(matcher.start(), matcher.end());

			path = path.trim();
			if (path.length() == 0) {
				continue;
			}

			char first = path.charAt(0);
			char last = path.charAt(path.length() - 1);

			if (first == '"' && last == '"' && path.length() > 1) {
				path = path.substring(1, path.length() - 1);
				path = path.trim();
				if (path.length() == 0) {
					continue;
				}
			} else if (path.contains("\"")) {
				// Unbalanced quotes
				// Too early to use standard i18n support. The class path hasn't
				// been configured.
				throw new IllegalArgumentException(
						"The double quote [\"] character only be used to quote paths. It must "
								+ "not appear in a path. This loader path is not valid: [" + value + "]");
			} else {
				// Not quoted - NO-OP
			}

			result.add(path);
		}
		return result.toArray(new String[result.size()]);
	}
}
