package com.wizoz.proxy.model;

public record Statistics(
		long totalRequests,
		long totalForwardedRequests,
		long totalMockedRequests,
		long totalDelayedRequests,
		int totalRules,
		int activeRules,
		int inactiveRules,
		int activeDelays,
		int inactiveDelays,
		boolean paused,
		String dbWarning) {

	public Statistics(long totalRequests, long totalForwardedRequests, long totalMockedRequests,
			long totalDelayedRequests, int totalRules, int activeRules, int inactiveRules, int activeDelays,
			int inactiveDelays, boolean paused) {
		this(totalRequests, totalForwardedRequests, totalMockedRequests, totalDelayedRequests, totalRules, activeRules,
				inactiveRules, activeDelays, inactiveDelays, paused, null);
	}
}
