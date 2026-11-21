package com.greg.one2many.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// dashboard: the moderator corrects an attendee's name (same limits as the attendee's own form)
@Data
public class AdminRenameRequest {

    @NotBlank(message = "{profile.name.required}")
    @Size(max = 60, message = "{profile.name.tooLong}")
    private String name;
}
