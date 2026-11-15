package com.wizoz.proxy.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wizoz.proxy.model.RequestRecord;
import com.wizoz.proxy.model.TimelinePoint;
import com.wizoz.proxy.service.TrafficService;

/** What went through the proxy. The management page and API are never listed, only proxied requests. */
@RestController
@RequestMapping("/wizoz")
public class TrafficController {

	private final TrafficService traffic;

	public TrafficController(TrafficService traffic) {
		this.traffic = traffic;
	}

	@GetMapping({ "/requests", "/requests/" })
	public List<RequestRecord> recent() {
		return traffic.recent();
	}

	@DeleteMapping({ "/requests", "/requests/" })
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void clear() {
		traffic.clear();
	}

	@GetMapping({ "/statistics/timeline", "/statistics/timeline/" })
	public List<TimelinePoint> timeline() {
		return traffic.timeline();
	}

}
