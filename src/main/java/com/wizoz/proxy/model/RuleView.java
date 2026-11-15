package com.wizoz.proxy.model;

import java.util.Map;

/** A rule as returned by the management API, with its generated id and every default filled in. */
public record RuleView(
		String id,
		boolean enabled,
		String method,
		String pathContains,
		Map<String, String> headers,
		String headerMatchMode,
		Map<String, String> queryParams,
		String queryParamMatchMode,
		String headerName,
		String headerValue,
		String queryParamName,
		String queryParamValue,
		int delayMs,
		Integer responseStatus,
		String responseBody,
		String contentType,
		Integer errorProbability,
		String errorPattern) {
}
