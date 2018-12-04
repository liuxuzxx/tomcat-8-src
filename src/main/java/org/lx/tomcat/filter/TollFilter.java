package org.lx.tomcat.filter;

import java.io.IOException;
import java.text.MessageFormat;

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
 * @description: 收费站的过滤器
 * @Date 2018-12-04 15:32:48
 */
public class TollFilter implements Filter {
    private static final Log log = LogFactory.getLog(TollFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("TollFilter init......");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        log.info(MessageFormat.format("Toll对URL：{0} 收费！",request.getRemoteHost()));
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.info("TollFilter is destroy.");
    }
}
