package com.zeroverload.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "rental.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RentalMqConfig {
    public static final String EXCHANGE = "rental.exchange";
    public static final String RENT_QUEUE = "rental.rent.queue";
    public static final String RETURN_QUEUE = "rental.return.queue";
    public static final String RENT_ROUTING_KEY = "rental.rent";
    public static final String RETURN_ROUTING_KEY = "rental.return";

    @Bean
    public DirectExchange rentalExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue rentalRentQueue() {
        return QueueBuilder.durable(RENT_QUEUE).build();
    }

    @Bean
    public Queue rentalReturnQueue() {
        return QueueBuilder.durable(RETURN_QUEUE).build();
    }

    @Bean
    public Binding rentalRentBinding(Queue rentalRentQueue, DirectExchange rentalExchange) {
        return BindingBuilder.bind(rentalRentQueue).to(rentalExchange).with(RENT_ROUTING_KEY);
    }

    @Bean
    public Binding rentalReturnBinding(Queue rentalReturnQueue, DirectExchange rentalExchange) {
        return BindingBuilder.bind(rentalReturnQueue).to(rentalExchange).with(RETURN_ROUTING_KEY);
    }
}
