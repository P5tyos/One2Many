package com.greg.one2many.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ScanInRequest {

    @NotBlank(message = "{scan.linkedin.required}")

    // Only checks that the link really goes to LinkedIn (www., es., m. or no prefix; http is
    // upgraded to https by normalize()). Anything after the domain is accepted: names with
    // accents (José Estévez, raw or as %C3%A9), pages (/company/...), events, future formats.
    // Event lesson (25 Nov 2026): a stricter rule (a-z, 0-9, _ % - only) refused real QR codes.
    // The DOMAIN check is what keeps people safe: https://linkedin.evil-site.com never passes.
    @Pattern(
        regexp = "^(?i:https?://([a-z0-9-]+\\.)?linkedin\\.com/)\\S+$", // scheme + domain in any case; the path keeps its case
        message = "{scan.linkedin.invalid}"
    )
    // A link copied from a browser with LinkedIn's tracking part was ~170 characters at the event;
    // 500 leaves room for that and still stops 6,000-character links that would stretch the dashboard.
    @Size(max = 500, message = "{scan.linkedin.invalid}")
    private String linkedinUrl;
}
