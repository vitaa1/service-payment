package com.portfolio.reconciliation.ingestion.web;

import java.util.UUID;

/** Resposta 202 da ingestão. O cliente acompanha depois via traceId. */
public record IngestionResponse(UUID ingestionId, UUID traceId) {}
