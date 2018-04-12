package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * <p>Utility class for building class loaders for Catalina.  The factory
 * method requires the following parameters in order to build a new class
 * loader (with suitable defaults in all cases):</p>
 * <ul>
 * <li>A set of directories containing unpacked classes (and resources)
 * that should be included in the class loader's
 * repositories.</li>
 * <li>A set of directories containing classes and resources in JAR files.
 * Each readable JAR file discovered in these directories will be
 * added to the class loader's repositories.</li>
 * <li><code>ClassLoader</code> instance that should become the parent of
 * the new class loader.</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 *         看类名，应该是tomcat自己制作类加载器的一个工厂，也算是工厂设计模式的一个用例了吧。
 */
public final class ClassLoaderFactory {


    private static final Log log = LogFactory.getLog(ClassLoaderFactory.class);

    /**
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param unpacked Array of pathnames to unpacked directories that should
     *                 be added to the repositories of the class loader, or <code>null</code>
     *                 for no unpacked directories to be considered
     * @param packed   Array of pathnames to directories containing JAR files
     *                 that should be added to the repositories of the class loader,
     *                 or <code>null</code> for no directories of JAR files to be considered
     * @param parent   Parent class loader for the new class loader, or
     *                 <code>null</code> for the system class loader.
     * @throws Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(File unpacked[],
                                                File packed[],
                                                final ClassLoader parent)
            throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");

        // Construct the "class path" for this class loader
        Set<URL> set = new LinkedHashSet<>();

        // Add unpacked directories
        if (unpacked != null) {
            for (int i = 0; i < unpacked.length; i++) {
                File file = unpacked[i];
                if (!file.exists() || !file.canRead())
                    continue;
                file = new File(file.getCanonicalPath() + File.separator);
                URL url = file.toURI().toURL();
                if (log.isDebugEnabled())
                    log.debug("  Including directory " + url);
                set.add(url);
            }
        }

        // Add packed directory JAR files
        if (packed != null) {
            for (int i = 0; i < packed.length; i++) {
                File directory = packed[i];
                if (!directory.isDirectory() || !directory.exists() ||
                        !directory.canRead())
                    continue;
                String filenames[] = directory.list();
                if (filenames == null) {
                    continue;
                }
                for (int j = 0; j < filenames.length; j++) {
                    String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                    if (!filename.endsWith(".jar"))
                        continue;
                    File file = new File(directory, filenames[j]);
                    if (log.isDebugEnabled())
                        log.debug("  Including jar file " + file.getAbsolutePath());
                    URL url = file.toURI().toURL();
                    set.add(url);
                }
            }
        }

        // Construct the class loader itself
        final URL[] array = set.toArray(new URL[set.size()]);
        return AccessController.doPrivileged(
                (PrivilegedAction<URLClassLoader>) () -> {
                    if (parent == null)
                        return new URLClassLoader(array);
                    else
                        return new URLClassLoader(array, parent);
                });
    }


    /**
     * Create and return a new class loader, based on the configuration
     * defaults and the specified directory paths:
     *
     * @param repositories List of class directories, jar files, jar directories
     *                     or URLS that should be added to the repositories of
     *                     the class loader.
     * @param parent       Parent class loader for the new class loader, or
     *                     <code>null</code> for the system class loader.
     * @throws Exception if an error occurs constructing the class loader
     */
    public static ClassLoader createClassLoader(List<Repository> repositories,
                                                final ClassLoader parent)
            throws Exception {

        if (log.isDebugEnabled())
            log.debug("Creating new class loader");

        // Construct the "class path" for this class loader
        Set<URL> set = new LinkedHashSet<>();

        /**
         * 不明白tomcat的作者为什么不喜欢使用switch，而是就是死磕if-else语句啊。。。
         */
        if (repositories != null) {
            for (Repository repository : repositories) {
                switch (repository.getType()) {
                    case URL:
                        URL url = new URL(repository.getLocation());
                        if (log.isDebugEnabled())
                            log.debug("  Including URL " + url);
                        set.add(url);
                        break;
                    case DIR:
                        File directory = new File(repository.getLocation());
                        directory = directory.getCanonicalFile();
                        if (!validateFile(directory, RepositoryType.DIR)) {
                            continue;
                        }
                        URL dirUrl = directory.toURI().toURL();
                        if (log.isDebugEnabled())
                            log.debug("  Including directory " + dirUrl);
                        set.add(dirUrl);
                        break;
                    case JAR:
                        File jarFile = new File(repository.getLocation());
                        jarFile = jarFile.getCanonicalFile();
                        if (!validateFile(jarFile, RepositoryType.JAR)) {
                            continue;
                        }
                        URL jarUrl = jarFile.toURI().toURL();
                        if (log.isDebugEnabled())
                            log.debug("  Including jar file " + jarUrl);
                        set.add(jarUrl);
                        break;
                    case GLOB:
                        File globDirectory = new File(repository.getLocation());
                        globDirectory = globDirectory.getCanonicalFile();
                        if (!validateFile(globDirectory, RepositoryType.GLOB)) {
                            continue;
                        }
                        if (log.isDebugEnabled())
                            log.debug("  Including directory glob "
                                    + globDirectory.getAbsolutePath());
                        String filenames[] = globDirectory.list();
                        if (filenames == null) {
                            continue;
                        }
                        for (int j = 0; j < filenames.length; j++) {
                            String filename = filenames[j].toLowerCase(Locale.ENGLISH);
                            if (!filename.endsWith(".jar"))
                                continue;
                            File file = new File(globDirectory, filenames[j]);
                            file = file.getCanonicalFile();
                            if (!validateFile(file, RepositoryType.JAR)) {
                                continue;
                            }
                            if (log.isDebugEnabled())
                                log.debug("    Including glob jar file " + file.getAbsolutePath());
                            URL globUrl = file.toURI().toURL();
                            set.add(globUrl);
                        }
                        break;
                }
            }
        }

        // Construct the class loader itself
        final URL[] array = set.toArray(new URL[set.size()]);
        if (log.isDebugEnabled())
            for (int i = 0; i < array.length; i++) {
                log.debug("  location " + i + " is " + array[i]);
            }

        return AccessController.doPrivileged(
                (PrivilegedAction<URLClassLoader>) () -> {
                    if (parent == null)
                        return new URLClassLoader(array);
                    else
                        return new URLClassLoader(array, parent);
                });
    }

