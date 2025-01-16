package com.example.util;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * 多线程事务管理
 * https://www.jb51.net/program/317477hx6.htm
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MultiplyThreadTransactionManagerExt {
    /**
     * 如果是多数据源的情况下,需要指定具体是哪一个数据源
     */
    private final DataSource dataSource;
    private final static ThreadLocal<Boolean> immediatelyCommitFlag = new ThreadLocal<>();
    private final static ThreadLocal<List<TransactionStatus>> transactionStatusListThreadLocal = new ThreadLocal<>();
    private final static ThreadLocal<List<TransactionResource>> transactionResourcesthreadLocal = new ThreadLocal<>();
    private final static ThreadLocal<Map<Object, Object>> mainNativeResourceThreadLocal = new ThreadLocal<>();
    /**
     * 多线程下事务执行
     *
     * @param tasks             任务列表
     * @param immediatelyCommit 是否需要立即提交
     */
    public List<CompletableFuture> runAsyncButWaitUntilAllDown(List<List<Runnable>> tasks,  Boolean immediatelyCommit) {
        Executor executor = Executors.newCachedThreadPool();
        DataSourceTransactionManager transactionManager = getTransactionManager();
        //是否发生了异常
        AtomicBoolean ex = new AtomicBoolean();
        List<CompletableFuture> taskFutureList = new CopyOnWriteArrayList<>();
        List<TransactionStatus> transactionStatusList = new CopyOnWriteArrayList<>();
        List<TransactionResource> transactionResources = new CopyOnWriteArrayList<>();
        //记录原生主事务资源
        //这一步可能在原生sql执行前，也可能在原生sql执行后，所以这个资源可能不够充分，需要在下面继续处理
        //如果返回的是原资源集合的引用，下面一步可以不用
        Map<Object, Object> resourceMap = TransactionSynchronizationManager.getResourceMap();
        if (!CollectionUtils.isEmpty(resourceMap)) {
            mainNativeResourceThreadLocal.set(new HashMap<>(resourceMap));
        }
        Map<String, String> copyOfContextMap = MDC.getCopyOfContextMap();
        Executor finalExecutor = executor;
        AtomicInteger atomicInteger = new AtomicInteger(0);
        tasks.forEach(task -> {
            taskFutureList.add(CompletableFuture.runAsync(
                    () -> {
                        log.info("任务开始");
                        try {
                            //1.开启新事务
                            TransactionStatus transactionStatus = openNewTransaction(transactionManager);
                            log.info("开启新事务 successfully");
                            transactionStatusList.add(transactionStatus);
                            atomicInteger.incrementAndGet();
                            System.out.println("atomicInteger.get()"+atomicInteger.incrementAndGet());
                            System.out.println(transactionStatus);
                            //2.异步任务执行
                            for (Runnable t : task) {
                                // 串行执行任务
                                t.run();
                                log.info("任务执行 successfully");
                            }
                            log.info("异步任务执行 successfully");
                            //3.继续事务资源复制,因为在sql执行是会产生新的资源对象
                            transactionResources.add(TransactionResource.copyTransactionResource());
                        } catch (Throwable throwable) {
                            log.info("任务执行异常"+throwable.getMessage());
                            log.error("任务执行异常",throwable);
                            //其中某个异步任务执行出现了异常,进行标记
                            ex.set(Boolean.TRUE);
                            //其他任务还没执行的不需要执行了
                            taskFutureList.forEach(completableFuture -> completableFuture.cancel(true));
                        }
                    }
                    , finalExecutor)
            );
        });
        try {
            //阻塞直到所有任务全部执行结束---如果有任务被取消,这里会抛出异常滴,需要捕获
            CompletableFuture.allOf(taskFutureList.toArray(new CompletableFuture[]{})).get();
        } catch (InterruptedException | ExecutionException e) {
            log.info("任务被取消");
            log.error("任务被取消",e);
        }
        //发生了异常则进行回滚操作,否则提交
        if (ex.get()) {
            log.info("发生异常,全部事务回滚");
            for (int i = 0; i < transactionStatusList.size(); i++) {
                transactionResources.get(i).autoWiredTransactionResource();
                Map<Object, Object> rollBackResourceMap = TransactionSynchronizationManager.getResourceMap();
                log.info("回滚前事务资源size{}，本身{}",rollBackResourceMap.size(),rollBackResourceMap);
                transactionManager.rollback(transactionStatusList.get(i));
                transactionResources.get(i).removeTransactionResource();
            }
        } else {
            if (immediatelyCommit) {
                log.info("全部事务正常提交");
                for (int i = 0; i < transactionStatusList.size(); i++) {
                    //transactionResources.get(i).autoWiredTransactionResource();
                    Map<Object, Object> commitResourceMap = TransactionSynchronizationManager.getResourceMap();
                    log.info("提交前事务资源size{}，本身{}",commitResourceMap.size(),commitResourceMap);
                    transactionManager.commit(transactionStatusList.get(i));
                    transactionResources.get(i).removeTransactionResource();
                }
            } else {
                //缓存全部待提交数据
                immediatelyCommitFlag.set(immediatelyCommit);
                transactionResourcesthreadLocal.set(transactionResources);
                transactionStatusListThreadLocal.set(transactionStatusList);
            }
        }
        //交还给主事务
        if (immediatelyCommit) {
            mainTransactionResourceBack(!ex.get());
        }
        return taskFutureList;
    }
    /**
     * 全部提交
     */
    public void multiplyThreadTransactionCommit() {
        try {
            Boolean immediatelyCommit = immediatelyCommitFlag.get();
            if (immediatelyCommit!= null && immediatelyCommit) {
                throw new IllegalStateException("immediatelyCommit cant call multiplyThreadTransactionCommit");
            }
            //提交
            //获取存储的事务资源和状态
            List<TransactionResource> transactionResources = transactionResourcesthreadLocal.get();
            List<TransactionStatus> transactionStatusList = transactionStatusListThreadLocal.get();
            if (CollectionUtils.isEmpty(transactionResources) || CollectionUtils.isEmpty(transactionStatusList)) {
                //throw new IllegalStateException("transactionResources or transactionStatusList is empty");
            	log.info("transactionResources or transactionStatusList is empty");
                return ;
            }
            //重新提交
            DataSourceTransactionManager transactionManager = getTransactionManager();
            log.info("全部事务正常提交");
            for (int i = 0; i < transactionStatusList.size(); i++) {
                transactionResources.get(i).autoWiredTransactionResource();
                Map<Object, Object> commitResourceMap = TransactionSynchronizationManager.getResourceMap();
                log.info("提交前事务资源size{}，本身{}",commitResourceMap.size(),commitResourceMap);
                transactionManager.commit(transactionStatusList.get(i));
                transactionResources.get(i).removeTransactionResource();
            }
        } catch (Exception e) {
            mainTransactionResourceBack(false);
            log.error("multiplyThreadTransactionCommit fail", e);
        } finally {
            transactionResourcesthreadLocal.remove();
            transactionStatusListThreadLocal.remove();
            immediatelyCommitFlag.remove();
        }
        //交还给主事务
        mainTransactionResourceBack(true);
    }
    
    /**
     * 全部回滚
     */
    public void multiplyThreadTransactionRollback() {
        try {
            Boolean immediatelyCommit = immediatelyCommitFlag.get();
            if (immediatelyCommit) {
                throw new IllegalStateException("immediatelyCommit cant call multiplyThreadTransactionCommit");
            }
            //提交
            //获取存储的事务资源和状态
            List<TransactionResource> transactionResources = transactionResourcesthreadLocal.get();
            List<TransactionStatus> transactionStatusList = transactionStatusListThreadLocal.get();
            if (CollectionUtils.isEmpty(transactionResources) || CollectionUtils.isEmpty(transactionStatusList)) {
                //throw new IllegalStateException("transactionResources or transactionStatusList is empty");
                log.info("transactionResources or transactionStatusList is empty");
                return;
            }
            //重新提交
            DataSourceTransactionManager transactionManager = getTransactionManager();
            log.info("全部事务正常回滚");
            for (int i = 0; i < transactionStatusList.size(); i++) {
                transactionResources.get(i).autoWiredTransactionResource();
                Map<Object, Object> commitResourceMap = TransactionSynchronizationManager.getResourceMap();
                log.info("回滚前事务资源size{}，本身{}",commitResourceMap.size(),commitResourceMap);
                transactionManager.rollback(transactionStatusList.get(i));
                transactionResources.get(i).removeTransactionResource();
            }
        } catch (Exception e) {
            mainTransactionResourceBack(false);
            log.error("multiplyThreadTransactionCommit fail", e);
        } finally {
            transactionResourcesthreadLocal.remove();
            transactionStatusListThreadLocal.remove();
            immediatelyCommitFlag.remove();
        }
        //交还给主事务
        mainTransactionResourceBack(true);
    }
    
    //主线程事务资源返还
    public void mainTransactionResourceBack(Boolean subTransactionSuccess) {
        if (CollectionUtils.isEmpty(mainNativeResourceThreadLocal.get())) {
            //清除数据
            mainNativeResourceThreadLocal.remove();
            return;
        }
        Map<Object, Object> nativeResource = new HashMap<>(mainNativeResourceThreadLocal.get());
        Map<Object, Object> resourceMap = TransactionSynchronizationManager.getResourceMap();
        log.info("当前线程资事务源size{}--------------------------------{}",resourceMap.size(), resourceMap);
        log.info("原生线程事务资源size{}--------------------------------{}",nativeResource.size(), nativeResource);
        //已经被绑定的资源不能重复绑定
        if (!CollectionUtils.isEmpty(resourceMap)) {
            for (Object o : resourceMap.keySet()) {
                if (nativeResource.containsKey(o)) {
                    nativeResource.remove(o);
                }
            }
        }
        nativeResource.forEach((k,v)->{
            if (!(k instanceof DataSource)){
                log.info("nativeResource 没有 DataSource");
            }
        });
        //交还不能绑定factory
        nativeResource.forEach((k,v)->{
            if (k instanceof DataSource){
                TransactionSynchronizationManager.bindResource(k,v);
            }
        });
        Map<Object, Object> finResource = TransactionSynchronizationManager.getResourceMap();
        log.info("主线程最终事务源size{}--------------------------------{}",finResource.size(), finResource);
        //防止未激活事务
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        //清除数据
        mainNativeResourceThreadLocal.remove();
        if (!subTransactionSuccess) {
            throw new RuntimeException("子事务失败，需要回滚");
        }
    }
    private TransactionStatus openNewTransaction(DataSourceTransactionManager transactionManager) {
        //JdbcTransactionManager根据TransactionDefinition信息来进行一些连接属性的设置
        //包括隔离级别和传播行为等
        DefaultTransactionDefinition transactionDef = new DefaultTransactionDefinition();
        //开启一个新事务---此时autocommit已经被设置为了false,并且当前没有事务,这里创建的是一个新事务
        return transactionManager.getTransaction(transactionDef);
    }
    private DataSourceTransactionManager getTransactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }
    /**
     * 保存当前事务资源,用于线程间的事务资源COPY操作
     */
    @Builder
    private static class TransactionResource {
        //事务结束后默认会移除集合中的DataSource作为key关联的资源记录
        private Map<Object, Object> resources = new HashMap<>();
        //下面五个属性会在事务结束后被自动清理,无需我们手动清理
        private Set<TransactionSynchronization> synchronizations = new HashSet<>();
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
                if (resourceMap.containsKey(o)) {
                    //移除重复事务资源key，避免绑定报错
                    resources.remove(o);
                }
            }
            boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
            //绑定事务资源，注意 绑定是绑定到当前主线程上，记得最后释放交换主线程，再由主线程收回原有事务自选
            resources.forEach(TransactionSynchronizationManager::bindResource);
            //如果需要注册事务监听者,这里记得修改--我们这里不需要,就采用默认负责--spring事务内部默认也是这个值
            //避免重复激活或者事务未激活
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