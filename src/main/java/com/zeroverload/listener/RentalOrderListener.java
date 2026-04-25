package com.zeroverload.listener;

import cn.hutool.json.JSONUtil;
import com.zeroverload.config.RentalMqConfig;
import com.zeroverload.dto.RentalRentMessage;
import com.zeroverload.dto.RentalReturnMessage;
import com.zeroverload.service.impl.RentalOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
@ConditionalOnProperty(name = "rental.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RentalOrderListener {
    @Resource
    private RentalOrderServiceImpl rentalOrderService;

    @RabbitListener(queues = RentalMqConfig.RENT_QUEUE)
    public void onRentMessage(String body) {
        RentalRentMessage msg = JSONUtil.toBean(body, RentalRentMessage.class);
        rentalOrderService.persistRentFromMq(msg);
    }

    @RabbitListener(queues = RentalMqConfig.RETURN_QUEUE)
    public void onReturnMessage(String body) {
        RentalReturnMessage msg = JSONUtil.toBean(body, RentalReturnMessage.class);
        rentalOrderService.persistReturnFromMq(msg);
    }
}
