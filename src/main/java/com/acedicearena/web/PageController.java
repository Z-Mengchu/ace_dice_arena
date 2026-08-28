package com.acedicearena.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping("/player")
    public String player() { return "forward:/player.html"; }

    @GetMapping("/login")
    public String login() { return "forward:/login.html"; }

    @GetMapping("/admin")
    public String admin() { return "forward:/admin.html"; }

    @GetMapping("/lobby")
    public String lobby() { return "forward:/lobby.html"; }

    @GetMapping("/sandbox-player")
    public String sandboxPlayer() { return "forward:/sandbox-player.html"; }
}
