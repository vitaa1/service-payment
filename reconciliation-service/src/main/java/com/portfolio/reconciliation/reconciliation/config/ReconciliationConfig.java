package com.portfolio.reconciliation.reconciliation.config;

import com.portfolio.reconciliation.reconciliation.evaluation.CaseEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReconciliationConfig {

  @Bean
  public CaseEvaluator caseEvaluator(ReconciliationProperties properties) {
    return new CaseEvaluator(properties.expectedSources());
  }
}
