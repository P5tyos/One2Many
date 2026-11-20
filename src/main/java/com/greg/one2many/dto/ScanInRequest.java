package com.greg.one2many.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ScanInRequest {

    @NotBlank(message = "{scan.linkedin.required}")

    // STRICT (default): only personal profiles (/in/name) and LinkedIn QR tokens (/qr/TOKEN),
    // https only, www. or no prefix, a restricted set of characters.
    @Pattern(
        regexp = "^https://(www\\.)?linkedin\\.com/(in|qr)/[A-Za-z0-9_%\\-]+/?(\\?[^\\s#]*)?$",
        message = "{scan.linkedin.invalid}"
    )

    // RELAXED (event fallback): http or https (normalize() upgrades http to https),
    // any subdomain (www., es., m. ...), any path.
    // The DOMAIN is still checked, so a link can only ever point to linkedin.com:
    // never just "contains the word linkedin", or https://linkedin.evil-site.com would pass.
    // To switch: comment out the STRICT @Pattern above, uncomment the one below, restart the app.
    //
    // @Pattern(
    //     regexp = "^https?://([A-Za-z0-9-]+\\.)?linkedin\\.com/\\S+$",
    //     message = "{scan.linkedin.invalid}"
    // )

    private String linkedinUrl;
}
