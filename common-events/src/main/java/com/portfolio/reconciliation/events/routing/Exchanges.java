package com.portfolio.reconciliation.events.routing;

/** Nomes de exchange do RabbitMQ. Ver docs/events/README.md (fonte da verdade). */
public final class Exchanges {

  /** Topic exchange que roteia todos os eventos de domínio. */
  public static final String EVENTS = "payments.events";

  /** Dead-letter exchange de {@link #EVENTS}. */
  public static final String EVENTS_DLX = "payments.events.dlx";

  private Exchanges() {}
}
