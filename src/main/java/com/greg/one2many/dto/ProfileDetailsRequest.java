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
}
