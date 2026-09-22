package com.quant.finance.decision.controller;

import com.quant.finance.decision.autotrade.ExecutionEngineException;
import com.quant.finance.decision.error.JobConflictException;
import com.quant.finance.decision.error.AlpacaApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** Another backtest is running: 409 with that job so the caller can attach to it instead. */
    @ExceptionHandler(JobConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(JobConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "activeJob", e.active()));
    }

    /** Alpaca refused or could not be reached. */
    @ExceptionHandler(AlpacaApiException.class)
    public ResponseEntity<Map<String, String>> alpaca(AlpacaApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Alpaca: " + e.getMessage()));
    }

    /** ExecutionEngine refused a command or could not be reached. */
    @ExceptionHandler(ExecutionEngineException.class)
    public ResponseEntity<Map<String, String>> executionEngine(ExecutionEngineException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "ExecutionEngine: " + e.getMessage()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, String>> notImplemented(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("error", e.getMessage()));
    }
}
