package com.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AsyncConfig 异步Config
 *
 * @author lijun.pan
 * @version 2024/11/06 11:35
 **/
@Slf4j
@Configuration
public class AsyncConfig {

    @Bean("multiplyThreadTransactionExecutor")
    public ThreadPoolExecutor multiplyThreadTransactionExecutor() {
        log.info("初始化多线程事务线程池multiplyThreadTransactionExecutor");

        // 获取当前系统可用处理器的数量
        int coreNum = Runtime.getRuntime().availableProcessors();
        // 使用核心数的一半
        int useThreadNum = coreNum / 2;
        // 最大线程数为核心数的一倍
        int maxThreadNum = useThreadNum * 2;

        // 创建线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                // 核心线程数
                useThreadNum,
                // 最大线程数
                maxThreadNum,
                // 空闲线程存活时间
                60L,
                TimeUnit.SECONDS,
                // 阻塞队列容量
                new LinkedBlockingQueue<>(1000),
                // 拒绝策略
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 设置线程名称前缀
        executor.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("multiplyThreadTransactionExecutor-" + thread.getId());
            return thread;
        });

        log.info("当前系统可用处理器数量：{}，配置核心线程数量：{}，配置最大线程数量：{}", coreNum, useThreadNum, maxThreadNum);
        log.info("多线程事务线程池multiplyThreadTransactionExecutor初始化完成");
        return executor;
    }
}
