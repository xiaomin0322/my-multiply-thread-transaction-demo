package com.nmb.zx.base.sys.event.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.util.JobExecutorTest.TestJob;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Maps;
import com.nmb.zx.base.sys.bizData.entity.BizData;
import com.nmb.zx.base.sys.bizData.service.BizDataService;
import com.nmb.zx.base.sys.dataHandover.service.DataRelationService;
import com.nmb.zx.base.sys.design.entity.Design;
import com.nmb.zx.base.sys.design.mapper.DesignMapper;
import com.nmb.zx.base.sys.design.vo.DesignConfig;
import com.nmb.zx.base.sys.event.entity.Event;
import com.nmb.zx.base.sys.event.entity.EventConfigDetail;
import com.nmb.zx.base.sys.event.entity.FlowEventRelation;
import com.nmb.zx.base.sys.event.mapper.EventConfigDetailMapper;
import com.nmb.zx.base.sys.event.mapper.EventMapper;
import com.nmb.zx.base.sys.event.mapper.FlowEventRelationMapper;
import com.nmb.zx.base.sys.ma.MaEngine;
import com.nmb.zx.base.sys.ma.func.vo.Table;
import com.nmb.zx.base.sys.module.entity.Module;
import com.nmb.zx.base.sys.module.mapper.ModuleMapper;
import com.nmb.zx.base.sys.specialField.dataTitle.entity.DataTitle;
import com.nmb.zx.base.sys.specialField.dataTitle.mapper.DataTitleMapper;
import com.nmb.zx.base.sys.subMod.entity.SubMod;
import com.nmb.zx.base.sys.subMod.mapper.SubModMapper;
import com.nmb.zx.base.sys.systemLog.entity.SystemLog;
import com.nmb.zx.base.sys.systemLog.service.SystemLogService;
import com.nmb.zx.base.sys.utils.JobExecutor;
import com.nmb.zx.common.util.JsonUtil;
import com.nmb.zx.common.util.session.SessionUtil;
import com.nmb.zx.common.vo.BusinessException;
import com.nmb.zx.common.vo.CommonField;
import com.nmb.zx.common.vo.LoginVo;

