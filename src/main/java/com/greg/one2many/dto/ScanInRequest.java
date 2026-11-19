package com.greg.one2many.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ScanInRequest {

    @NotBlank(message = "{scan.linkedin.required}")
    @Pattern(
        regexp = "^https://(www\\.)?linkedin\\.com/in/.+$",
        message = "{scan.linkedin.invalid}"
    )
    private String linkedinUrl;
}
