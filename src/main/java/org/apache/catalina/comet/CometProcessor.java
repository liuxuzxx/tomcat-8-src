package org.apache.catalina.comet;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

/**
 * This interface should be implemented by servlets which would like to handle
 * asynchronous IO, receiving events when data is available for reading, and
 * being able to output data without the need for being invoked by the container.
 * Note: When this interface is implemented, the service method of the servlet will
 * never be called, and will be replaced with a begin event.
 */
public interface CometProcessor extends Servlet{

    /**
     * Process the given Comet event.
     *
     * @param event The Comet event that will be processed
     * @throws IOException
     * @throws ServletException
     */
    void event(CometEvent event) throws IOException, ServletException;
}
