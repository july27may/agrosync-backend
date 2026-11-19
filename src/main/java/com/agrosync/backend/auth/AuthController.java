package com.agrosync.backend.auth;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuthController.class);
    private static final String FLOW = "AGROSYNC_OAUTH_FLOW";
    private final FarmAccess access;
    private final SuiteCrmIdentity crm;
    private final URI frontend;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration offlineTtl;
    private final KeyPair keys;

    AuthController(FarmAccess access, SuiteCrmIdentity crm, JsonMapper json, Clock clock,
                   @Value("${AGROSYNC_FRONTEND_ORIGIN:http://127.0.0.1:5173}") URI frontend,
                   @Value("${agrosync.offline-ttl}") Duration offlineTtl)
            throws GeneralSecurityException {
        this.access = access;
        this.crm = crm;
        this.frontend = frontend;
        this.json = json;
        this.clock = clock;
        if (offlineTtl == null || offlineTtl.isNegative() || offlineTtl.toMillis() < 1000) {
            throw new IllegalArgumentException("AGROSYNC_OFFLINE_TTL debe ser de al menos un segundo");
        }
        this.offlineTtl = offlineTtl;
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
    }

    record Grant(FarmAccess.User user, String deviceId, long issuedAt, long expiresAt) {}
    record SignedGrant(String payload, String signature, String publicKey) {}

    @GetMapping("/csrf")
    ResponseEntity<CsrfToken> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(token);
    }

    @GetMapping("/login")
    ResponseEntity<Void> login(HttpServletRequest request) {
        var flow = crm.newFlow();
        request.getSession().setAttribute(FLOW, flow);
        return redirect(crm.authorizationUrl(flow));
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                  @RequestParam(required = false) String code,
                                  HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null) {
            LOG.info("AgroSync OAuth: retorno sin sesión previa");
            return redirect(frontend.resolve("/?login=sesion"));
        }
        SuiteCrmIdentity.Flow flow;
        synchronized (session) {
            flow = (SuiteCrmIdentity.Flow) session.getAttribute(FLOW);
            session.removeAttribute(FLOW);
        }
        try {
            var identity = crm.finish(flow, state, code);
            var authentication = UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            return redirect(frontend.resolve("/"));
        } catch (ApiException failure) {
            LOG.info("AgroSync OAuth: identidad no confirmada ({})", failure.code());
            return redirect(frontend.resolve(failure.status().is5xxServerError() ? "/?login=proveedor" : "/?login=error"));
        }
    }

    private ResponseEntity<Void> redirect(URI destination) {
        return ResponseEntity.status(303).location(destination).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/me")
    FarmAccess.User me(Principal principal) {
        var user = access.current(principal);
        LOG.info("AgroSync sesión verificada: fincas={}, conflictos={}", user.permisos().size(), user.conflictos().size());
        return user;
    }

    @PostMapping("/offline")
    SignedGrant authorizeOffline(Principal principal, @RequestParam @Pattern(regexp = "[0-9a-f-]{36}") String deviceId)
            throws GeneralSecurityException {
        var user = access.current(principal);
        if (user.permisos().isEmpty()) throw ApiException.forbidden();
        var time = clock.instant();
        byte[] payload = json.writeValueAsBytes(new Grant(user, deviceId, time.toEpochMilli(), time.plus(offlineTtl).toEpochMilli()));
        var signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(keys.getPrivate());
        signature.update(payload);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return new SignedGrant(encoder.encodeToString(payload), encoder.encodeToString(signature.sign()),
                encoder.encodeToString(keys.getPublic().getEncoded()));
    }
}
