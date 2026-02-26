package com.zeroverload;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.zeroverload.mapper")
@EnableRabbit
@SpringBootApplication
public class HmDianPingApplication {
    //
    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
