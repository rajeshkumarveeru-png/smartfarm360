package com.smartfarm360.platform.privacy;

import com.smartfarm360.platform.ops.AnnouncementService;
import com.smartfarm360.platform.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The person's own privacy (SaaS operations design, section 11, O11, O12), plus what every app reads before sign-in:
 * the current terms and privacy notice, and the announcements.
 */
@RestController
@RequestMapping("/api/v1")
public class PrivacyController {
    private final PrivacyService privacy;
    private final AnnouncementService announcements;

    public PrivacyController(PrivacyService privacy, AnnouncementService announcements) {
        this.privacy = privacy;
        this.announcements = announcements;
    }

    public record AcceptRequest(@NotNull @Size(min = 1, max = 4) List<@Pattern(regexp = "(TERMS|PRIVACY):\\d{1,4}") String> documents,
                                @Pattern(regexp = "WEB|MOBILE|API") String client) {}

    public record MarketingRequest(@NotNull Boolean optIn, @Pattern(regexp = "WEB|MOBILE|API") String client) {}

    public record DeleteAccountRequest(@NotBlank @Size(max = 200) String password) {}

    @GetMapping("/legal/current")
    public List<PrivacyService.LegalDocument> legal(@RequestParam(defaultValue = "en") String language) { return privacy.current(language); }

    @GetMapping("/announcements")
    public List<AnnouncementService.Announcement> announcements() { return announcements.active(); }

    @GetMapping("/me/consents")
    public List<PrivacyService.Consent> consents(CurrentUser user) { return privacy.consents(user.id()); }

    @PostMapping("/me/consents")
    public List<PrivacyService.Consent> accept(CurrentUser user, @Valid @RequestBody AcceptRequest request) {
        return privacy.accept(user, request.documents(), request.client());
    }

    @PutMapping("/me/marketing")
    public List<PrivacyService.Consent> marketing(CurrentUser user, @Valid @RequestBody MarketingRequest request) {
        return privacy.setMarketing(user, request.optIn(), request.client());
    }

    /** A copy of the personal data the platform keeps about the user, as a JSON download. */
    @GetMapping("/me/data")
    public ResponseEntity<PrivacyService.PersonalData> personalData(CurrentUser user) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"smartfarm360-my-data.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(privacy.personalData(user));
    }

    /** Deletes the account (O11). The client signs out afterwards. */
    @PostMapping("/me/deletion")
    public ResponseEntity<Void> deleteAccount(CurrentUser user, @Valid @RequestBody DeleteAccountRequest request) {
        privacy.deleteAccount(user, request.password());
        return ResponseEntity.noContent().build();
    }
}
