package com.flashslot.messaging.config;

import com.flashslot.messaging.model.NotificationEventType;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.util.ArrayList;

@Configuration
@ConditionalOnProperty(
        prefix = "app.messaging",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MessagingConfiguration {
    @Bean
    public Declarables notificationTopology() {
        var exchange = new DirectExchange("flashslot.events", true, false);
        var deadExchange = new DirectExchange("flashslot.dlx", true, false);
        var queue =
                QueueBuilder.durable("flashslot.notification")
                        .deadLetterExchange("flashslot.dlx")
                        .deadLetterRoutingKey("notification.dead")
                        .build();
        var dead = QueueBuilder.durable("flashslot.notification.dead").build();
        var declarations = new ArrayList<Declarable>();
        declarations.add(exchange);
        declarations.add(deadExchange);
        declarations.add(queue);
        declarations.add(dead);
        declarations.add(BindingBuilder.bind(dead).to(deadExchange).with("notification.dead"));
        for (var type : NotificationEventType.values())
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(type.getRoutingKey()));
        return new Declarables(declarations);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory notificationListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connection) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        // AUTO 是监听成功返回后由容器 ACK，不是 RabbitMQ 的 no-ack 模式。
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(20);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(3)
                        .backOffOptions(1000, 2, 5000)
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build());
        return factory;
    }
}
