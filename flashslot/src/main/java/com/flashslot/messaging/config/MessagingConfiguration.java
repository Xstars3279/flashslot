package com.flashslot.messaging.config;

import com.flashslot.messaging.model.NotificationEventType;
import com.flashslot.messaging.model.NotificationEvent;
import com.flashslot.messaging.model.PublishedEvent;
import com.flashslot.waitlist.service.WaitlistMatchingService;
import com.flashslot.messaging.service.DeadLetterStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.util.ArrayList;

@Configuration
@Slf4j
@ConditionalOnProperty(
        prefix = "app.messaging",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MessagingConfiguration {
    @Bean
    public SimpleRabbitListenerContainerFactory deadLetterListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connection) {
        var factory=new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connection);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(true);
        // 数据库故障时退避重试，最终仍回到死信队列，绝不 ACK 尚未持久化的消息。
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
            .backOffOptions(1000,2,5000)
            .recoverer((message,cause) -> { throw new org.springframework.amqp.ImmediateRequeueAmqpException("死信保存失败，保留原消息"); }).build());
        return factory;
    }
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
        var release=QueueBuilder.durable("flashslot.release").deadLetterExchange("flashslot.dlx")
            .deadLetterRoutingKey("release.dead").build();
        var releaseDead=QueueBuilder.durable("flashslot.release.dead").build();
        declarations.add(release);
        declarations.add(releaseDead);
        declarations.add(BindingBuilder.bind(release).to(exchange).with("release.dispatch"));
        declarations.add(BindingBuilder.bind(releaseDead).to(deadExchange).with("release.dead"));
        for (var type : NotificationEventType.values())
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(type.getRoutingKey()));
        return new Declarables(declarations);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory releaseListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connection,
            ObjectMapper json,WaitlistMatchingService matching,DeadLetterStore failures) {
        // 独立工厂，避免调用另一个 @Bean 方法后修改同一个工厂的重试策略。
        var factory=new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connection);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
            .backOffOptions(1000,2,5000).recoverer((message,cause) -> {
                try {
                    var event=json.readValue(message.getBody(),NotificationEvent.class);
                    PublishedEvent.validate(event);
                    if(PublishedEvent.RELEASE.equals(event.eventType())) {
                        Throwable root=cause;
                        while(root instanceof org.springframework.amqp.rabbit.support.ListenerExecutionFailedException
                            && root.getCause()!=null && root.getCause()!=root) root=root.getCause();
                        matching.recordFailure(event.payload().path("jobId").asLong(),
                            root instanceof RuntimeException runtime ? runtime : new IllegalStateException(root));
                    }
                } catch(Exception ex) {
                    log.warn("撮合失败记录未完成，exception={}",ex.getClass().getSimpleName());
                }
                persistFailure(failures,message,cause);
            }).build());
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory notificationListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connection,DeadLetterStore failures) {
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
                        .recoverer((message,cause) -> persistFailure(failures,message,cause))
                        .build());
        return factory;
    }

    /** 保存事务提交后 recoverer 才正常返回并允许 AUTO ACK；失败则保留原队列消息。 */
    private void persistFailure(DeadLetterStore failures,Message message,Throwable cause) {
        try { failures.capture(message,cause); }
        catch(RuntimeException ex) {
            throw new org.springframework.amqp.ImmediateRequeueAmqpException("消费失败原文尚未持久化，保留原消息",ex);
        }
    }
}
