package com.greg.one2many.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileDetailsRequest {

    @NotBlank(message = "{profile.name.required}")
    @Size(max = 60, message = "{profile.name.tooLong}")
    private String name;

    @Size(max = 100, message = "{profile.message.tooLong}")
    private String message;

    // the phone's instant filter (AllProfanity) warned about the name and they saved anyway.
    // Comes from the browser, so it can only ADD scrutiny (yellow row), never remove any.
    private boolean nameWarned;
}
