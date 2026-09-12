package com.acedicearena.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 页面路由：把前端入口路径转发到对应的静态 html 页面。 */
@Controller
public class PageController {
    /** 玩家端页面。 */
    @GetMapping("/player")
    public String player() { return "forward:/player.html"; }

    /** 登录页。 */
    @GetMapping("/login")
    public String login() { return "forward:/login.html"; }

    /** 管理员端页面。 */
    @GetMapping("/admin")
    public String admin() { return "forward:/admin.html"; }

    /** 大厅页面。 */
    @GetMapping("/lobby")
    public String lobby() { return "forward:/lobby.html"; }

    /** 沙盘玩家端页面。 */
    @GetMapping("/sandbox-player")
    public String sandboxPlayer() { return "forward:/sandbox-player.html"; }
}
