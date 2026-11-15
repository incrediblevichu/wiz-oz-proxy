package com.wizoz.proxy.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wizoz.proxy.model.RuleRequest;
import com.wizoz.proxy.model.RuleView;
import com.wizoz.proxy.model.Statistics;
import com.wizoz.proxy.service.ProxyService;
import com.wizoz.proxy.service.RuleService;

/** The management API, served under {@code /wizoz}. */
@RestController
@RequestMapping("/wizoz")
public class RulesController {

	private final RuleService rules;
	private final ProxyService proxyService;

	public RulesController(RuleService rules, ProxyService proxyService) {
		this.rules = rules;
		this.proxyService = proxyService;
	}

	@GetMapping({ "/rules", "/rules/" })
	public List<RuleView> list() {
		return rules.list();
	}

	@PostMapping({ "/rules", "/rules/" })
	@ResponseStatus(HttpStatus.CREATED)
	public RuleView add(@RequestBody RuleRequest request) {
		return rules.get(rules.add(request));
	}

	@GetMapping("/rules/{id}")
	public RuleView get(@PathVariable String id) {
		return rules.get(id);
	}

	@PutMapping("/rules/{id}")
	public RuleView replace(@PathVariable String id, @RequestBody RuleRequest request) {
		return rules.replace(id, request);
	}

	@DeleteMapping("/rules/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		rules.delete(id);
	}

	@DeleteMapping({ "/rules", "/rules/" })
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAll() {
		rules.deleteAll();
	}

	@PostMapping("/rules/{id}/enable")
	public RuleView enable(@PathVariable String id) {
		rules.setEnabled(id, true);
		return rules.get(id);
	}

	@PostMapping("/rules/{id}/disable")
	public RuleView disable(@PathVariable String id) {
		rules.setEnabled(id, false);
		return rules.get(id);
	}

	@PostMapping("/pause")
	public Map<String, Boolean> pause() {
		rules.setPaused(true);
		return Map.of("paused", true);
	}

	@PostMapping("/resume")
	public Map<String, Boolean> resume() {
		rules.setPaused(false);
		return Map.of("paused", false);
	}

	@GetMapping("/statistics")
	public Statistics statistics() {
		return proxyService.statistics();
	}

}
