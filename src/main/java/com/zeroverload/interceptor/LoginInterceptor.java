package com.zeroverload.interceptor;

import com.zeroverload.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行 CORS 预检请求，否则前端带自定义 header（如 authorization）会在 OPTIONS 阶段被 401 拦截
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
       //1.判断是否需要拦截（ThreadLocal）中是否有用户
        if(UserHolder.getUser()==null){
            //没有需要拦截，设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //由用户放行
        return true;
    }
}
