package com.wizoz.proxy.model;

/**
 * One request that went through the proxy, with the answer it got.
 *
 * @param id            1 for the first request since the proxy started, then counting up
 * @param timeMs        when the request arrived, in epoch milliseconds
 * @param query         the raw query string, or null
 * @param clientAddress the address the connection came from
 * @param origin        the Origin header, or null
 * @param mocked        true when a rule answered with a mocked response, false when the backend's own answer was relayed
 *                      (or the proxy couldn't get one, which is answered 502 or 504)
 * @param ruleId        the rule that matched this request, whether or not it mocked the answer; null if none did
 * @param delayMs       how long a rule held the request before answering or forwarding it
 * @param status        the status sent to the client
 * @param durationMs    from arrival until the answer was ready, including any delay
 */
public record RequestRecord(
		long id,
		long timeMs,
		String method,
		String path,
		String query,
		String clientAddress,
		String origin,
		Payload request,
		boolean mocked,
		String ruleId,
		int delayMs,
		int status,
		Payload response,
		long durationMs) {

	public RequestRecord withId(long newId) {
		return new RequestRecord(newId, timeMs, method, path, query, clientAddress, origin, request, mocked, ruleId, delayMs,
				status, response, durationMs);
	}

}
