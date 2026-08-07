package com.portfolio.reconciliation.reconciliation.config;

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
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topologia do reconciliation-service: produtor (declara a exchange que publica, Decisão 5a) e
 * consumidor (declara sua própria fila + DLQ + binding, ADR-0010 decisão 6a — nomes de fila não
 * pertencem ao common-events, ADR-0005). {@code @EnableScheduling} liga o relay e o purge da
 * outbox.
 */
@Configuration
@EnableScheduling
public class RabbitConfig {

  @Bean
  public TopicExchange paymentsEventsExchange() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS).durable(true).build();
  }

  @Bean
  public TopicExchange paymentsEventsDlx() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS_DLX).durable(true).build();
  }

  /** Mensagens que esgotam o retry (ver application.yml) são rejeitadas e dead-letradas aqui. */
  @Bean
  public Queue transactionNormalizedQueue() {
    return QueueBuilder.durable(QueueNames.TRANSACTION_NORMALIZED)
        .withArgument("x-dead-letter-exchange", Exchanges.EVENTS_DLX)
        .withArgument("x-dead-letter-routing-key", RoutingKeys.TRANSACTION_NORMALIZED)
        .build();
  }

  @Bean
  public Queue transactionNormalizedDlq() {
    return QueueBuilder.durable(QueueNames.TRANSACTION_NORMALIZED_DLQ).build();
  }

  @Bean
  public Binding transactionNormalizedBinding(
      Queue transactionNormalizedQueue, TopicExchange paymentsEventsExchange) {
    return BindingBuilder.bind(transactionNormalizedQueue)
        .to(paymentsEventsExchange)
        .with(RoutingKeys.TRANSACTION_NORMALIZED);
  }

  @Bean
  public Binding transactionNormalizedDlqBinding(
      Queue transactionNormalizedDlq, TopicExchange paymentsEventsDlx) {
    return BindingBuilder.bind(transactionNormalizedDlq)
        .to(paymentsEventsDlx)
        .with(RoutingKeys.TRANSACTION_NORMALIZED);
  }

  /**
   * Habilita a desserialização do envelope pelo tipo concreto do parâmetro do listener (Decisão 2
   * / ADR-0005) — sem cabeçalho de tipo, uma fila carrega só um eventType.
   */
  @Bean
  public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }
}
