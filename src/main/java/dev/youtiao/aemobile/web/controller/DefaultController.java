package dev.youtiao.aemobile.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DefaultController {
    @GetMapping("/AEMobileTest")
    public String aemobileTest() {
        return "<h1>hello world</h1>";
    }
}
