package com.yek.server.service;

import com.yek.server.entity.User;
import com.yek.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Autowired
    UserMapper userMapper;

    public void addUser(User user) {
        userMapper.addUser(user);
    }
}
