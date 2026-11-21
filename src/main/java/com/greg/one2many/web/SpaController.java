package com.greg.one2many.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// When Spring serves the built Vue app (event mode), typing /station or opening /p/abc...
// must return Vue's index.html; Vue's router then shows the right screen.
// Explicit list on purpose: any other unknown address still gets a plain 404.
@Controller
public class SpaController {

    @GetMapping({"/station", "/dashboard", "/about", "/p/{token}"})
    public String index() {
        return "forward:/index.html";
    }

    // the bare tunnel address = Greg opening the app: straight to the dashboard
    // (not logged in yet → Spring Security shows the login page first)
    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }
}
