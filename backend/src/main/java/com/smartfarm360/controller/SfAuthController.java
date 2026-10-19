package com.smartfarm360.controller;

import com.smartfarm360.entity.SfUserAccount;
import com.smartfarm360.repository.SfUserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class SfAuthController {
    private final SfUserAccountRepository users;
    private final PasswordEncoder passwords;
    public SfAuthController(SfUserAccountRepository users, PasswordEncoder passwords) { this.users = users; this.passwords = passwords; }

    public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
                                  @NotBlank @Size(min=10, max=72) String password,
                                  @NotBlank @Size(max=140) String displayName) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@Valid @RequestBody RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        SfUserAccount user = new SfUserAccount();
        user.setEmail(email); user.setDisplayName(request.displayName().trim()); user.setPasswordHash(passwords.encode(request.password()));
        users.save(user);
        return Map.of("email", user.getEmail(), "displayName", user.getDisplayName());
    }

    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth) {
        SfUserAccount user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return Map.of("email", user.getEmail(), "displayName", user.getDisplayName());
    }
}
