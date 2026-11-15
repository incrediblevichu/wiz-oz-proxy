package com.wizoz.proxy.model;

import java.util.Enumeration;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntSupplier;

import jakarta.servlet.http.HttpServletRequest;

/** A rule as the proxy runs it: matching, the delay to apply, and the decision whether to fail a request. */
public final class Rule {

	private final String id;
	private final RuleRequest spec;
	private final ErrorPattern pattern;
	private volatile boolean enabled;

	/**
	 * @param spec a request whose defaults have already been filled in
	 */
	public Rule(String id, RuleRequest spec, ErrorPattern pattern) {
		this.id = id;
		this.spec = spec;
		this.pattern = pattern;
		this.enabled = spec.enabled();
	}

	public String id() {
		return id;
	}

	public RuleRequest spec() {
		return spec;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/** Turning a rule on or off also restarts its error pattern. */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		pattern.reset();
	}

	public boolean matches(String method, String path) {
		return matches(method, path, (Function<String, Enumeration<String>>) null, (Function<String, String[]>) null);
	}

	public boolean matches(String method, String path, HttpServletRequest request) {
		if (request == null) {
			return matches(method, path);
		}
		return matches(method, path, request::getHeaders, request::getParameterValues);
	}

	public boolean matches(String method, String path,
			Function<String, Enumeration<String>> headerLookup,
			Function<String, String[]> queryParamLookup) {
		if (!spec.method().isEmpty() && !spec.method().equalsIgnoreCase(method)) {
			return false;
		}
		if (!spec.pathContains().isEmpty() && !path.contains(spec.pathContains())) {
			return false;
		}
		if (spec.headers() != null && !spec.headers().isEmpty()) {
			if (headerLookup == null) {
				return false;
			}
			boolean anyMode = "ANY".equalsIgnoreCase(spec.headerMatchMode());
			boolean matchedAny = false;
			for (var entry : spec.headers().entrySet()) {
				Enumeration<String> headers = headerLookup.apply(entry.getKey());
				boolean matched = headers != null && hasMatchingValue(headers, entry.getValue());
				if (anyMode) {
					if (matched) {
						matchedAny = true;
						break;
					}
				} else {
					if (!matched) {
						return false;
					}
				}
			}
			if (anyMode && !matchedAny) {
				return false;
			}
		}
		if (spec.queryParams() != null && !spec.queryParams().isEmpty()) {
			if (queryParamLookup == null) {
				return false;
			}
			boolean anyMode = "ANY".equalsIgnoreCase(spec.queryParamMatchMode());
			boolean matchedAny = false;
			for (var entry : spec.queryParams().entrySet()) {
				String[] params = queryParamLookup.apply(entry.getKey());
				boolean matched = params != null && hasMatchingValue(params, entry.getValue());
				if (anyMode) {
					if (matched) {
						matchedAny = true;
						break;
					}
				} else {
					if (!matched) {
						return false;
					}
				}
			}
			if (anyMode && !matchedAny) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasMatchingValue(Enumeration<String> values, String expected) {
		while (values.hasMoreElements()) {
			if (expected.equals(values.nextElement())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasMatchingValue(String[] values, String expected) {
		for (String value : values) {
			if (expected.equals(value)) {
				return true;
			}
		}
		return false;
	}

	public int delayMs() {
		return spec.delayMs();
	}

	public String responseBody() {
		return spec.responseBody();
	}

	public String contentType() {
		return spec.contentType();
	}

	/**
	 * Decides what happens to one matching request.
	 *
	 * @param percentRoll a number from 1 to 100, asked for only when the outcome is left to chance
	 * @return the status to answer with, or empty to forward the request
	 */
	public OptionalInt nextFault(IntSupplier percentRoll) {
		if (!pattern.isEmpty()) {
			int status = pattern.next();
			return status == ErrorPattern.SUCCESS ? OptionalInt.empty() : OptionalInt.of(status);
		}
		if (spec.responseStatus() == null) {
			return OptionalInt.empty();
		}
		return percentRoll.getAsInt() <= spec.errorProbability() ? OptionalInt.of(spec.responseStatus()) : OptionalInt.empty();
	}

	public RuleView view() {
		return new RuleView(id, enabled, spec.method(), spec.pathContains(), spec.headers(), spec.headerMatchMode(),
				spec.queryParams(), spec.queryParamMatchMode(), spec.headerName(), spec.headerValue(),
				spec.queryParamName(), spec.queryParamValue(), spec.delayMs(), spec.responseStatus(),
				spec.responseBody(), spec.contentType(), spec.errorProbability(), spec.errorPattern());
	}

}
