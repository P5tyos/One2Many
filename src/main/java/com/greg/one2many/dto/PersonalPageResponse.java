package com.greg.one2many.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PersonalPageResponse {
    private boolean profileComplete; // false → show the form, true → show the list
    private String name;
    private String message;
}