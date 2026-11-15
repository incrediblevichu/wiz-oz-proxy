package com.wizoz.proxy.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * What a client sends to create or replace a rule. A rule has to do something: set {@code delayMs},
 * {@code responseStatus} or {@code errorPattern}.
 *
 * @param enabled          defaults to true
 * @param method           HTTP method to match; blank matches any method
 * @param pathContains     the rule applies when the request path contains this text; blank matches every path
 * @param headers          map of HTTP headers that must all match (case-insensitive keys)
 * @param queryParams      map of query parameters that must all match
 * @param headerName       single header name for convenience (merged into {@code headers})
 * @param headerValue      single header value for convenience
 * @param queryParamName   single query parameter name for convenience (merged into {@code queryParams})
 * @param queryParamValue  single query parameter value for convenience
 * @param delayMs          milliseconds to wait before the request is answered or forwarded; defaults to 0
 * @param responseStatus   status of the mocked response; when absent, requests are forwarded
 * @param responseBody     body of the mocked response; defaults to {@code {}}
 * @param contentType      content type of the mocked response; defaults to {@code application/json}
 * @param errorProbability percent (0-100) of matching requests that get the mocked response; defaults to 100 when
 *                         {@code responseStatus} is set, and can't be set without it
 * @param errorPattern     deterministic sequence such as {@code S##2,F#503#3,S##1}, see {@link ErrorPattern}. When
 *                         set, it decides which requests fail and with what status, instead of
 *                         {@code responseStatus} and {@code errorProbability}
 */
public record RuleRequest(
		Boolean enabled,
		String method,
		String pathContains,
		Map<String, String> headers,
		String headerMatchMode,
		Map<String, String> queryParams,
		String queryParamMatchMode,
		@JsonAlias({"headerKey", "header_name", "header_key"})
		String headerName,
		@JsonAlias({"header_value", "headerVal"})
		String headerValue,
		@JsonAlias({"queryParamKey", "queryParam", "query_param_name", "query_param_key"})
		String queryParamName,
		@JsonAlias({"query_param_value", "queryParamVal"})
		String queryParamValue,
		Integer delayMs,
		Integer responseStatus,
		String responseBody,
		String contentType,
		Integer errorProbability,
		String errorPattern) {

	public static final int MAX_DELAY_MS = 600_000;

	/** Overloaded constructor for backwards compatibility without header/query param criteria. */
	public RuleRequest(Boolean enabled, String method, String pathContains, Integer delayMs, Integer responseStatus,
			String responseBody, String contentType, Integer errorProbability, String errorPattern) {
		this(enabled, method, pathContains, null, "ALL", null, "ALL", null, null, null, null, delayMs, responseStatus,
				responseBody, contentType, errorProbability, errorPattern);
	}

	/** Overloaded constructor for single header / query param criteria. */
	public RuleRequest(Boolean enabled, String method, String pathContains, String headerName, String headerValue,
			String queryParamName, String queryParamValue, Integer delayMs, Integer responseStatus, String responseBody,
			String contentType, Integer errorProbability, String errorPattern) {
		this(enabled, method, pathContains, null, "ALL", null, "ALL", headerName, headerValue, queryParamName,
				queryParamValue, delayMs, responseStatus, responseBody, contentType, errorProbability, errorPattern);
	}

	/** Overloaded constructor with headers and queryParams maps without explicit match modes (defaults to ALL). */
	public RuleRequest(Boolean enabled, String method, String pathContains, Map<String, String> headers,
			Map<String, String> queryParams, String headerName, String headerValue, String queryParamName,
			String queryParamValue, Integer delayMs, Integer responseStatus, String responseBody, String contentType,
			Integer errorProbability, String errorPattern) {
		this(enabled, method, pathContains, headers, "ALL", queryParams, "ALL", headerName, headerValue, queryParamName,
				queryParamValue, delayMs, responseStatus, responseBody, contentType, errorProbability, errorPattern);
	}

	/**
	 * @return a copy with every default filled in and every field trimmed
	 * @throws IllegalArgumentException if the rule is invalid
	 */
	public RuleRequest normalized() {
		int delay = delayMs == null ? 0 : delayMs;
		if (delay < 0 || delay > MAX_DELAY_MS) {
			throw new IllegalArgumentException("delayMs must be between 0 and " + MAX_DELAY_MS);
		}
		if (responseStatus != null && (responseStatus < 100 || responseStatus > 599)) {
			throw new IllegalArgumentException("responseStatus must be between 100 and 599");
		}
		if (errorProbability != null && responseStatus == null) {
			throw new IllegalArgumentException("errorProbability needs responseStatus");
		}
		if (errorProbability != null && (errorProbability < 0 || errorProbability > 100)) {
			throw new IllegalArgumentException("errorProbability must be between 0 and 100");
		}
		String pattern = errorPattern == null ? "" : errorPattern.trim();
		ErrorPattern.parse(pattern);
		if (delay == 0 && responseStatus == null && pattern.isEmpty()) {
			throw new IllegalArgumentException("A rule must set delayMs, responseStatus or errorPattern");
		}

		String hMode = headerMatchMode == null || headerMatchMode.isBlank() ? "ALL" : headerMatchMode.trim().toUpperCase();
		if (!"ALL".equals(hMode) && !"ANY".equals(hMode)) {
			throw new IllegalArgumentException("headerMatchMode must be ALL or ANY");
		}

		String qMode = queryParamMatchMode == null || queryParamMatchMode.isBlank() ? "ALL" : queryParamMatchMode.trim().toUpperCase();
		if (!"ALL".equals(qMode) && !"ANY".equals(qMode)) {
			throw new IllegalArgumentException("queryParamMatchMode must be ALL or ANY");
		}

		Map<String, String> normalizedHeaders = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((k, v) -> {
				if (k != null && !k.trim().isEmpty()) {
					normalizedHeaders.put(k.trim(), v == null ? "" : v.trim());
				}
			});
		}
		String hName = headerName == null ? "" : headerName.trim();
		String hValue = headerValue == null ? "" : headerValue.trim();
		if (!hName.isEmpty()) {
			normalizedHeaders.put(hName, hValue);
		} else if (!hValue.isEmpty() && normalizedHeaders.isEmpty()) {
			throw new IllegalArgumentException("headerValue needs headerName");
		}

		Map<String, String> normalizedQueryParams = new LinkedHashMap<>();
		if (queryParams != null) {
			queryParams.forEach((k, v) -> {
				if (k != null && !k.trim().isEmpty()) {
					normalizedQueryParams.put(k.trim(), v == null ? "" : v.trim());
				}
			});
		}
		String qName = queryParamName == null ? "" : queryParamName.trim();
		String qValue = queryParamValue == null ? "" : queryParamValue.trim();
		if (!qName.isEmpty()) {
			normalizedQueryParams.put(qName, qValue);
		} else if (!qValue.isEmpty() && normalizedQueryParams.isEmpty()) {
			throw new IllegalArgumentException("queryParamValue needs queryParamName");
		}

		return new RuleRequest(
				enabled == null || enabled,
				method == null ? "" : method.trim(),
				pathContains == null ? "" : pathContains.trim(),
				Collections.unmodifiableMap(normalizedHeaders),
				hMode,
				Collections.unmodifiableMap(normalizedQueryParams),
				qMode,
				hName,
				hValue,
				qName,
				qValue,
				delay,
				responseStatus,
				responseBody == null ? "{}" : responseBody,
				contentType == null || contentType.isBlank() ? "application/json" : contentType.trim(),
				responseStatus == null ? null : (errorProbability == null ? 100 : errorProbability),
				pattern);
	}

}
