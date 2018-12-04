package org.lx.tomcat.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.lx.tomcat.util.SystemUtil;

/**
 * @author 刘旭
 * @description: 分配请求的Servlet
 * @Date 2018-11-19 11:43:58
 */
public class RondoDispatcherServlet extends HttpServlet {

    @Override
    public void init() throws ServletException {
        super.init();
        SystemUtil.logInfo("RondoDispatcherServlet init......");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" /><title>RondoDispatcherServlet登录</title></head><body>你好:登录成功!</body></html>");
        resp.getWriter().write(builder.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        super.doPost(req, resp);
    }
}
