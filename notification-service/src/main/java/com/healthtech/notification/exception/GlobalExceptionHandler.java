package com.healthtech.notification.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.springframework.http.ProblemDetail.forStatusAndDetail;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex){
        log.error("Unexpected error", ex);
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred");
        problemDetail.setTitle("Internal Server Error");
        return problemDetail;
    }
}
