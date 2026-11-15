package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wizoz.proxy.model.Rule;
import com.wizoz.proxy.model.RuleRequest;
import com.wizoz.proxy.service.InvalidRuleException;
import com.wizoz.proxy.service.RuleNotFoundException;
import com.wizoz.proxy.service.RuleService;
import com.wizoz.proxy.service.impl.RuleServiceImpl;

class RuleServiceTest {

	private RuleService service;

	@BeforeEach
	void setUp() {
		service = new RuleServiceImpl();
	}

	private static RuleRequest failing(String method, String path, int status) {
		return new RuleRequest(null, method, path, null, status, null, null, null, null);
	}

	private static RuleRequest pattern(String path, String pattern) {
		return new RuleRequest(null, null, path, null, null, null, null, null, pattern);
	}

	@Test
	void findsNothingWhenThereAreNoRules() {
		assertTrue(service.findApplicable("GET", "/anything").isEmpty());
	}

	@Test
	void matchesOnPathAndMethod() {
		service.add(failing("POST", "/orders", 500));
		assertTrue(service.findApplicable("POST", "/api/orders/1").isPresent());
		assertTrue(service.findApplicable("post", "/api/orders/1").isPresent());
		assertTrue(service.findApplicable("GET", "/api/orders/1").isEmpty());
		assertTrue(service.findApplicable("POST", "/api/users").isEmpty());
	}

	@Test
	void aBlankPathAndMethodMatchEverything() {
		service.add(failing("", "", 500));
		assertTrue(service.findApplicable("DELETE", "/whatever").isPresent());
	}

