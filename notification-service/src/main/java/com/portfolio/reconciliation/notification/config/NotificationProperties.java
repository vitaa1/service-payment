package com.portfolio.reconciliation.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Endereços estáticos de envio (ADR-0011 — sem conceito de contato no domínio). */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(String from, String to) {}
