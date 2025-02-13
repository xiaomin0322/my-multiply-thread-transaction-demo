package com.example.bean;

import java.util.Map;

import lombok.Data;

@Data
public class BizData {
    private String tableName;
    private Map<String, Object> data;
    private Integer id;
}
