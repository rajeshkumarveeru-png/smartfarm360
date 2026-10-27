package com.smartfarm360.platform.admin;

import com.smartfarm360.platform.ops.AnnouncementService;
import com.smartfarm360.platform.privacy.PrivacyService;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Platform administration (SaaS operations design, section 6). Only platform administrators who signed in with the
 * second step reach these endpoints (SecurityConfig, WebMvcConfig). They show counts and status, never farm books.
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {
    private final PlatformConsoleService console;
    private final AnnouncementService announcements;
    private final PrivacyService privacy;

    public PlatformAdminController(PlatformConsoleService console, AnnouncementService announcements, PrivacyService privacy) {
        this.console = console;
        this.announcements = announcements;
        this.privacy = privacy;
    }

    public record TenantStatusRequest(@NotNull Boolean active, @Size(max = 300) String reason) {}

    public record LimitRequest(@Min(0) @Max(1_000_000) Integer value, @Size(max = 300) String reason) {}

    public record UserStatusRequest(@NotNull Boolean active, @Size(max = 300) String reason) {}

    public record CheckRequest(@NotNull @Pattern(regexp = "BACKUP|RESTORE_TEST|LOAD_TEST") String kind, @NotNull Boolean ok,
                               @Size(max = 1000) String details, @Min(0) Long durationMs) {}

    public record AnnouncementRequest(@NotBlank @Size(max = 500) String messageEn, @NotBlank @Size(max = 500) String messageTa,
                                      Instant startsAt, @NotNull Instant endsAt, boolean maintenance) {}

    public record LegalText(@NotNull @Pattern(regexp = "en|ta") String language, @NotBlank @Size(max = 140) String title,
                            @NotBlank @Size(max = 100_000) String body) {}

    public record PublishRequest(@NotNull @Pattern(regexp = "TERMS|PRIVACY") String kind, @NotNull @Size(min = 2, max = 2) List<@Valid LegalText> texts,
                                 @Size(max = 1000) String changes) {}

    @GetMapping("/tenants")
    public PageResponse<PlatformConsoleService.PlatformTenant> tenants(@RequestParam(required = false) String q,
                                                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return console.tenants(q, page, size);
    }

    @GetMapping("/tenants/{id}")
    public PlatformConsoleService.TenantDetail tenant(@PathVariable long id) { return console.tenantDetail(id); }

    /** Suspends with a reason (shown to the business's members) or reactivates. */
    @PatchMapping("/tenants/{id}")
    public PlatformConsoleService.PlatformTenant setStatus(CurrentUser admin, @PathVariable long id, @Valid @RequestBody TenantStatusRequest request) {
        return console.setTenantStatus(admin, id, request.active(), request.reason());
    }

    /** Overrides one plan limit; a null value removes the override. */
    @PutMapping("/tenants/{id}/limits/{code}")
    public PlatformConsoleService.TenantDetail setLimit(CurrentUser admin, @PathVariable long id, @PathVariable String code, @Valid @RequestBody LimitRequest request) {
        return console.setLimit(admin, id, code, request.value(), request.reason());
    }

    @GetMapping("/users")
    public PageResponse<PlatformConsoleService.PlatformUser> users(@RequestParam(required = false) String q,
                                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return console.users(q, page, size);
    }

    /** Locks (signs out everywhere) or unlocks a user account. */
    @PatchMapping("/users/{id}")
    public PlatformConsoleService.PlatformUser setUser(CurrentUser admin, @PathVariable long id, @Valid @RequestBody UserStatusRequest request) {
        return console.setUserActive(admin, id, request.active(), request.reason());
    }

    @GetMapping("/ops")
    public PlatformConsoleService.Operations operations() { return console.operations(); }

    /** Records a backup, restore test or load test made outside the application. */
    @PostMapping("/ops/checks")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformConsoleService.Check recordCheck(CurrentUser admin, @Valid @RequestBody CheckRequest r) {
        return console.recordCheck(admin, r.kind(), r.ok(), r.details(), r.durationMs());
    }

    @PostMapping("/ops/test-alert")
    public PlatformConsoleService.Check testAlert(CurrentUser admin) { return console.testAlert(admin); }

    @GetMapping("/audit")
    public PageResponse<PlatformConsoleService.PlatformAuditEntry> audit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return console.auditLog(page, size);
    }

    @GetMapping("/announcements")
    public List<AnnouncementService.Announcement> announcements() { return announcements.all(); }

    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementService.Announcement announce(CurrentUser admin, @Valid @RequestBody AnnouncementRequest r) {
        return announcements.create(admin, new AnnouncementService.AnnouncementRequest(r.messageEn(), r.messageTa(), r.startsAt(), r.endsAt(), r.maintenance()));
    }

    @PostMapping("/announcements/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endAnnouncement(CurrentUser admin, @PathVariable long id) { announcements.end(admin, id); }

    @GetMapping("/legal-documents")
    public List<PrivacyService.LegalDocument> legalDocuments() { return privacy.allDocuments(); }

    /** Publishes a new version in English and Tamil; every user accepts it again before going on. */
    @PostMapping("/legal-documents")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PrivacyService.LegalDocument> publish(CurrentUser admin, @Valid @RequestBody PublishRequest r) {
        return privacy.publish(admin, new PrivacyService.PublishRequest(r.kind(),
                r.texts().stream().map(t -> new PrivacyService.LegalText(t.language(), t.title(), t.body())).toList(), r.changes()));
    }
}
