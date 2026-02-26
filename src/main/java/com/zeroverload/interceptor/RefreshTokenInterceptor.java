package com.zeroverload.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.zeroverload.dto.UserDTO;
import com.zeroverload.utils.RedisConstants;
import com.zeroverload.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private  StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        //2.基于token获取redis中的用户
        if (StrUtil.isBlank(token)) {
            return true; // token为空，说明用户未登录，直接放行（后续由LoginInterceptor判断是否拦截）
        }
        String userKey = RedisConstants.LOGIN_USER_KEY + token; //拼接Redis中存储用户信息的Key
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(userKey); //从Redis中获取用户信息（Hash结构）
        //3.判断用户是否存在
        if(map.isEmpty()) {
            return true; // 没有用户信息，说明token无效/过期，直接放行
        }
        //5.将查询到Hash数据转换为userDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(map, new UserDTO(), false);
        //6.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.刷新有效期
        stringRedisTemplate.expire(userKey,30, TimeUnit.MINUTES);
        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // afterCompletion方法：请求处理完成后（包括视图渲染）执行
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);

        // 清空ThreadLocal中的用户信息，避免内存泄漏
        UserHolder.removeUser();
    }
}
