package org.lx.tomcat.filter;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * @author 刘旭
 * @description: 指定资源的过滤器
 * @Date 2018-12-04 15:48:23
 */
public class ResourceFilter implements Filter {
    private static final Log log = LogFactory.getLog(ResourceFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("ResourceFilter is init...");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        log.info("Resource do the filter");
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.info("ResourceFilter destroy!");
    }
}
