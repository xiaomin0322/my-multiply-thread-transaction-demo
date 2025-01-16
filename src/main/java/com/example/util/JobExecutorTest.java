package com.example.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.batch.BatchProperties.Job;

public class JobExecutorTest {

	@Test
	public void testExecuteJobs() {
		// 测试正常情况
		testNormalExecution2();
		// 测试仅包含具有相同 objId 的任务
		// testJobsWithSameObjId();
		// 测试仅包含不具有 objId 的任务
		// testJobsWithoutObjId();
		// 测试异常处理
		// testExceptionHandling();
	}

	@SuppressWarnings("rawtypes")
	private void testNormalExecution2() {
		// 测试数据，其中包含一些具有相同 objId 的任务和一些不同 objId 的任务
		List<TestJob> jobList = Arrays.asList(new TestJob(1, "Job1"), new TestJob(1, "Job2"), new TestJob(2, "Job3"),
				new TestJob(3, "Job4"));

		// 定义将 Job 转换为 Runnable 的函数
		Function<TestJob, Runnable> jobToRunnable = job -> () -> {
			System.out.println(System.currentTimeMillis() + ":" + ((TestJob) job).toString() + "线程ID:"
					+ Thread.currentThread().getId());
			// 这里可以添加具体的任务逻辑，例如调用 job 的某个方法或进行其他操作
		};

		List<List<TestJob>> groupJobsByFieldList = JobExecutor.groupJobsByFieldList(jobList, "objId");

		System.out.println("groupJobsByFieldList:" + groupJobsByFieldList.size());

		List<List<Runnable>> convertToRunnableLists = JobExecutor.convertToRunnableLists(groupJobsByFieldList,
				jobToRunnable);

		System.out.println("convertToRunnableLists:" + convertToRunnableLists.size());

		List<CompletableFuture> executeRunnableLists = JobExecutor.executeRunnableLists(convertToRunnableLists);
		// 等待所有任务完成
		CompletableFuture.allOf(executeRunnableLists.toArray(new CompletableFuture[0])).join();
	}

	private void testNormalExecution() {
		// 测试数据，其中包含一些具有相同 objId 的任务和一些不同 objId 的任务
		List<TestJob> jobList = Arrays.asList(new TestJob(1, "Job1"), new TestJob(1, "Job2"), new TestJob(2, "Job3"),
				new TestJob(3, "Job4"));

		List<Object> results = JobExecutor.executeJobs(jobList, job -> {
			System.out.println(System.currentTimeMillis() + ":" + ((TestJob) job).toString() + "线程ID:"
					+ Thread.currentThread().getId());
			// 模拟任务执行，这里简单地返回任务名称
			return ((TestJob) job).getName();
		}, "objId");

		// 验证结果的数量是否正确
		assertEquals(4, results.size());
		// 验证结果是否包含预期的任务名称
		assertTrue(results.contains("Job1"));
		assertTrue(results.contains("Job2"));
		assertTrue(results.contains("Job3"));
		assertTrue(results.contains("Job4"));
	}

	private void testJobsWithSameObjId() {
		// 仅包含具有相同 objId 的任务
		List<TestJob> jobList = Arrays.asList(new TestJob(1, "Job1"), new TestJob(1, "Job2"));

		List<Object> results = JobExecutor.executeJobs(jobList, job -> {
			// 模拟任务执行，这里简单地返回任务名称
			return ((TestJob) job).getName();
		});

		// 验证结果的数量是否正确
		assertEquals(2, results.size());
		// 验证结果是否包含预期的任务名称
		assertTrue(results.contains("Job1"));
		assertTrue(results.contains("Job2"));
	}

	private void testJobsWithoutObjId() {
	}

	private void testExceptionHandling() {
		// 包含会抛出异常的任务
		List<TestJob> jobList = Arrays.asList(new TestJob(1, "Job1"), new TestJob(1, "Job2"), new TestJob(2, "Job3"),
				new TestJob(3, "Job4") {
					@Override
					public String getName() {
						throw new RuntimeException("Test Exception");
					}
				});

		List<Object> results = JobExecutor.executeJobs(jobList, job -> {
			// 模拟任务执行，这里简单地返回任务名称
			return ((TestJob) job).getName();
		});

		// 验证结果的数量是否小于等于 4，因为一个任务会抛出异常
		assertTrue(results.size() <= 4);
		// 验证是否包含预期的正常任务名称
		assertTrue(results.contains("Job1"));
		assertTrue(results.contains("Job2"));
		assertTrue(results.contains("Job3"));
		// 验证是否不包含异常任务的结果
		assertFalse(results.contains("Job4"));
	}

	// 测试任务类，实现 HasObjId 接口
	static class TestJob {
		private int objId;
		private String name;

		public TestJob(int objId, String name) {
			this.objId = objId;
			this.name = name;
		}

		public String getName() {
			return name;
		}

		@Override
		public String toString() {
			// TODO Auto-generated method stub
			return objId + ":" + name;
		}
	}
}