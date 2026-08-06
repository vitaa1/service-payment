package com.portfolio.reconciliation.ingestion.web;

import java.util.List;

/** Resposta 400 da ingestão: os erros de validação do payload. */
public record IngestionErrorResponse(List<String> errors) {}
