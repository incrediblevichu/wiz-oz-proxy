package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.wizoz.proxy.model.RuleRequest;

class RuleRequestTest {

	private static RuleRequest request(Integer delayMs, Integer status, Integer probability, String pattern) {
		return new RuleRequest(null, null, null, delayMs, status, null, null, probability, pattern);
	}

	@Test
	void defaultsAreFilledIn() {
		RuleRequest rule = request(null, 500, null, null).normalized();
		assertTrue(rule.enabled());
		assertEquals("", rule.method());
		assertEquals("", rule.pathContains());
		assertEquals("ALL", rule.headerMatchMode());
		assertEquals("ALL", rule.queryParamMatchMode());
		assertEquals("", rule.headerName());
		assertEquals("", rule.headerValue());
		assertEquals("", rule.queryParamName());
		assertEquals("", rule.queryParamValue());
		assertEquals(0, rule.delayMs());
		assertEquals("{}", rule.responseBody());
		assertEquals("application/json", rule.contentType());
		assertEquals(100, rule.errorProbability());
		assertEquals("", rule.errorPattern());
	}

	@Test
	void aDelayOnlyRuleHasNoProbability() {
		RuleRequest rule = request(250, null, null, null).normalized();
		assertNull(rule.responseStatus());
		assertNull(rule.errorProbability());
	}

	@Test
	void aPatternOnlyRuleIsValid() {
		assertEquals("S##1,F#500#1", request(null, null, null, " S##1,F#500#1 ").normalized().errorPattern());
	}

	@Test
	void aRuleThatDoesNothingIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> request(null, null, null, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(0, null, null, "  ").normalized());
	}

	@Test
	void outOfRangeValuesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> request(-1, 500, null, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(RuleRequest.MAX_DELAY_MS + 1, 500, null, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(null, 99, null, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(null, 600, null, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(null, 500, 101, null).normalized());
		assertThrows(IllegalArgumentException.class, () -> request(null, 500, -1, null).normalized());
	}

	@Test
	void probabilityWithoutAStatusIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> request(100, null, 50, null).normalized());
	}

	@Test
	void anInvalidPatternIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> request(null, null, null, "nonsense").normalized());
	}

	@Test
	void headerValueWithoutHeaderNameIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new RuleRequest(true, "GET", "/test", "", "val", "", "", 0, 500, "{}", "application/json", 100, "").normalized());
	}

	@Test
	void queryParamValueWithoutQueryParamNameIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new RuleRequest(true, "GET", "/test", "", "", "", "val", 0, 500, "{}", "application/json", 100, "").normalized());
	}

	@Test
	void headerAndQueryParamNormalized() {
		RuleRequest rule = new RuleRequest(true, "GET", "/test", " X-Custom ", " val ", " q ", " v ", 0, 500, "{}", "application/json", 100, "").normalized();
		assertEquals("X-Custom", rule.headerName());
		assertEquals("val", rule.headerValue());
		assertEquals("q", rule.queryParamName());
		assertEquals("v", rule.queryParamValue());
		assertEquals(java.util.Map.of("X-Custom", "val"), rule.headers());
		assertEquals(java.util.Map.of("q", "v"), rule.queryParams());
	}

	@Test
	void multipleHeadersAndQueryParamsNormalized() {
		RuleRequest rule = new RuleRequest(true, "GET", "/test", java.util.Map.of(" X-A ", " 1 ", " X-B ", " 2 "),
				"any", java.util.Map.of(" q1 ", " a ", " q2 ", " b "), "any", null, null, null, null, 0, 500, "{}", "application/json", 100, "").normalized();
		assertEquals(java.util.Map.of("X-A", "1", "X-B", "2"), rule.headers());
		assertEquals(java.util.Map.of("q1", "a", "q2", "b"), rule.queryParams());
		assertEquals("ANY", rule.headerMatchMode());
		assertEquals("ANY", rule.queryParamMatchMode());
	}

	@Test
	void invalidMatchModeThrows() {
		assertThrows(IllegalArgumentException.class,
				() -> new RuleRequest(true, "GET", "/test", java.util.Map.of("X", "1"), "INVALID", null, "ALL", null, null, null, null, 0, 500, "{}", "application/json", 100, "").normalized());
	}

}
