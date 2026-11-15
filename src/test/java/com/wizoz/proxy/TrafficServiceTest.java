package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wizoz.proxy.model.Payload;
import com.wizoz.proxy.model.RequestRecord;
import com.wizoz.proxy.model.TimelinePoint;
import com.wizoz.proxy.service.TrafficService;
import com.wizoz.proxy.service.impl.TrafficServiceImpl;

class TrafficServiceTest {

	private static final long NOW = 1_700_000_000_000L - (1_700_000_000_000L % TrafficService.SLICE_MS);

	private static class MutableClock extends Clock {
		long now = NOW;

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.ofEpochMilli(now);
		}
	}

	private final MutableClock clock = new MutableClock();
	private final TrafficService traffic = new TrafficServiceImpl(clock);

	private static RequestRecord request(String path, long timeMs, boolean mocked) {
		return new RequestRecord(0, timeMs, "GET", path, null, "127.0.0.1", null, new Payload(null, "", false, 0), mocked, null, 0,
				mocked ? 503 : 200, new Payload(null, "", false, 0), 1);
	}

	@Test
	void keepsTheNewestRequestsFirstAndNumbersThemFromOne() {
		traffic.record(request("/a", NOW, false));
		traffic.record(request("/b", NOW, true));
		List<RequestRecord> recent = traffic.recent();
		assertEquals(List.of("/b", "/a"), recent.stream().map(RequestRecord::path).toList());
		assertEquals(List.of(2L, 1L), recent.stream().map(RequestRecord::id).toList());
	}

	@Test
	void keepsOnlyTheLastHundred() {
		for (int i = 1; i <= 130; i++) {
			traffic.record(request("/" + i, NOW, false));
		}
		List<RequestRecord> recent = traffic.recent();
		assertEquals(TrafficService.CAPACITY, recent.size());
		assertEquals(130, recent.get(0).id());
		assertEquals(31, recent.get(99).id());
	}

	@Test
	void clearForgetsTheRequestsButKeepsCountingIds() {
		traffic.record(request("/a", NOW, false));
		traffic.clear();
		assertTrue(traffic.recent().isEmpty());
		traffic.record(request("/b", NOW, false));
		assertEquals(2, traffic.recent().get(0).id());
	}

	@Test
	void clearKeepsTheTimeline() {
		traffic.record(request("/a", NOW, false));
		traffic.clear();
		assertEquals(1, traffic.timeline().get(TrafficService.SLICES - 1).forwarded());
	}

	@Test
	void timelineAlwaysHasTheSameNumberOfSlicesEndingWithTheCurrentOne() {
		List<TimelinePoint> points = traffic.timeline();
		assertEquals(TrafficService.SLICES, points.size());
		assertEquals(NOW, points.get(points.size() - 1).startMs());
		assertEquals(NOW - (TrafficService.SLICES - 1) * TrafficService.SLICE_MS, points.get(0).startMs());
		for (TimelinePoint point : points) {
			assertEquals(0, point.forwarded() + point.mocked());
		}
	}

	@Test
	void timelineSeparatesForwardedFromMockedAndGroupsBySlice() {
		traffic.record(request("/a", NOW, false));
		traffic.record(request("/b", NOW + 4_999, false));
		traffic.record(request("/c", NOW, true));
		traffic.record(request("/d", NOW - TrafficService.SLICE_MS, true));
		List<TimelinePoint> points = traffic.timeline();
		TimelinePoint current = points.get(TrafficService.SLICES - 1);
		TimelinePoint previous = points.get(TrafficService.SLICES - 2);
		assertEquals(2, current.forwarded());
		assertEquals(1, current.mocked());
		assertEquals(0, previous.forwarded());
		assertEquals(1, previous.mocked());
	}

	@Test
	void timelineSlidesAsTimePassesAndDropsOldCalls() {
		traffic.record(request("/a", NOW, false));
		clock.now = NOW + TrafficService.SLICE_MS;
		List<TimelinePoint> points = traffic.timeline();
		assertEquals(1, points.get(TrafficService.SLICES - 2).forwarded());
		assertEquals(0, points.get(TrafficService.SLICES - 1).forwarded());

		clock.now = NOW + TrafficService.SLICES * TrafficService.SLICE_MS;
		assertTrue(traffic.timeline().stream().allMatch(p -> p.forwarded() == 0 && p.mocked() == 0));
		assertEquals(1, traffic.recent().size());
	}

	@Test
	void aCallFromBeforeTheWindowIsNotCountedInTheTimeline() {
		traffic.record(request("/old", NOW - TrafficService.SLICES * TrafficService.SLICE_MS, false));
		assertTrue(traffic.timeline().stream().allMatch(p -> p.forwarded() == 0));
		assertEquals(1, traffic.recent().size());
	}

}
