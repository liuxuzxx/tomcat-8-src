package org.apache.catalina.security;

import java.security.Security;

import org.apache.catalina.startup.CatalinaProperties;

/**
 * Util class to protect Catalina against package access and insertion.
 * The code are been moved from Catalina.java
 * @author the Catalina.java authors
 * 我还以为是个什么的JDK的反射安全性质的东西，原来就是一些安全的Config信息，说白了属于数据的操作设置.
 */
public final class SecurityConfig{
    private static SecurityConfig singleton = null;

    private static final org.apache.juli.logging.Log log=
        org.apache.juli.logging.LogFactory.getLog( SecurityConfig.class );


    private static final String PACKAGE_ACCESS =  "sun.,"
                                                + "org.apache.catalina."
                                                + ",org.apache.jasper."
                                                + ",org.apache.coyote."
                                                + ",org.apache.tomcat.";

    // FIX ME package "javax." was removed to prevent HotSpot
    // fatal internal errors
    private static final String PACKAGE_DEFINITION= "java.,sun."
                                                + ",org.apache.catalina."
                                                + ",org.apache.coyote."
                                                + ",org.apache.tomcat."
                                                + ",org.apache.jasper.";
    /**
     * List of protected package from conf/catalina.properties
     */
    private final String packageDefinition;

    /**
     * List of protected package from conf/catalina.properties
     */
    private final String packageAccess;

    /**
     * Create a single instance of this class.
     */
    private SecurityConfig() {
        String definition = null;
        String access = null;
        try{
            definition = CatalinaProperties.getProperty("package.definition");
            access = CatalinaProperties.getProperty("package.access");
        } catch (java.lang.Exception ex){
            if (log.isDebugEnabled()){
                log.debug("Unable to load properties using CatalinaProperties", ex);
            }
        } finally {
            packageDefinition = definition;
            packageAccess = access;
        }
    }

    /**
     * Returns the singleton instance of that class.
     * @return an instance of that class.
     */
    public static SecurityConfig newInstance(){
        if (singleton == null){
            singleton = new SecurityConfig();
        }
        return singleton;
    }

    /**
     * Set the security package.access value.
     */
    public void setPackageAccess(){
        // If catalina.properties is missing, protect all by default.
        if (packageAccess == null){
            setSecurityProperty("package.access", PACKAGE_ACCESS);
        } else {
            setSecurityProperty("package.access", packageAccess);
        }
    }

    /**
     * Set the security package.definition value.
     */
     public void setPackageDefinition(){
        // If catalina.properties is missing, protect all by default.
         if (packageDefinition == null){
            setSecurityProperty("package.definition", PACKAGE_DEFINITION);
         } else {
            setSecurityProperty("package.definition", packageDefinition);
         }
    }

    /**
     * Set the proper security property
     * @param properties the package.* property.
     */
    private final void setSecurityProperty(String properties, String packageList){
        if (System.getSecurityManager() != null){
            String definition = Security.getProperty(properties);
            if( definition != null && definition.length() > 0 ){
                if (packageList.length() > 0) {
                    definition = definition + ',' + packageList;
                }
            } else {
                definition = packageList;
            }
            Security.setProperty(properties, definition);
        }
    }
}