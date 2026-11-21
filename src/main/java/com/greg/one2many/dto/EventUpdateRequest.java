package com.greg.one2many.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

// dashboard: Greg edits the event details during the event (typo, organiser corrects something)
@Data
public class EventUpdateRequest {

    @Size(max = 80, message = "{event.field.tooLong}")
    private String name;

    @Size(max = 80, message = "{event.field.tooLong}")
    private String date;

    @Size(max = 120, message = "{event.field.tooLong}")
    private String location;

    @Size(max = 120, message = "{event.field.tooLong}")
    private String organizers;

    @Size(max = 300, message = "{event.field.tooLong}")
    private String description;

    // empty, or a real LinkedIn address (it is shown as a link on every phone)
    @Size(max = 300, message = "{event.field.tooLong}")
    @Pattern(regexp = "^$|^https://([A-Za-z0-9-]+\\.)?linkedin\\.com/\\S*$", message = "{event.linkedin.invalid}")
    private String linkedinUrl;
}
