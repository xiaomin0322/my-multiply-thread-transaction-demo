package com.example.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.Resource;

import org.assertj.core.util.Lists;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dao.User;
import com.example.mapper.UserMapper;
import com.example.util.MultiplyThreadTransactionManager;
import com.example.util.MultiplyThreadTransactionManagerExt;

import lombok.extern.slf4j.Slf4j;

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
    
    @Resource
    private MultiplyThreadTransactionManagerExt multiplyThreadTransactionManagerExt;

    @RequestMapping("transaction")
    public String transaction() {
        List<Runnable> tasks = new ArrayList<>();

        tasks.add(() -> updateUserName(1, "lijun.pan"));

        tasks.add(() -> {
            updateUserAge(2, 23);
            throw new RuntimeException("update user age error");
        });
        //自动回滚
        multiplyThreadTransactionManager.execute(tasks, multiplyThreadTransactionExecutor);
        return "success";
    }
    
    @RequestMapping("transaction2")
    public String transaction2(Integer par) {
        List<List<Runnable>> tasks = new ArrayList<>();

        tasks.add(Lists.newArrayList(() -> updateUserName(1, "lijun.pan")));

        tasks.add(Lists.newArrayList(() ->{
            updateUserAge(2, 23);
            if(par!=null && par == 1) {
            	 throw new RuntimeException("update user age error");
            }
        }));
        //这里自动就回滚了
        log.info("runAsyncButWaitUntilAllDown runAsyncButWaitUntilAllDown");
        multiplyThreadTransactionManagerExt.runAsyncButWaitUntilAllDown(tasks, false);
        
        if(par!=null && par == 1) {
        	try {
        		updateNameException();
        	}catch (Exception e) {
        		multiplyThreadTransactionManagerExt.multiplyThreadTransactionRollback();
			}
        }
        
        log.info("runAsyncButWaitUntilAllDown multiplyThreadTransactionCommit");
        multiplyThreadTransactionManagerExt.multiplyThreadTransactionCommit();
       
        return "success";
    }
    
    /**
     * 多线程任务手动提交
     * @param par
     * @return
     */
    @RequestMapping("transaction3")
    public String transaction3(Integer par) {
    	 List<List<Runnable>> tasks = new ArrayList<>();

    	 tasks.add(Lists.newArrayList(() -> updateUserName(1, "lijun.pan3")));

    	 tasks.add(Lists.newArrayList(() -> {
            updateUserAge(2, 233);
        }));

        log.info("runAsyncButWaitUntilAllDown runAsyncButWaitUntilAllDown");
        //设置为true为自动提交
        multiplyThreadTransactionManagerExt.runAsyncButWaitUntilAllDown(tasks, false);
        
        if(par!=null && par == 1) {
        	try {
        		updateNameException();
        	}catch (Exception e) {
        		//手动回滚
        		log.info("runAsyncButWaitUntilAllDown multiplyThreadTransactionRollback");
        		multiplyThreadTransactionManagerExt.multiplyThreadTransactionRollback();
			}
        }
        //提交事务，如果前面回滚了就不提交了
        log.info("runAsyncButWaitUntilAllDown multiplyThreadTransactionCommit");
        multiplyThreadTransactionManagerExt.multiplyThreadTransactionCommit();
       
        return "success";
    }

    @RequestMapping("updateName")
    @Transactional(rollbackFor = Exception.class)
    public String updateName() {
        updateUserName(1, "lijun.pan");
        return "success";
    }
    
    @Transactional(rollbackFor = Exception.class)
    public String updateNameException() {
    	//如果不同线程先修同一条数据会出现死锁，所以这里修改id=3得数据
        updateUserName(2, "Exception");
        throw new RuntimeException("update user age error");
        //return "success";
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
