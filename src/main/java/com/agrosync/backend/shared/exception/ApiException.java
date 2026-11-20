package com.agrosync.backend.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA", "Selecciona una fecha de inicio de semana válida.");
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTENTICACION_REQUERIDA", "Inicia sesión en SuiteCRM.");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESO_DENEGADO", "La cuenta no tiene permiso para este recurso.");
    }

    public static ApiException conflictingFarmRoles() {
        return new ApiException(HttpStatus.CONFLICT, "PERFILES_FINCA_INCOMPATIBLES",
                "Hay varios perfiles AgroSync para esta finca. Corrige la asignación en SuiteCRM.");
    }

    public static ApiException conflictingLivestock() {
        return new ApiException(HttpStatus.CONFLICT, "GANADERIA_CONFLICTO",
                "SuiteCRM encontró un conflicto con el registro, su código o una referencia pendiente.");
    }

    public static ApiException invalidRecord() {
        return new ApiException(HttpStatus.BAD_REQUEST, "REGISTRO_INVALIDO", "Revisa los campos obligatorios.");
    }

    public static ApiException conflictingRecord(String code) {
        return switch (code) {
            case "VERSION_INCOMPATIBLE" -> new ApiException(HttpStatus.CONFLICT, code,
                    "El registro cambió en SuiteCRM. Revisa la versión central antes de reintentar.");
            case "CODIGO_DUPLICADO" -> new ApiException(HttpStatus.CONFLICT, code,
                    "El código ya está en uso. Elige otro código.");
            case "UNIDAD_INMUTABLE" -> new ApiException(HttpStatus.CONFLICT, code,
                    "La unidad de control se conserva para mantener el historial de existencias.");
            case "CONFIGURACION_ROL" -> new ApiException(HttpStatus.CONFLICT, code,
                    "Configura un único rol AgroSync Administrador de finca en SuiteCRM.");
            default -> new ApiException(HttpStatus.CONFLICT, "REGISTRO_CONFLICTIVO",
                    "SuiteCRM rechazó una operación incompatible con el registro.");
        };
    }

    public static ApiException conflictingInventory(String code) {
        return switch (code) {
            case "SALDO_INSUFICIENTE" -> new ApiException(HttpStatus.CONFLICT, code,
                    "El saldo confirmado es insuficiente para esta salida.");
            case "INSUMO_CAMBIADO" -> new ApiException(HttpStatus.CONFLICT, code,
                    "El insumo está inactivo o su unidad cambió.");
            case "COMPENSACION_INVALIDA" -> new ApiException(HttpStatus.CONFLICT, code,
                    "El movimiento de origen ya se compensó o no coincide.");
            default -> new ApiException(HttpStatus.CONFLICT, "INVENTARIO_CONFLICTO",
                    "SuiteCRM rechazó una operación incompatible con el inventario.");
        };
    }

    public static ApiException invalidMovement() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MOVIMIENTO_INVALIDO", "La cantidad debe ser positiva.");
    }

    public static ApiException conflictingHarvest() {
        return new ApiException(HttpStatus.CONFLICT, "COSECHA_CONFLICTIVA",
                "SuiteCRM encontró un conflicto con la operación o con los permisos de la finca.");
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException configuration() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CRM_SIN_CREDENCIALES",
                "La integración con SuiteCRM no está configurada.");
    }

    public static ApiException authentication() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CRM_AUTENTICACION",
                "SuiteCRM rechazó la autenticación de la integración.");
    }

    public static ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CRM_RESPUESTA_INVALIDA",
                "SuiteCRM devolvió una respuesta inesperada.");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CRM_NO_DISPONIBLE",
                "SuiteCRM no está disponible.");
    }

    public static ApiException transport(ResourceAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "CRM_TIEMPO_AGOTADO",
                        "SuiteCRM no respondió dentro del tiempo configurado.");
            }
        }
        return unavailable();
    }
}
