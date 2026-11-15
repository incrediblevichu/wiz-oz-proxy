package com.wizoz.proxy.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.wizoz.proxy.service.InvalidRuleException;
import com.wizoz.proxy.service.RuleNotFoundException;

/** Management API errors, as JSON of the form {@code {"message": "..."}}. Proxied requests never reach this. */
@RestControllerAdvice(assignableTypes = RulesController.class)
public class ApiExceptionHandler {

	@ExceptionHandler(InvalidRuleException.class)
	public ResponseEntity<Map<String, String>> invalid(InvalidRuleException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "The request body is not valid JSON for a rule"));
	}

	@ExceptionHandler(RuleNotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(RuleNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
	}

}