import lombok.var;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EventService {
	@Autowired
	private EventMapper eventMapper;
	@Autowired
	private ModuleMapper moduleMapper;
	@Lazy
	@Autowired
	private BizDataService bizDataService;
	@Autowired
	private EventConfigDetailMapper eventConfigDetailMapper;
	@Autowired
	private FlowEventRelationMapper flowEventRelationMapper;
	@Autowired
	private DataTitleMapper dataTitleMapper;
	@Autowired
	private DataRelationService dataRelationService;
	@Autowired
	private SubModMapper subModMapper;
	@Autowired
	private SystemLogService systemLogService;
	@Autowired
	private DesignMapper designMapper;

	/**
	 * 执行表单事件
	 *
	 * @param events   事件
	 * @param variable 数据源
	 * @param user     当前用户
	 */
	@SuppressWarnings("rawtypes")
	public void execute2(List<Event> events, Map<String, Object> variable, Module module, LoginVo user) {
		if (CollectionUtils.isEmpty(events)) {
			return;
		}
		// 定义将 Job 转换为 Runnable 的函数
		Function<Event, Runnable> jobToRunnable = event -> () -> {
			Map<String, Object> variableNew = Maps.newHashMap(variable);
			executeEvent(variableNew, module, user, event);
		};
		List<List<Event>> groupJobsByFieldList = JobExecutor.groupJobsByFieldList(events, "targetModuleId");
		log.info("groupJobsByFieldList size:{}", groupJobsByFieldList.size());

		List<List<Runnable>> convertToRunnableLists = JobExecutor.convertToRunnableLists(groupJobsByFieldList,
				jobToRunnable);
		log.info("convertToRunnableLists:" + convertToRunnableLists.size());

		List<CompletableFuture> executeRunnableLists = JobExecutor.executeRunnableLists(convertToRunnableLists);
		// 等待所有任务完成
		CompletableFuture.allOf(executeRunnableLists.toArray(new CompletableFuture[0])).join();

	}

	private void executeEvent(Map<String, Object> variable, Module module, LoginVo user, Event event) {
		log.info("检索到事件name:{},remark:{}", event.getName(), event.getRemark());
		// 将数据标题写入事务malang公式 by wj
		Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
		Design tarDesign = designMapper.selectById(targetModule.getFormId());
		DesignConfig designConfig = JsonUtil.read(tarDesign.getConfig(), DesignConfig.class);
		QueryWrapper<DataTitle> wrapper = new QueryWrapper<>();
		wrapper.eq("moduleId", event.getTargetModuleId());
		if (StringUtils.hasText(event.getTargetSubTable())) {
			wrapper.eq("fieldKey", event.getTargetSubTable());
		} else {
			wrapper.isNull("fieldKey");
		}
		List<DataTitle> dataTitles = dataTitleMapper.selectList(wrapper);
		var records = this.splitChildren(variable, event.getCurChild());
		for (var record : records) {
			event.setModuleName(module.getName());
			if (StringUtils.hasText(event.getTriggerCondition())) {
				try {
					Boolean condResult = new MaEngine(record, event.getTriggerCondition(), user).result().bool();
					if (!condResult) {
						log.info("未达到事件触发条件");
						continue;
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					throw new BusinessException("触发条件异常:" + event.getName() + ",备注: " + event.getRemark());
				}
			}
			try {
				Map<String, Object> param = new HashMap<>();
				param.put("source", record);
				Table table = new Table();
				if (!StringUtils.hasText(event.getTargetSubTable())) {
					table.setTableName(targetModule.getTableName());
					table.setType(CommonField.Type.main);
					table.setModuleId(targetModule.getId());
					table.setModule(targetModule);
				} else {
					QueryWrapper<SubMod> queryWrapper = new QueryWrapper<>();
					queryWrapper.eq("mainModId", targetModule.getId()).eq("fieldKey", event.getTargetSubTable());
					SubMod subMod = subModMapper.selectOne(queryWrapper);
					if (Objects.isNull(subMod)) {
						throw new BusinessException("目标子表不存在");
					}
					table.setTableName(subMod.getTableName());
					table.setType(CommonField.Type.child);
					table.setFieldKey(subMod.getFieldKey());
					table.setModuleId(subMod.getMainModId());
					table.setModule(targetModule);
				}
				Boolean actResult = new MaEngine(param, event.getAction(), user, event, table, dataTitles, designConfig)
						.result().bool();
				if (!actResult) {
					throw new BusinessException("流转规则执行失败: " + event.getName() + ",备注: " + event.getRemark());
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				throw new BusinessException(
						"流转规则执行失败: " + event.getName() + ",备注: " + event.getRemark() + e.getMessage());
			}
		}
		log.info("事件{}完成,remark:{},record size:{}", event.getName(), event.getRemark(), records.size());

		try {
			if ("insert".equals(event.getTriggerType())) {
				dataRelationService.addDataRelation(user.getUserId(), targetModule.getTableName());
				if (StringUtils.hasText(event.getTargetSubTable())) {
					SubMod targetSubMod = subModMapper
							.selectOne(new QueryWrapper<SubMod>().eq("mainModId", event.getTargetModuleId())
									.eq("deleted", 0).eq("fieldKey", event.getTargetSubTable()));
					dataRelationService.addDataRelation(user.getUserId(), targetSubMod.getTableName());
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new BusinessException("用户关联事务目标数据表异常:" + event.getName() + ",备注: " + event.getRemark());
		}
	}

	/**
	 * 执行表单事件
	 *
	 * @param events   事件
	 * @param variable 数据源
	 * @param user     当前用户
	 */
	public void execute(List<Event> events, Map<String, Object> variable, Module module, LoginVo user) {
		if (CollectionUtils.isEmpty(events)) {
			return;
		}
		for (Event event : events) {
			log.info("检索到事件name:{},remark:{}", event.getName(), event.getRemark());
			// 将数据标题写入事务malang公式 by wj
			Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
			// tableName 相同只能顺序执行，不同的可以同时进行
			String tableName = targetModule.getTableName();
//            if (targetModule.getSimpleDataTitle().equals(1)) {
//                LambdaQueryWrapper<DataTitle> wrapper = Wrappers.lambdaQuery();
//                wrapper.eq(DataTitle::getModuleId, event.getTargetModuleId());
//                if (StringUtils.hasText(event.getTargetSubTable())) {
//                    wrapper.eq(DataTitle::getFieldKey, event.getTargetSubTable());
//                } else {
//                    wrapper.isNull(DataTitle::getFieldKey);
//                }
//                List<DataTitle> dataTitles = dataTitleMapper.selectList(wrapper);
//                String dataTitleAction = dataTitles.stream()
//                        .map(dataTitle -> {
//                            if (DataTitle.Type.FIXED.getCode().equals(dataTitle.getType())) {
//                                return "'" + dataTitle.getConfig() + "'";
//                            } else if (DataTitle.Type.FIELD.getCode().equals(dataTitle.getType())) {
//                                return "_SQLSTRINGIFNULL(${" + dataTitle.getConfig() + "})";
//                            } else {
//                                throw new BusinessException("未知的规则类型");
//                            }
//                        })
//                        .collect(Collectors.joining(","));
//                if (dataTitles.size() > 1) {
//                    dataTitleAction = ",${dataTitle},_SQLCONCAT(" + dataTitleAction + ")";
//                } else {
//                    dataTitleAction = ",${dataTitle}," + dataTitleAction;
//                }
//                if (event.getAction().contains("_INSERT(")) {
//                    event.setAction(event.getAction().substring(0, event.getAction().length() - 1)
//                            + dataTitleAction + ")");
//                } else if (event.getAction().contains("_UPDATE(") || event.getAction().contains("_UPSERT(")) {
//                    event.setAction(event.getAction()
//                            .replace(",'WHERE", dataTitleAction + ",'WHERE"));
//                }
//            }

			Design tarDesign = designMapper.selectById(targetModule.getFormId());
			DesignConfig designConfig = JsonUtil.read(tarDesign.getConfig(), DesignConfig.class);
			QueryWrapper<DataTitle> wrapper = new QueryWrapper<>();
			wrapper.eq("moduleId", event.getTargetModuleId());
			if (StringUtils.hasText(event.getTargetSubTable())) {
				wrapper.eq("fieldKey", event.getTargetSubTable());
			} else {
				wrapper.isNull("fieldKey");
			}
			List<DataTitle> dataTitles = dataTitleMapper.selectList(wrapper);
			var records = this.splitChildren(variable, event.getCurChild());
			for (var record : records) {
				event.setModuleName(module.getName());
				if (StringUtils.hasText(event.getTriggerCondition())) {
					try {
						Boolean condResult = new MaEngine(record, event.getTriggerCondition(), user).result().bool();
						if (!condResult) {
							log.info("未达到事件触发条件");
							continue;
						}
					} catch (Exception e) {
						log.error(e.getMessage(), e);
						throw new BusinessException("触发条件异常:" + event.getName() + ",备注: " + event.getRemark());
					}
				}
				try {
					Map<String, Object> param = new HashMap<>();
					param.put("source", record);
					Table table = new Table();
					if (!StringUtils.hasText(event.getTargetSubTable())) {
						table.setTableName(targetModule.getTableName());
						table.setType(CommonField.Type.main);
						table.setModuleId(targetModule.getId());
						table.setModule(targetModule);
					} else {
						QueryWrapper<SubMod> queryWrapper = new QueryWrapper<>();
						queryWrapper.eq("mainModId", targetModule.getId()).eq("fieldKey", event.getTargetSubTable());
						SubMod subMod = subModMapper.selectOne(queryWrapper);
						if (Objects.isNull(subMod)) {
							throw new BusinessException("目标子表不存在");
						}
						table.setTableName(subMod.getTableName());
						table.setType(CommonField.Type.child);
						table.setFieldKey(subMod.getFieldKey());
						table.setModuleId(subMod.getMainModId());
						table.setModule(targetModule);
					}
					Boolean actResult = new MaEngine(param, event.getAction(), user, event, table, dataTitles,
							designConfig).result().bool();
					if (!actResult) {
						throw new BusinessException("流转规则执行失败: " + event.getName() + ",备注: " + event.getRemark());
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					throw new BusinessException(
							"流转规则执行失败: " + event.getName() + ",备注: " + event.getRemark() + e.getMessage());
				}
			}
			log.info("事件{}完成,remark:{},record size:{}", event.getName(), event.getRemark(), records.size());

			try {
				if ("insert".equals(event.getTriggerType())) {
					dataRelationService.addDataRelation(user.getUserId(), targetModule.getTableName());
					if (StringUtils.hasText(event.getTargetSubTable())) {
						SubMod targetSubMod = subModMapper
								.selectOne(new QueryWrapper<SubMod>().eq("mainModId", event.getTargetModuleId())
										.eq("deleted", 0).eq("fieldKey", event.getTargetSubTable()));
						dataRelationService.addDataRelation(user.getUserId(), targetSubMod.getTableName());
					}
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				throw new BusinessException("用户关联事务目标数据表异常:" + event.getName() + ",备注: " + event.getRemark());
			}
		}
	}

	/**
	 * 执行流程表单事件
	 *
	 * @param id     数据id
	 * @param module 表单
	 * @param scene  reactivate:重新激活, manual:手动触发
	 * @param user   当前用户
	 */
	public void execute(Integer id, Module module, String scene, LoginVo user) {
		BizData detail = bizDataService.detail(module.getId(), id);
		QueryWrapper<Event> wrapper = new QueryWrapper<>();
		wrapper.eq("moduleId", module.getId()).eq("type", Event.Type.common.getCode())
				.eq("status", Event.Status.ENABLE.getCode()).orderByAsc("sort");
		Map<String, List<Event>> eventGroup = eventMapper.selectList(wrapper).stream()
				.collect(Collectors.groupingBy(Event::getTriggerType));
		if ("reactivate".equals(scene)) {
			this.execute(eventGroup.get(Event.TriggerType.delete.getCode()), detail.getData(), module, user);
		} else if ("manual".equals(scene)) {
			this.execute(eventGroup.get(Event.TriggerType.insert.getCode()), detail.getData(), module, user);
		}
	}

	/**
	 * 执行流程事件
	 *
	 * @param processType 处理方式 commit:提交, reject:驳回, withdraw:撤回, transfer:转交,
	 *                    delete:删除
	 * @param taskDefKey  流程节点
	 * @param procDefId   流程节点
	 * @param module      表单
	 * @param id          数据id
	 * @param user        当前用户
	 */
	public void executeFlowEvent(String processType, String taskDefKey, String procDefId, Module module, Integer id,
			LoginVo user) {
		QueryWrapper<FlowEventRelation> wrapper = new QueryWrapper<>();
		wrapper.eq("taskDefKey", taskDefKey).eq("procDefId", procDefId).orderByAsc("id");

		List<Event> events = flowEventRelationMapper.selectList(wrapper).stream().map(relation -> {
			QueryWrapper<Event> eventWrapper = new QueryWrapper<>();
			eventWrapper.eq("id", relation.getEventId()).eq("triggerType", processType).eq("status",
					Event.Status.ENABLE.getCode());
			return eventMapper.selectOne(eventWrapper);
		}).filter(Objects::nonNull).collect(Collectors.toList());
		if (CollectionUtils.isEmpty(events)) {
			return;
		}
		BizData detail = bizDataService.detail(module.getId(), id);
		this.execute(events, detail.getData(), module, user);
	}

	public void save(List<Event> events, LoginVo user) {
		for (Event event : events) {
			if (Objects.isNull(event.getId())) {
				SessionUtil.fillSession(user, event, false);
				eventMapper.insert(event);

				event.getActionConfig().forEach(actionConfig -> {
					EventConfigDetail detail = new EventConfigDetail();
					detail.setEventId(event.getId());
					detail.setDetail(actionConfig);
					eventConfigDetailMapper.insert(detail);
				});
				Module module = moduleMapper.selectById(event.getModuleId());
				SystemLog systemLog = new SystemLog();
				systemLog.setType(2);
				systemLog.setFunctionName(module.getName());
				systemLog.setFunctionType(Event.Type.common.getCode().equals(event.getType()) ? "表单事务设置" : "流程事务设置");
				systemLog.setOperationType("事务新增");
				String triggerType = null;
				for (Event.TriggerType trigger : Event.TriggerType.values()) {
					if (trigger.getCode().equals(event.getTriggerType())) {
						triggerType = trigger.getDesc();
						break;
					}
				}
				Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
				systemLog.setOperationContent(
						"新增事务、触发时机：" + triggerType + "；规则类型：" + event.getName() + "；目标表单：" + targetModule.getName());
				systemLogService.addSystemLog(user, systemLog);
			} else {
				SessionUtil.fillSession(user, event, true);
				eventMapper.updateById(event);

				QueryWrapper<EventConfigDetail> wrapper = new QueryWrapper<>();
				wrapper.eq("eventId", event.getId());
				eventConfigDetailMapper.delete(wrapper);
				event.getActionConfig().forEach(actionConfig -> {
					EventConfigDetail detail = new EventConfigDetail();
					detail.setEventId(event.getId());
					detail.setDetail(actionConfig);
					eventConfigDetailMapper.insert(detail);
				});

				Module module = moduleMapper.selectById(event.getModuleId());
				SystemLog systemLog = new SystemLog();
				systemLog.setType(2);
				systemLog.setFunctionName(module.getName());
				systemLog.setFunctionType(Event.Type.common.getCode().equals(event.getType()) ? "表单事务设置" : "流程事务设置");
				systemLog.setOperationType("事务修改");
				String triggerType = null;
				for (Event.TriggerType trigger : Event.TriggerType.values()) {
					if (trigger.getCode().equals(event.getTriggerType())) {
						triggerType = trigger.getDesc();
						break;
					}
				}
				Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
				systemLog.setOperationContent(
						"修改事务、触发时机：" + triggerType + "；规则类型：" + event.getName() + "；目标表单：" + targetModule.getName());
				systemLogService.addSystemLog(user, systemLog);
			}
		}
	}

	public PageInfo<Event> list(Integer moduleId, String type, Integer corpId, Integer pageNum, Integer pageSize,
			String triggerType, String name, String remark, Integer targetModuleId, Integer status,
			Integer executeOnUpdate) {
		QueryWrapper<Event> wrapper = new QueryWrapper<>();
		wrapper.eq("corpId", corpId).eq("type", type).eq("moduleId", moduleId);
		if (StringUtils.hasText(triggerType)) {
			wrapper.eq("triggerType", triggerType);
		}
		if (StringUtils.hasText(name)) {
			wrapper.like("name", name);
		}
		if (StringUtils.hasText(remark)) {
			wrapper.like("remark", remark);
		}
		if (!targetModuleId.equals(0)) {
			wrapper.eq("targetModuleId", targetModuleId);
		}
		if (!status.equals(-1)) {
			wrapper.eq("status", status);
		}
		if (!executeOnUpdate.equals(-1)) {
			wrapper.eq("executeOnUpdate", executeOnUpdate);
		}
		wrapper.orderByAsc("sort");
		PageInfo<Event> pageInfo = PageHelper.startPage(pageNum, pageSize)
				.doSelectPageInfo(() -> eventMapper.selectList(wrapper));
		// 填充目标表单名称
		List<Integer> targetModuleIds = pageInfo.getList().stream().map(Event::getTargetModuleId)
				.collect(Collectors.toList());
		Map<Integer, String> moduleMap = new HashMap<>();
		if (!CollectionUtils.isEmpty(targetModuleIds)) {
			moduleMap = moduleMapper.selectBatchIds(targetModuleIds).stream()
					.collect(Collectors.toMap(Module::getId, Module::getName, (m1, m2) -> m1));
		}
		for (Event event : pageInfo.getList()) {
			event.setModuleName(moduleMap.get(event.getTargetModuleId()));
		}
		// 填充事件配置详情
		List<Integer> ids = pageInfo.getList().stream().map(Event::getId).collect(Collectors.toList());
		if (!CollectionUtils.isEmpty(ids)) {
			QueryWrapper<EventConfigDetail> detailWrapper = new QueryWrapper<>();
			detailWrapper.in("eventId", ids);
			Map<Integer, List<String>> detailGroup = eventConfigDetailMapper.selectList(detailWrapper).stream()
					.collect(Collectors.groupingBy(EventConfigDetail::getEventId,
							Collectors.mapping(EventConfigDetail::getDetail, Collectors.toList())));
			for (Event event : pageInfo.getList()) {
				List<String> details = detailGroup.get(event.getId());
				if (!CollectionUtils.isEmpty(details)) {
					event.getActionConfig().addAll(details);
				}
			}
		}
		return pageInfo;
	}

	public List<FlowEventRelation> getEventRelations(Integer moduleId, String procDefId) {
		QueryWrapper<FlowEventRelation> wrapper = new QueryWrapper<>();
		wrapper.eq("moduleId", moduleId).eq("procDefId", procDefId);
		return flowEventRelationMapper.selectList(wrapper);
	}

	public void delete(Integer eventId, LoginVo user) {
		Event event = eventMapper.selectById(eventId);
		eventMapper.deleteById(event.getId());

		Module module = moduleMapper.selectById(event.getModuleId());
		SystemLog systemLog = new SystemLog();
		systemLog.setType(2);
		systemLog.setFunctionName(module.getName());
		systemLog.setFunctionType(Event.Type.common.getCode().equals(event.getType()) ? "表单事务设置" : "流程事务设置");
		systemLog.setOperationType("事务删除");
		String triggerType = null;
		for (Event.TriggerType trigger : Event.TriggerType.values()) {
			if (trigger.getCode().equals(event.getTriggerType())) {
				triggerType = trigger.getDesc();
				break;
			}
		}
		Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
		systemLog.setOperationContent(
				"删除事务、触发时机：" + triggerType + "；规则类型：" + event.getName() + "；目标表单：" + targetModule.getName());
		systemLogService.addSystemLog(user, systemLog);
	}

	private List<Map<String, Object>> splitChildren(Map<String, Object> variable, String curChild) {
		int curSize = 1;
		Object currentChild = variable.get(curChild);
		if (Objects.nonNull(currentChild) && currentChild instanceof List) {
			curSize = ((List<?>) currentChild).size();
		}
		List<Map<String, Object>> records = new ArrayList<>();
		for (int i = 0; i < curSize; i++) {
			records.add(new HashMap<>());
		}
		variable.forEach((k, v) -> {
			if (k.equals(curChild)) {
				for (int i = 0; i < ((List<?>) v).size(); i++) {
					records.get(i).put(k, ((List<?>) v).get(i));
				}
			} else if (!(v instanceof List)) {
				for (Map<String, Object> record : records) {
					record.put(k, v);
				}
			}
		});
		return records;
	}

	public void updateStatus(Event event, LoginVo user) {
		UpdateWrapper<Event> wrapper = Wrappers.update();
		wrapper.lambda().set(Event::getStatus, event.getStatus()).set(Event::getUpdaterId, user.getUserId())
				.set(Event::getUpdaterName, user.getNikeName()).set(Event::getUpdateTime, LocalDateTime.now())
				.eq(Event::getId, event.getId());
		eventMapper.update(null, wrapper);

		Module module = moduleMapper.selectById(event.getModuleId());
		SystemLog systemLog = new SystemLog();
		systemLog.setType(2);
		systemLog.setFunctionName(module.getName());
		systemLog.setFunctionType(Event.Type.common.getCode().equals(event.getType()) ? "表单事务设置" : "流程事务设置");
		systemLog.setOperationType("事务修改");
		String triggerType = null;
		for (Event.TriggerType trigger : Event.TriggerType.values()) {
			if (trigger.getCode().equals(event.getTriggerType())) {
				triggerType = trigger.getDesc();
				break;
			}
		}
		Module targetModule = moduleMapper.selectById(event.getTargetModuleId());
		systemLog.setOperationContent(
				"修改事务、触发时机：" + triggerType + "；规则类型：" + event.getName() + "；目标表单：" + targetModule.getName());
		systemLogService.addSystemLog(user, systemLog);
	}
}