    private static boolean validateFile(File file,
                                        RepositoryType type) throws IOException {
        if (RepositoryType.DIR == type || RepositoryType.GLOB == type) {
            if (!file.exists() || !file.isDirectory() || !file.canRead()) {
                String msg = "Problem with directory [" + file +
                        "], exists: [" + file.exists() +
                        "], isDirectory: [" + file.isDirectory() +
                        "], canRead: [" + file.canRead() + "]";

                File home = new File(Bootstrap.getCatalinaHome());
                home = home.getCanonicalFile();
                File base = new File(Bootstrap.getCatalinaBase());
                base = base.getCanonicalFile();
                File defaultValue = new File(base, "lib");

                // Existence of ${catalina.base}/lib directory is optional.
                // Hide the warning if Tomcat runs with separate catalina.home
                // and catalina.base and that directory is absent.
                if (!home.getPath().equals(base.getPath())
                        && file.getPath().equals(defaultValue.getPath())
                        && !file.exists()) {
                    log.debug(msg);
                } else {
                    log.warn(msg);
                }
                return false;
            }
        } else if (RepositoryType.JAR == type) {
            if (!file.exists() || !file.canRead()) {
                log.warn("Problem with JAR file [" + file +
                        "], exists: [" + file.exists() +
                        "], canRead: [" + file.canRead() + "]");
                return false;
            }
        }
        return true;
    }

    public static enum RepositoryType {
        DIR,
        GLOB,
        JAR,
        URL
    }

    public static class Repository {
        private final String location;
        private final RepositoryType type;

        public Repository(String location, RepositoryType type) {
            this.location = location;
            this.type = type;
        }

        public String getLocation() {
            return location;
        }

        public RepositoryType getType() {
            return type;
        }
    }
}
