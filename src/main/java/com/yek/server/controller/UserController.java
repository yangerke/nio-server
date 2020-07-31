package com.yek.server.controller;

import com.yek.server.entity.User;
import com.yek.server.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    @Autowired
    private UserService userService;

    @RequestMapping("/user/addUser")
    public String addUser(@Nullable @RequestParam("un") String userName, @Nullable@RequestParam("ua") Integer userAge) {
        if (userName == null || userAge == null)
            return "参数不全，请确认后重新添加！！！";
        User user = new User();
        user.setUserName(userName);
        user.setUserAge(userAge);
        userService.addUser(user);
        return "添加成功\n" + user.toString();
    }
}
