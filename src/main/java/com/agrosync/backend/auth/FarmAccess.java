package com.agrosync.backend.auth;

import com.agrosync.backend.shared.exception.ApiException;
import org.springframework.stereotype.Component;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class FarmAccess {
    public enum Role { ADMINISTRADOR_FINCA, REGISTRADOR, CONSULTA }
    public record Conflict(String id, String nombre, List<Role> roles) {}
    public record User(String id, String username, Map<String, Role> permisos, List<Conflict> conflictos) {
        public void requireFarm(String id) {
            if (conflictos.stream().anyMatch(conflict -> conflict.id().equals(id))) throw ApiException.conflictingFarmRoles();
            if (!permisos.containsKey(id)) throw ApiException.forbidden();
        }
    }

    private final SuiteCrmIdentity crm;
    public FarmAccess(SuiteCrmIdentity crm) { this.crm = crm; }

    public User current(Principal principal) {
        var identity = crm.identity(principal);
        var document = crm.get(principal, uri -> uri.path("/Api/V8/custom/agrosync/access").build());
        var data = document.path("fincas");
        if (!identity.id().equals(document.path("id").stringValue(null)) || !data.isArray()) {
            throw ApiException.invalidResponse();
        }
        var permissions = new TreeMap<String, Role>();
        for (var item : data) {
            var id = item.path("id").stringValue(null);
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || permissions.containsKey(id)) {
                throw ApiException.invalidResponse();
            }
            try {
                permissions.put(id, Role.valueOf(item.path("role").stringValue("")));
            } catch (IllegalArgumentException malformed) {
                throw ApiException.invalidResponse();
            }
        }
        var conflicts = new TreeMap<String, Conflict>();
        var reported = document.path("conflictos");
        if (!reported.isMissingNode() && !reported.isArray()) throw ApiException.invalidResponse();
        for (var entry : reported) {
            var id = entry.path("id").stringValue("");
            var roles = new java.util.TreeSet<Role>();
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !entry.path("roles").isArray()) throw ApiException.invalidResponse();
            for (var role : entry.path("roles")) {
                try { roles.add(Role.valueOf(role.stringValue(""))); }
                catch (IllegalArgumentException malformed) { throw ApiException.invalidResponse(); }
            }
            if (roles.size() < 2 || conflicts.containsKey(id)) throw ApiException.invalidResponse();
            permissions.remove(id);
            conflicts.put(id, new Conflict(id, entry.path("nombre").stringValue(id), List.copyOf(roles)));
        }
        return new User(identity.id(), identity.username(), Map.copyOf(permissions), List.copyOf(conflicts.values()));
    }

    public List<String> farms(Principal principal) {
        return current(principal).permisos().keySet().stream().sorted().toList();
    }

    public void requireFarm(Principal principal, String id) {
        current(principal).requireFarm(id);
    }

    public void requireAssignment(Principal principal) {
        if (current(principal).permisos().isEmpty()) throw ApiException.forbidden();
    }
}
