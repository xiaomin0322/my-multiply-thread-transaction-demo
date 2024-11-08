package com.example.controller;

import com.example.dao.User;
import com.example.mapper.UserMapper;
import com.example.util.MultiplyThreadTransactionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * TestController
 *
 * @author lijun.pan
 * @version 2024/11/06 11:12
 **/
@Slf4j
@RestController
@RequestMapping("test")
public class TestController {

    @Resource
    private UserMapper userMapper;

    @Resource(name = "multiplyThreadTransactionExecutor")
    private ThreadPoolExecutor multiplyThreadTransactionExecutor;

    @Resource
    private MultiplyThreadTransactionManager multiplyThreadTransactionManager;

    @RequestMapping("transaction")
    public String transaction() {
        List<Runnable> tasks = new ArrayList<>();

        tasks.add(() -> updateUserName(1, "lijun.pan"));

        tasks.add(() -> {
            updateUserAge(2, 23);
//            throw new RuntimeException("update user age error");
        });

        multiplyThreadTransactionManager.execute(tasks, multiplyThreadTransactionExecutor);
        return "success";
    }

    @RequestMapping("updateName")
    @Transactional(rollbackFor = Exception.class)
    public String updateName() {
        updateUserName(1, "lijun.pan");
        return "success";
    }

    public void updateUserName(Integer userId, String newUserName) {
        log.info("update user name start");
        User user = userMapper.selectById(userId);
        user.setName(newUserName);
        userMapper.updateById(user);
        log.info("update user name end");
    }

    public void updateUserAge(Integer userId, Integer newUserAge) {
        log.info("update user age start");
        User user = userMapper.selectById(userId);
        user.setAge(newUserAge);
        userMapper.updateById(user);
        log.info("update user age end");
    }
}
