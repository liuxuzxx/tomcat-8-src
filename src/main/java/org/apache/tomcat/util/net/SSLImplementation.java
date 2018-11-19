package org.apache.tomcat.util.net;

import java.net.Socket;

import javax.net.ssl.SSLSession;

public abstract class SSLImplementation {
    private static final org.apache.juli.logging.Log logger = org.apache.juli.logging.LogFactory.getLog(SSLImplementation.class);
    private static final String JSSEImplementationClass = "org.apache.tomcat.util.net.jsse.JSSEImplementation";
    private static final String[] implementations = { JSSEImplementationClass };

    public static SSLImplementation getInstance() throws ClassNotFoundException {
        for (String implementation : implementations) {
            try {
                return getInstance(implementation);
            } catch (Exception e) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Error creating " + implementation, e);
                }
            }
        }
        throw new ClassNotFoundException("Can't find any SSL implementation");
    }

    public static SSLImplementation getInstance(String className)
            throws ClassNotFoundException {
        if (className == null) {
            return getInstance();
        }
        try {
            if (JSSEImplementationClass.equals(className)) {
                return new org.apache.tomcat.util.net.jsse.JSSEImplementation();
            }
            Class<?> clazz = Class.forName(className);
            return (SSLImplementation) clazz.newInstance();
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Error loading SSL Implementation " + className, e);
            }
            throw new ClassNotFoundException("Error loading SSL Implementation " + className + " :" + e.toString());
        }
    }

    public abstract String getImplementationName();

    public abstract ServerSocketFactory getServerSocketFactory(AbstractEndpoint<?> endpoint);

    public abstract SSLSupport getSSLSupport(Socket sock);

    public abstract SSLSupport getSSLSupport(SSLSession session);

    public abstract SSLUtil getSSLUtil(AbstractEndpoint<?> ep);
}
