package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.wizoz.proxy.model.ErrorPattern;

class ErrorPatternTest {

	private static final int OK = ErrorPattern.SUCCESS;

	private static List<Integer> take(ErrorPattern pattern, int count) {
		List<Integer> outcomes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			outcomes.add(pattern.next());
		}
		return outcomes;
	}

	@Test
	void followsTheSequenceThenRepeatsTheLastRun() {
		ErrorPattern pattern = ErrorPattern.parse("S##2,F#503#3,S##1");
		assertEquals(List.of(OK, OK, 503, 503, 503, OK, OK, OK, OK), take(pattern, 9));
	}

	@Test
	void aSequenceEndingInFailureKeepsFailing() {
		ErrorPattern pattern = ErrorPattern.parse("S##1,F#500#1");
		assertEquals(List.of(OK, 500, 500, 500), take(pattern, 4));
	}

	@Test
	void differentFailureStatusesAreKeptInOrder() {
		ErrorPattern pattern = ErrorPattern.parse("F#401#1, F#429#2 ,S##1");
		assertEquals(List.of(401, 429, 429, OK), take(pattern, 4));
	}

	@Test
	void resetStartsTheSequenceOver() {
		ErrorPattern pattern = ErrorPattern.parse("S##1,F#500#1");
		take(pattern, 3);
		pattern.reset();
		assertEquals(List.of(OK, 500), take(pattern, 2));
	}

	@Test
	void blankPatternIsEmptyAndAlwaysSucceeds() {
		for (String text : new String[] { null, "", "   " }) {
			ErrorPattern pattern = ErrorPattern.parse(text);
			assertTrue(pattern.isEmpty());
			assertEquals(OK, pattern.next());
		}
	}

	@Test
	void hugeCountsCostNoMemory() {
		ErrorPattern pattern = ErrorPattern.parse("S##2000000000,F#500#1");
		assertEquals(List.of(OK, OK), take(pattern, 2));
	}

	@Test
	void invalidPatternsAreRejected() {
		for (String text : new String[] { "X#500#1", "F#500", "F#abc#1", "F#500#x", "F#500#0", "F#500#-1", "F#99#1", "F#600#1",
				"S##1,,S##1", "F##1", "S##1,", "F#500#1#1" }) {
			assertThrows(IllegalArgumentException.class, () -> ErrorPattern.parse(text), text);
		}
	}

	@Test
	void concurrentCallersEachGetADistinctPosition() throws Exception {
		ErrorPattern pattern = ErrorPattern.parse("S##10,F#503#3,S##1");
		ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			List<Future<Integer>> outcomes = new ArrayList<>();
			for (int i = 0; i < 14; i++) {
				outcomes.add(executor.submit(pattern::next));
			}
			int failures = 0;
			for (Future<Integer> outcome : outcomes) {
				if (outcome.get(10, TimeUnit.SECONDS) == 503) {
					failures++;
				}
			}
			assertEquals(3, failures);
		} finally {
			executor.shutdownNow();
		}
	}

}
