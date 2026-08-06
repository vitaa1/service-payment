package com.portfolio.reconciliation.ingestion.config;

import com.portfolio.reconciliation.events.routing.Exchanges;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topologia que o ingestion declara. Como produtor puro, declara apenas a exchange que publica
 * (Decisão 5a / ADR-0006); filas e DLX pertencem aos consumidores. {@code @EnableScheduling}
 * liga o relay e o purge da outbox.
 */
@Configuration
@EnableScheduling
public class RabbitConfig {

  @Bean
  public TopicExchange paymentsEventsExchange() {
    return ExchangeBuilder.topicExchange(Exchanges.EVENTS).durable(true).build();
  }
}
