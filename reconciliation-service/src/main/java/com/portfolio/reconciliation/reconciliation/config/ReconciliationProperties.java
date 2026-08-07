package com.portfolio.reconciliation.reconciliation.config;

import com.portfolio.reconciliation.events.Source;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Política de fontes esperadas para MATCHED (ADR-0010), configurável em application.yml. */
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(Set<Source> expectedSources) {}
