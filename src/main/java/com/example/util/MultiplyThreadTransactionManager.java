package com.example.util;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import javax.management.RuntimeErrorException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultiplyThreadTransactionManager {

    @Resource
    private PlatformTransactionManager transactionManager;

    public void execute(List<Runnable> tasks, Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("线程池不能为空");
        }
        //是否发生了异常
        AtomicBoolean ifException = new AtomicBoolean();

        // 事务任务列表
        List<CompletableFuture<?>> taskFutureList = new ArrayList<>(tasks.size());
        // 事务状态列表
        List<TransactionStatus> transactionStatusList = new CopyOnWriteArrayList<>();
        // 事务资源列表
        List<TransactionResource> transactionResourceList = new CopyOnWriteArrayList<>();

        tasks.forEach(task -> taskFutureList.add(CompletableFuture.runAsync(
                        () -> {
                            try {
                                // 1.开启新事务
                                transactionStatusList.add(openNewTransaction(transactionManager));
                                // 2.异步任务执行
                                task.run();
                                // 3.将执行的资源保存到主线程
                                transactionResourceList.add(TransactionResource.copyTransactionResource());
                            } catch (Throwable throwable) {
                                //打印异常
                                log.error("异步任务执行出现了异常", throwable);
                                //其中某个异步任务执行出现了异常,进行标记
                                ifException.set(Boolean.TRUE);
                                //其他任务还没执行的不需要执行了
                                taskFutureList.forEach(completableFuture -> completableFuture.cancel(true));
                            }
                        }, executor)
                .whenComplete((unused, throwable) -> {
                    // 4.子线程释放资源
                    TransactionResource transactionResource = TransactionResource.copyTransactionResource();
                    transactionResource.removeTransactionResource();
//                    Map<Object, Object> resourceMap = TransactionSynchronizationManager.getResourceMap();
//                    resourceMap.forEach((key, value) -> {
//                        TransactionSynchronizationManager.unbindResource(key);
//                    });
                    TransactionSynchronizationManager.clear();
                })
        ));

        try {
            //阻塞直到所有任务全部执行结束---如果有任务被取消,这里会抛出异常滴,需要捕获
            CompletableFuture<?>[] taskFutureListArray = taskFutureList.toArray(new CompletableFuture[]{});
            CompletableFuture.allOf(taskFutureListArray).get();
        } catch (InterruptedException | ExecutionException ex) {
            log.error("异步任务执行出现了异常", ex);
        }

        //发生了异常则进行回滚操作,否则提交
        if (ifException.get()) {
            log.error("发生异常,全部事务回滚");
            for (int i = 0; i < transactionStatusList.size() && i < transactionResourceList.size(); i++) {
                // 主线程绑定事务资源
                transactionResourceList.get(i).autoWiredTransactionResource();
                // 回滚
                Map<Object, Object> commitResourceMap = TransactionSynchronizationManager.getResourceMap();
                log.info("回滚前事务资源,size: {}, resources: \n{}", commitResourceMap.size(), commitResourceMap);
                transactionManager.rollback(transactionStatusList.get(i));
                // 主线程移除资源
                transactionResourceList.get(i).removeTransactionResource();
            }
            throw new RuntimeException("发生异常,全部事务回滚");
        } else {
            log.info("全部事务正常提交");
            for (int i = 0; i < transactionStatusList.size() && i < transactionResourceList.size(); i++) {
                // 主线程绑定事务资源
                transactionResourceList.get(i).autoWiredTransactionResource();
                // 提交
                Map<Object, Object> commitResourceMap = TransactionSynchronizationManager.getResourceMap();
                log.info("提交前事务资源,size: {}, resources: \n{}", commitResourceMap.size(), commitResourceMap);
                transactionManager.commit(transactionStatusList.get(i));
                // 主线程移除资源
                transactionResourceList.get(i).removeTransactionResource();
            }
        }
    }

    private TransactionStatus openNewTransaction(PlatformTransactionManager transactionManager) {
        //TransactionDefinition信息来进行一些连接属性的设置
        DefaultTransactionDefinition transactionDef = new DefaultTransactionDefinition();
        //开启一个新事务---此时autocommit已经被设置为了false,并且当前没有事务,这里创建的是一个新事务
        return transactionManager.getTransaction(transactionDef);
    }

    /**
     * 保存当前事务资源,用于线程间的事务资源COPY操作
     */
    @Builder
    private static class TransactionResource {
        //事务结束后默认会移除集合中的DataSource作为key关联的资源记录
        private Map<Object, Object> resources;

        //下面五个属性会在事务结束后被自动清理,无需我们手动清理
        private Set<TransactionSynchronization> synchronizations;

        private String currentTransactionName;

        private Boolean currentTransactionReadOnly;

        private Integer currentTransactionIsolationLevel;

        private Boolean actualTransactionActive;

        public static TransactionResource copyTransactionResource() {
            return TransactionResource.builder()
                    //返回的是不可变集合，这里为了更加灵活，copy出一个集合过来
                    .resources(new HashMap<>(TransactionSynchronizationManager.getResourceMap()))
                    //如果需要注册事务监听者,这里记得修改--我们这里不需要,就采用默认负责--spring事务内部默认也是这个值
                    .synchronizations(new LinkedHashSet<>())
                    .currentTransactionName(TransactionSynchronizationManager.getCurrentTransactionName())
                    .currentTransactionReadOnly(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    .currentTransactionIsolationLevel(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .actualTransactionActive(TransactionSynchronizationManager.isActualTransactionActive())
                    .build();
        }

        //装配事务资源，为提交/回滚做储备
        public void autoWiredTransactionResource() {
            //获取当前线程事务资源
            Map<Object, Object> resourceMap = TransactionSynchronizationManager.getResourceMap();
            for (Object o : resourceMap.keySet()) {
                //移除重复事务资源key，避免绑定报错
                resources.remove(o);
            }
            //绑定事务资源，注意 绑定是绑定到当前主线程上，记得最后释放交换主线程，再由主线程收回原有事务自选
            resources.forEach(TransactionSynchronizationManager::bindResource);
            //如果需要注册事务监听者,这里记得修改--我们这里不需要,就采用默认负责--spring事务内部默认也是这个值
            //避免重复激活或者事务未激活
            boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
            if (!synchronizationActive) {
                TransactionSynchronizationManager.initSynchronization();
            }

            TransactionSynchronizationManager.setActualTransactionActive(actualTransactionActive);
            TransactionSynchronizationManager.setCurrentTransactionName(currentTransactionName);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(currentTransactionIsolationLevel);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(currentTransactionReadOnly);
        }

        public void removeTransactionResource() {
            Map<Object, Object> resourceMap = new HashMap<>(TransactionSynchronizationManager.getResourceMap());

            //事务结束后默认会移除集合中的DataSource作为key关联的资源记录
            //DataSource如果重复移除,unbindResource时会因为不存在此key关联的事务资源而报错
            resources.keySet().forEach(key -> {
                if (resourceMap.containsKey(key)) {
                    TransactionSynchronizationManager.unbindResource(key);
                }
            });
        }
    }
}