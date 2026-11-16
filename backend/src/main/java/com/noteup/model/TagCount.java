package com.noteup.model;

/**
 * 标签及其关联的已发布笔记数量。
 *
 * <p>由标签统计查询直接投影得到，用于标签云或侧边栏展示；只统计已发布且未删除的笔记。</p>
 *
 * @param name  标签名
 * @param count 使用该标签的笔记数
 */
public record TagCount(String name, long count) {
}