package com.wizoz.proxy.service.impl;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.wizoz.proxy.model.RequestRecord;
import com.wizoz.proxy.model.TimelinePoint;
import com.wizoz.proxy.service.TrafficService;

@Service
public class TrafficServiceImpl implements TrafficService {

	private final Clock clock;
	private final Deque<RequestRecord> recent = new ArrayDeque<>();
	private final TreeMap<Long, int[]> slices = new TreeMap<>();
	private long lastId;

	public TrafficServiceImpl(Clock clock) {
		this.clock = clock;
	}

	@Override
	public synchronized void record(RequestRecord record) {
		recent.addFirst(record.withId(++lastId));
		if (recent.size() > CAPACITY) {
			recent.removeLast();
		}
		long start = sliceStart(record.timeMs());
		if (start >= oldestSliceStart()) {
			slices.computeIfAbsent(start, k -> new int[2])[record.mocked() ? 1 : 0]++;
		}
		slices.headMap(oldestSliceStart()).clear();
	}

	@Override
	public synchronized List<RequestRecord> recent() {
		return List.copyOf(recent);
	}

	@Override
	public synchronized List<TimelinePoint> timeline() {
		long first = oldestSliceStart();
		List<TimelinePoint> points = new ArrayList<>(SLICES);
		for (int i = 0; i < SLICES; i++) {
			long start = first + i * SLICE_MS;
			int[] counts = slices.getOrDefault(start, new int[2]);
			points.add(new TimelinePoint(start, counts[0], counts[1]));
		}
		return points;
	}

	@Override
	public synchronized void clear() {
		recent.clear();
	}

	private long oldestSliceStart() {
		return sliceStart(clock.millis()) - (SLICES - 1) * SLICE_MS;
	}

	private static long sliceStart(long timeMs) {
		return Math.floorDiv(timeMs, SLICE_MS) * SLICE_MS;
	}

}
