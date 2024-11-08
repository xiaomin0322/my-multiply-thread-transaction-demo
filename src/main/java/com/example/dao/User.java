package com.example.dao;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * User
 *
 * @author lijun.pan
 * @version 2024/11/06 11:10
 **/
@Data
@TableName("user")
public class User {
    private Integer id;
    private String name;
    private Integer age;
}
