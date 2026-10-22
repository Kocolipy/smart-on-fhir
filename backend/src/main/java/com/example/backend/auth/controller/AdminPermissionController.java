package com.example.backend.auth.controller;

import com.example.backend.authorization.domain.Permission;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for the vocabulary a Group may carry: every
 * {@linkplain Permission#delegatable() delegatable} Permission, by name (ADR 0015).
 *
 * <p>One read and nothing else. The vocabulary is closed and lives in code, so this adapter
 * declares no write handler. Requires {@code group:read}, the Permission that reads what every
 * Group carries: this answers what any Group could.
 */
@RestController
@RequestMapping("/api/admin/permissions")
public class AdminPermissionController {

    @GetMapping
    @PreAuthorize("hasAuthority('group:read')")
    public List<String> listDelegatablePermissions() {
        return Permission.valuesOf(Permission.allDelegatable());
    }
}
