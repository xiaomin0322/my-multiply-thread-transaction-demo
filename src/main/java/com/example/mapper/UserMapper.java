package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.bean.BizData;
import com.example.dao.User;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * UserMapper
 *
 * @author lijun.pan
 * @version 2024/11/06 11:14
 **/
@Mapper
public interface UserMapper extends BaseMapper<User> {
	public void insertUser(User user);
	
	public void insertBatch(User user);
	
	public void insertBatchList(@Param("list") List<BizData> list);
}
