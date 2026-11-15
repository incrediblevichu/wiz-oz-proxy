package com.wizoz.proxy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deterministic sequence of outcomes, answering "what happens to the Nth matching request".
 *
 * <p>The text is a comma separated list of runs, each {@code S##count} (success: forward) or
 * {@code F#status#count} (failure: answer with that status). {@code S##2,F#503#3,S##1} means: forward two requests,
 * fail the next three with 503, then forward. Once the sequence is used up its last run repeats forever.
 *
 * <p>Runs are stored as counts, so {@code F#500#2000000000} costs no memory.
 */
public final class ErrorPattern {

	public static final int SUCCESS = -1;

	private static final ErrorPattern EMPTY = new ErrorPattern(List.of(), List.of());

	private final List<Integer> statuses;
	private final List<Long> runEnds;
	private final long length;
	private final AtomicLong position = new AtomicLong();

	private ErrorPattern(List<Integer> statuses, List<Long> runEnds) {
		this.statuses = statuses;
		this.runEnds = runEnds;
		this.length = runEnds.isEmpty() ? 0 : runEnds.get(runEnds.size() - 1);
	}

	/**
	 * @throws IllegalArgumentException if the text is not a valid pattern
	 */
	public static ErrorPattern parse(String text) {
		if (text == null || text.isBlank()) {
			return EMPTY.copy();
		}
		List<Integer> statuses = new ArrayList<>();
		List<Long> runEnds = new ArrayList<>();
		long total = 0;
		for (String run : text.split(",", -1)) {
			String[] parts = run.trim().split("#", -1);
			if (parts.length != 3) {
				throw new IllegalArgumentException("Invalid run '" + run.trim() + "', expected S##count or F#status#count");
			}
			int status = switch (parts[0]) {
				case "S" -> SUCCESS;
				case "F" -> parseNumber(parts[1], "status", run);
				default -> throw new IllegalArgumentException("Invalid run '" + run.trim() + "', it must start with S or F");
			};
			if (status != SUCCESS && (status < 100 || status > 599)) {
				throw new IllegalArgumentException("Invalid status " + status + " in '" + run.trim() + "', expected 100-599");
			}
			int count = parseNumber(parts[2], "count", run);
			if (count < 1) {
				throw new IllegalArgumentException("Invalid count in '" + run.trim() + "', it must be at least 1");
			}
			total += count;
			statuses.add(status);
			runEnds.add(total);
		}
		return new ErrorPattern(statuses, runEnds);
	}

	private static int parseNumber(String value, String what, String run) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid " + what + " '" + value + "' in '" + run.trim() + "'");
		}
	}

	public boolean isEmpty() {
		return statuses.isEmpty();
	}

	/**
	 * Takes the next outcome off the sequence. Safe to call from many threads: every caller gets a distinct position.
	 *
	 * @return the status to fail with, or {@link #SUCCESS}
	 */
	public int next() {
		if (isEmpty()) {
			return SUCCESS;
		}
		long index = position.getAndUpdate(p -> p < length - 1 ? p + 1 : p);
		for (int run = 0; run < runEnds.size(); run++) {
			if (index < runEnds.get(run)) {
				return statuses.get(run);
			}
		}
		throw new IllegalStateException("position " + index + " is past the end of the pattern");
	}

	/** Starts the sequence over. */
	public void reset() {
		position.set(0);
	}

	private ErrorPattern copy() {
		return new ErrorPattern(statuses, runEnds);
	}

}
