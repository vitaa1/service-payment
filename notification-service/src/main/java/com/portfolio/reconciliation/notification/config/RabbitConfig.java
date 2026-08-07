package com.portfolio.reconciliation.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.reconciliation.events.routing.Exchanges;
import com.portfolio.reconciliation.events.routing.RoutingKeys;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia de consumo do notification-service: declara a fila própria + DLQ + binding
 * (ADR-0005 — nomes de fila não pertencem ao common-events). A routing key do dead-letter é o
 * nome da própria fila, não {@code RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED} (ADR-0011) —
 * essa routing key de produção também alimenta a fila do report-service, e reusá-la na DLX
 * vazaria mensagens mortas entre as duas DLQs.
 */
@Configuration
public class RabbitConfig {

  @Bean
  public TopicExchange paymentsEventsExchange() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS).durable(true).build();
  }

  @Bean
  public TopicExchange paymentsEventsDlx() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS_DLX).durable(true).build();
  }

  @Bean
  public Queue divergenceDetectedQueue() {
    return QueueBuilder.durable(QueueNames.DIVERGENCE_DETECTED)
        .withArgument("x-dead-letter-exchange", Exchanges.EVENTS_DLX)
        .withArgument("x-dead-letter-routing-key", QueueNames.DIVERGENCE_DETECTED)
        .build();
  }

  @Bean
  public Queue divergenceDetectedDlq() {
    return QueueBuilder.durable(QueueNames.DIVERGENCE_DETECTED_DLQ).build();
  }

  @Bean
  public Binding divergenceDetectedBinding(
      Queue divergenceDetectedQueue, TopicExchange paymentsEventsExchange) {
    return BindingBuilder.bind(divergenceDetectedQueue)
        .to(paymentsEventsExchange)
        .with(RoutingKeys.RECONCILIATION_DIVERGENCE_DETECTED);
  }

  @Bean
  public Binding divergenceDetectedDlqBinding(
      Queue divergenceDetectedDlq, TopicExchange paymentsEventsDlx) {
    return BindingBuilder.bind(divergenceDetectedDlq)
        .to(paymentsEventsDlx)
        .with(QueueNames.DIVERGENCE_DETECTED);
  }

  @Bean
  public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }
}
