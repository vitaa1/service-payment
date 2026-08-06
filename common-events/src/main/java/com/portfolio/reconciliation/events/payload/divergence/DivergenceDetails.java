package com.portfolio.reconciliation.events.payload.divergence;

/**
 * Detalhe estruturado de uma divergência, uma forma por {@code DivergenceType}. Discriminado
 * pelo campo irmão {@code divergenceType} no payload de {@code DivergenceDetected} (ver
 * docs/events/divergence-detected.md). Allowlist fechado — nenhum outro tipo é aceito.
 *
 * <p>As anotações {@code @JsonTypeInfo}/{@code @JsonSubTypes} ficam no componente {@code
 * details} de {@code DivergenceDetectedPayload}, não aqui: {@code EXTERNAL_PROPERTY} precisa de
 * contexto do container (o campo irmão), não da interface isoladamente.
 */
public sealed interface DivergenceDetails permits DivergentDetails, MissingDetails, DuplicateDetails {}
