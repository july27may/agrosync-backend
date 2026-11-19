package com.agrosync.backend.shared.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiErrorHandler extends ResponseEntityExceptionHandler {
    private record Body(String code, String message) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Body> integration(ApiException failure) {
        return ResponseEntity.status(failure.status()).body(new Body(failure.code(), failure.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body,
                                                              HttpHeaders headers, HttpStatusCode status,
                                                              WebRequest request) {
        Body error = switch (status.value()) {
            case 400 -> new Body("SOLICITUD_INVALIDA", "Revisa los parámetros de la solicitud.");
            case 404 -> new Body("RUTA_NO_ENCONTRADA", "La ruta solicitada no existe.");
            case 405 -> new Body("METODO_NO_PERMITIDO", "El método solicitado no está permitido.");
            default -> new Body("SOLICITUD_NO_PROCESADA", "No se pudo procesar la solicitud.");
        };
        return new ResponseEntity<>(error, headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Body> unexpected() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Body("ERROR_INTERNO", "No se pudo procesar la solicitud."));
    }
}
