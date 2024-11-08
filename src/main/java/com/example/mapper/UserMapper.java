package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.dao.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserMapper
 *
 * @author lijun.pan
 * @version 2024/11/06 11:14
 **/
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
