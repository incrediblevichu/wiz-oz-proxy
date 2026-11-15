package com.wizoz.proxy.service;

import java.util.List;

import com.wizoz.proxy.model.RequestRecord;
import com.wizoz.proxy.model.TimelinePoint;

/** Remembers what went through the proxy: the last {@value #CAPACITY} requests, and call counts over recent minutes. */
public interface TrafficService {

	int CAPACITY = 100;

	/** Length of one slice of the timeline. */
	long SLICE_MS = 5_000;

	/** Number of slices in the timeline, so it covers the last five minutes. */
	int SLICES = 60;

	/** Stores a request, giving it the next id. */
	void record(RequestRecord record);

	/** @return the stored requests, newest first */
	List<RequestRecord> recent();

	/** @return exactly {@value #SLICES} slices, oldest first, the last one being the current one */
	List<TimelinePoint> timeline();

	/** Forgets the stored requests. The timeline is kept. */
	void clear();

}
