package com.wizoz.proxy.model;

/**
 * Requests that arrived in one time slice. {@code forwarded} counts every request that wasn't mocked.
 *
 * @param startMs start of the slice, in epoch milliseconds
 */
public record TimelinePoint(long startMs, int forwarded, int mocked) {
}
