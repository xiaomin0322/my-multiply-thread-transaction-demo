package com.example.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public class JobExecutor {

	private static final ExecutorService executorService = Executors.newFixedThreadPool(10);

	public static <T> List<Object> executeJobs(List<T> jobList, Function<T, Object> jobFunction, String groupingField) {
		Map<Object, List<T>> groupedJobs = groupJobsByField(jobList, groupingField);

		List<Future<Object>> futures = new ArrayList<>();
		for (List<T> sameGroupJobs : groupedJobs.values()) {
			// 对于同一组的任务，串行执行
			futures.add(executorService.submit(() -> {
				List<Object> results = new ArrayList<>();
				for (T job : sameGroupJobs) {
					results.add(jobFunction.apply(job));
				}
				return results;
			}));
		}

		List<Object> results = new ArrayList<>();
		for (Future<Object> future : futures) {
			try {
				Object result = future.get();
				if (result instanceof List) {
					results.addAll((List) result);
				} else {
					results.add(result);
				}
			} catch (Exception e) {
				// 处理异常，这里可以根据实际情况进行更详细的异常处理
				e.printStackTrace();
			}
		}
		return results;
	}

	public static <T> List<Object> executeJobs(List<T> jobList, Function<T, Object> jobFunction) {
		return executeJobs(jobList, jobFunction, null);
	}

	@SuppressWarnings("rawtypes")
	public static <T> List<CompletableFuture> executeRunnableListsByCommonExecutor(List<List<Runnable>> runnableLists) {
		List<CompletableFuture> futures = new ArrayList<>();
		for (List<Runnable> runnableList : runnableLists) {
			// 对于每个子列表的 Runnable，根据情况进行并行或串行执行
			if (runnableList.size() > 1) {
				// 串行执行
				futures.add(CompletableFuture.runAsync(() -> {
					for (Runnable runnable : runnableList) {
						runnable.run();
					}
				}, executorService));
			} else if (runnableList.size() == 1) {
				// 并行执行单个 Runnable
				futures.add(CompletableFuture.runAsync(runnableList.get(0), executorService));
			}
		}
		// 等待所有任务完成
        //CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		return futures;
	}
	
    @SuppressWarnings("rawtypes")
    public static <T> List<CompletableFuture> executeRunnableLists(List<List<Runnable>> runnableLists) {
        List<CompletableFuture> futures = new ArrayList<>();
        // 为每个调用创建一个新的 ExecutorService
        ExecutorService executorService = Executors.newCachedThreadPool();
        try {
            for (List<Runnable> runnableList : runnableLists) {
                // 对于每个子列表的 Runnable，根据情况进行并行或串行执行
                if (runnableList.size() > 1) {
                    // 串行执行
                    futures.add(CompletableFuture.runAsync(() -> {
                        for (Runnable runnable : runnableList) {
                            runnable.run();
                        }
                    }, executorService));
                } else if (runnableList.size() == 1) {
                    // 并行执行单个 Runnable
                    futures.add(CompletableFuture.runAsync(runnableList.get(0), executorService));
                }
            }
        } finally {
            // 确保在使用完后关闭 ExecutorService
            executorService.shutdown();
        }
        return futures;
    }

	/**
	 * 将List<List<T>> 转换成可以 List<List<Runnable>>
	 * 
	 * @param groupedJobs
	 * @param jobToRunnableConverter
	 * @return
	 */
	public static <T> List<List<Runnable>> convertToRunnableLists(List<List<T>> groupedJobs,
			Function<T, Runnable> jobToRunnableConverter) {
		List<List<Runnable>> runnableLists = new ArrayList<>();
		for (List<T> jobList : groupedJobs) {
			List<Runnable> runnableList = new ArrayList<>();
			for (T job : jobList) {
				Runnable runnable = jobToRunnableConverter.apply(job);
				runnableList.add(runnable);
			}
			runnableLists.add(runnableList);
		}
		return runnableLists;
	}

	/**
	 * 按照指定的字段分组
	 * 
	 * @param jobList
	 * @param groupingField
	 * @return
	 */
	public static <T> List<List<T>> groupJobsByFieldList(List<T> jobList, String groupingField) {
		Map<Object, List<T>> groupedJobs;
		if (groupingField == null || groupingField.isEmpty()) {
			// 如果分组字段为空，不进行分组，将所有任务视为同一组
			groupedJobs = new HashMap<>();
			groupedJobs.put(null, jobList);
		} else {
			groupedJobs = jobList.stream().collect(Collectors.groupingBy(job -> {
				try {
					Field field = job.getClass().getDeclaredField(groupingField);
					field.setAccessible(true);
					return field.get(job);
				} catch (NoSuchFieldException | IllegalAccessException e) {
					throw new RuntimeException("Field not found or inaccessible: " + groupingField, e);
				}
			}));
		}

		// 将 Map 中的分组列表转换为 List 中的分组列表
		List<List<T>> groupedJobLists = new ArrayList<>();
		for (List<T> group : groupedJobs.values()) {
			groupedJobLists.add(group);
		}
		return groupedJobLists;
	}

	private static <T> Map<Object, List<T>> groupJobsByField(List<T> jobList, String groupingField) {
		Map<Object, List<T>> groupedJobs;
		if (groupingField == null || groupingField.isEmpty()) {
			// 如果分组字段为空，不进行分组，将所有任务视为同一组
			groupedJobs = new HashMap<>();
			groupedJobs.put(null, jobList);
		} else {
			groupedJobs = jobList.stream().collect(Collectors.groupingBy(job -> {
				try {
					java.lang.reflect.Field field = job.getClass().getDeclaredField(groupingField);
					field.setAccessible(true);
					return field.get(job);
				} catch (NoSuchFieldException | IllegalAccessException e) {
					throw new RuntimeException("Field not found or inaccessible: " + groupingField, e);
				}
			}));
		}
		return groupedJobs;
	}
}