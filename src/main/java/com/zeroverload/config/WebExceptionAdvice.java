package com.zeroverload.config;

import com.zeroverload.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RedisConnectionFailureException.class)
    public Result handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.error("Redis connection failure", e);
        return Result.fail("Redis 连接失败：请确认 Redis 已启动，并检查 spring.redis.host/port 配置");
    }

    @ExceptionHandler(RedisSystemException.class)
    public Result handleRedisSystem(RedisSystemException e) {
        log.error("Redis system exception", e);
        return Result.fail("Redis 异常：请确认 Redis 可用，并检查相关配置");
    }

    @ExceptionHandler(DataAccessException.class)
    public Result handleDataAccess(DataAccessException e) {
        log.error("Database access exception", e);
        return Result.fail("数据库访问失败：请确认 MySQL 已启动、hmdp 已导入，并检查 spring.datasource.* 配置");
    }

    @ExceptionHandler(Exception.class)
    public Result handleOther(Exception e) {
        log.error("Unhandled exception", e);
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return Result.fail("服务端异常：" + e.getClass().getSimpleName());
        }
        msg = msg.trim().replaceAll("\\s+", " ");
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return Result.fail("服务端异常：" + msg);
    }
}
