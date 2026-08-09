package com.healthtech.appointment.exception;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.ProblemDetail.forStatusAndDetail;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@Nonnull MethodArgumentNotValidException ex, @Nonnull HttpHeaders headers,
                                                                  @Nonnull HttpStatusCode status, @Nonnull WebRequest request) {
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problemDetail.setTitle("Validation Error");
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError -> errors.put(fieldError.getField(), fieldError.getDefaultMessage()));
        problemDetail.setProperty("errors", errors);
        log.warn("Validation failed: {} field error(s), status 400", errors.size());
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred");
        problemDetail.setTitle("Internal Server Error");
        return problemDetail;
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ProblemDetail handlePatientNotFoundException(PatientNotFoundException ex) {
        log.warn("{}, status 404", ex.getMessage());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Patient Not Found");
        return problemDetail;
    }

    @ExceptionHandler(DoctorNotFoundException.class)
    public ProblemDetail handleDoctorNotFoundException(DoctorNotFoundException ex) {
        log.warn("{}, status 404", ex.getMessage());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Doctor Not Found");
        return problemDetail;
    }

    @ExceptionHandler(SlotNotAlignedException.class)
    public ProblemDetail handleSlotNotAlignedException(SlotNotAlignedException ex) {
        log.warn("{}, status 400", ex.getMessage());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Slot Not Aligned");
        return problemDetail;
    }

    @ExceptionHandler(OutsideOpeningHoursException.class)
    public ProblemDetail handleOutsideOpeningHoursException(OutsideOpeningHoursException ex) {
        log.warn("{}, status 400", ex.getMessage());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problemDetail.setTitle("Outside Opening Hours");
        return problemDetail;
    }

    @ExceptionHandler(SlotAlreadyBookedException.class)
    public ProblemDetail handleSlotAlreadyBookedException(SlotAlreadyBookedException ex) {
        log.warn("Double-book conflict for doctorId {} at slot {}, status 409", ex.getDoctorId(), ex.getSlot());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Slot Already Booked");
        return problemDetail;
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ProblemDetail handleAppointmentNotFoundException(AppointmentNotFoundException ex) {
        log.warn("{}, status 404", ex.getMessage());
        ProblemDetail problemDetail = forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Appointment Not Found");
        return problemDetail;
    }

    @ExceptionHandler(AppointmentAccessDeniedException.class)
    public ProblemDetail handleAppointmentAccessDeniedException(AppointmentAccessDeniedException ex) {
        log.warn("{}, status 403", ex.getMessage());
        ProblemDetail pd = forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setTitle("Not Resource Owner");
        return pd;
    }
}
