package com.yek.server.mapper;

import com.yek.server.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {
    void addUser(User user);
}
