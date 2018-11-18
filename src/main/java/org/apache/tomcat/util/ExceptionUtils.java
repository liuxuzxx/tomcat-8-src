package org.apache.tomcat.util;

import java.lang.reflect.InvocationTargetException;

public class ExceptionUtils {

    public static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof StackOverflowError) {
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    public static Throwable unwrapInvocationTargetException(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }
}