	@Test
	void matchesOnHeaders() {
		service.add(new RuleRequest(null, null, null, "X-Mock", "fail", null, null, null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest withHeader = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		withHeader.addHeader("X-Mock", "fail");
		assertTrue(service.findApplicable("GET", "/test", withHeader).isPresent());

		org.springframework.mock.web.MockHttpServletRequest withDifferentHeaderValue = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		withDifferentHeaderValue.addHeader("X-Mock", "pass");
		assertTrue(service.findApplicable("GET", "/test", withDifferentHeaderValue).isEmpty());

		org.springframework.mock.web.MockHttpServletRequest withoutHeader = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		assertTrue(service.findApplicable("GET", "/test", withoutHeader).isEmpty());

		org.springframework.mock.web.MockHttpServletRequest caseInsensitiveHeader = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		caseInsensitiveHeader.addHeader("x-mock", "fail");
		assertTrue(service.findApplicable("GET", "/test", caseInsensitiveHeader).isPresent());
	}

	@Test
	void matchesOnMultipleHeaders() {
		service.add(new RuleRequest(null, null, null, java.util.Map.of("X-A", "1", "X-B", "2"), "ALL", null, "ALL", null, null, null, null, null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest matching = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matching.addHeader("X-A", "1");
		matching.addHeader("X-B", "2");
		assertTrue(service.findApplicable("GET", "/test", matching).isPresent());

		org.springframework.mock.web.MockHttpServletRequest partial = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		partial.addHeader("X-A", "1");
		assertTrue(service.findApplicable("GET", "/test", partial).isEmpty());

		org.springframework.mock.web.MockHttpServletRequest mismatch = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		mismatch.addHeader("X-A", "1");
		mismatch.addHeader("X-B", "3");
		assertTrue(service.findApplicable("GET", "/test", mismatch).isEmpty());
	}

	@Test
	void matchesOnAnyHeaderWhenModeIsAny() {
		service.add(new RuleRequest(null, null, null, java.util.Map.of("X-A", "1", "X-B", "2"), "ANY", null, "ALL", null, null, null, null, null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest matchingFirst = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingFirst.addHeader("X-A", "1");
		assertTrue(service.findApplicable("GET", "/test", matchingFirst).isPresent());

		org.springframework.mock.web.MockHttpServletRequest matchingSecond = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingSecond.addHeader("X-B", "2");
		assertTrue(service.findApplicable("GET", "/test", matchingSecond).isPresent());

		org.springframework.mock.web.MockHttpServletRequest matchingNone = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingNone.addHeader("X-A", "wrong");
		matchingNone.addHeader("X-C", "other");
		assertTrue(service.findApplicable("GET", "/test", matchingNone).isEmpty());
	}

	@Test
	void matchesOnQueryParams() {
		service.add(new RuleRequest(null, null, null, null, null, "env", "test", null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest withParam = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		withParam.setParameter("env", "test");
		assertTrue(service.findApplicable("GET", "/test", withParam).isPresent());

		org.springframework.mock.web.MockHttpServletRequest withDifferentParam = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		withDifferentParam.setParameter("env", "prod");
		assertTrue(service.findApplicable("GET", "/test", withDifferentParam).isEmpty());

		org.springframework.mock.web.MockHttpServletRequest withoutParam = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		assertTrue(service.findApplicable("GET", "/test", withoutParam).isEmpty());
	}

	@Test
	void matchesOnMultipleQueryParams() {
		service.add(new RuleRequest(null, null, null, null, "ALL", java.util.Map.of("p1", "v1", "p2", "v2"), "ALL", null, null, null, null, null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest matching = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matching.setParameter("p1", "v1");
		matching.setParameter("p2", "v2");
		assertTrue(service.findApplicable("GET", "/test", matching).isPresent());

		org.springframework.mock.web.MockHttpServletRequest partial = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		partial.setParameter("p1", "v1");
		assertTrue(service.findApplicable("GET", "/test", partial).isEmpty());

		org.springframework.mock.web.MockHttpServletRequest mismatch = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		mismatch.setParameter("p1", "v1");
		mismatch.setParameter("p2", "wrong");
		assertTrue(service.findApplicable("GET", "/test", mismatch).isEmpty());
	}

	@Test
	void matchesOnAnyQueryParamWhenModeIsAny() {
		service.add(new RuleRequest(null, null, null, null, "ALL", java.util.Map.of("p1", "v1", "p2", "v2"), "ANY", null, null, null, null, null, 500, null, null, null, null));
		org.springframework.mock.web.MockHttpServletRequest matchingFirst = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingFirst.setParameter("p1", "v1");
		assertTrue(service.findApplicable("GET", "/test", matchingFirst).isPresent());

		org.springframework.mock.web.MockHttpServletRequest matchingSecond = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingSecond.setParameter("p2", "v2");
		assertTrue(service.findApplicable("GET", "/test", matchingSecond).isPresent());

		org.springframework.mock.web.MockHttpServletRequest matchingNone = new org.springframework.mock.web.MockHttpServletRequest("GET", "/test");
		matchingNone.setParameter("p1", "wrong");
		matchingNone.setParameter("p3", "other");
		assertTrue(service.findApplicable("GET", "/test", matchingNone).isEmpty());
	}

	@Test
	void theFirstMatchingRuleWins() {
		String first = service.add(failing("", "/a", 500));
		service.add(failing("", "/a", 503));
		assertEquals(first, service.findApplicable("GET", "/a").orElseThrow().id());
	}

	@Test
	void disabledRulesAreSkipped() {
		String first = service.add(failing("", "/a", 500));
		String second = service.add(failing("", "/a", 503));
		service.setEnabled(first, false);
		assertEquals(second, service.findApplicable("GET", "/a").orElseThrow().id());
		service.setEnabled(first, true);
		assertEquals(first, service.findApplicable("GET", "/a").orElseThrow().id());
	}

	@Test
	void pausingStopsEveryRuleFromApplying() {
		service.add(failing("", "", 500));
		service.setPaused(true);
		assertTrue(service.isPaused());
		assertTrue(service.findApplicable("GET", "/a").isEmpty());
		service.setPaused(false);
		assertTrue(service.findApplicable("GET", "/a").isPresent());
	}

	@Test
	void togglingARuleRestartsItsPattern() {
		String id = service.add(pattern("", "S##1,F#500#1"));
		Rule rule = service.findApplicable("GET", "/").orElseThrow();
		assertEquals(OptionalInt.empty(), rule.nextFault(() -> 1));
		assertEquals(OptionalInt.of(500), rule.nextFault(() -> 1));
		service.setEnabled(id, false);
		service.setEnabled(id, true);
		assertEquals(OptionalInt.empty(), rule.nextFault(() -> 1));
	}

	@Test
	void replaceKeepsPositionAndId() {
		String first = service.add(failing("", "/a", 500));
		String second = service.add(failing("", "/b", 500));
		service.replace(first, failing("", "/c", 502));
		List<String> ids = service.list().stream().map(r -> r.id()).toList();
		assertEquals(List.of(first, second), ids);
		assertEquals("/c", service.get(first).pathContains());
		assertEquals(502, service.get(first).responseStatus());
	}

	@Test
	void replaceRestartsThePattern() {
		String id = service.add(pattern("", "S##1,F#500#1"));
		Rule before = service.findApplicable("GET", "/").orElseThrow();
		before.nextFault(() -> 1);
		service.replace(id, pattern("", "S##1,F#500#1"));
		Rule after = service.findApplicable("GET", "/").orElseThrow();
		assertEquals(OptionalInt.empty(), after.nextFault(() -> 1));
	}

	@Test
	void deleteRemovesOneRuleAndDeleteAllRemovesEverything() {
		String first = service.add(failing("", "/a", 500));
		service.add(failing("", "/b", 500));
		service.delete(first);
		assertEquals(1, service.list().size());
		service.deleteAll();
		assertEquals(0, service.list().size());
	}

	@Test
	void deleteAllRemovesRulesThatAreDisabledToo() {
		String id = service.add(failing("", "/a", 500));
		service.setEnabled(id, false);
		service.deleteAll();
		assertTrue(service.list().isEmpty());
	}

	@Test
	void unknownIdsAreReported() {
		assertThrows(RuleNotFoundException.class, () -> service.get("nope"));
		assertThrows(RuleNotFoundException.class, () -> service.delete("nope"));
		assertThrows(RuleNotFoundException.class, () -> service.setEnabled("nope", true));
		assertThrows(RuleNotFoundException.class, () -> service.replace("nope", failing("", "", 500)));
	}

	@Test
	void invalidRulesAreRejectedAndNotStored() {
		assertThrows(InvalidRuleException.class, () -> service.add(new RuleRequest(null, null, null, null, null, null, null, null, null)));
		assertThrows(InvalidRuleException.class, () -> service.add(null));
		assertThrows(InvalidRuleException.class, () -> service.add(pattern("", "bad")));
		assertTrue(service.list().isEmpty());
	}

	@Test
	void probabilityDecidesWhichRequestsFail() {
		service.add(new RuleRequest(null, null, null, null, 500, null, null, 30, null));
		Rule rule = service.findApplicable("GET", "/").orElseThrow();
		assertTrue(rule.nextFault(() -> 30).isPresent());
		assertFalse(rule.nextFault(() -> 31).isPresent());
	}

	@Test
	void aDelayOnlyRuleNeverFails() {
		service.add(new RuleRequest(null, null, null, 10, null, null, null, null, null));
		Rule rule = service.findApplicable("GET", "/").orElseThrow();
		assertFalse(rule.nextFault(() -> 1).isPresent());
	}

}
