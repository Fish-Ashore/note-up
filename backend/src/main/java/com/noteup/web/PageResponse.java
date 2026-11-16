package com.noteup.web;

import java.util.List;

/**
 * 通用分页响应体。
 *
 * <p>页码从 0 开始；{@code page} 与 {@code size} 返回的是服务层归一化后的
 * 实际值，客户端可据此计算总页数并处理越界请求。{@code total} 是满足过滤
 * 条件的总记录数，而非当前页条数。</p>
 *
 * @param items 当前页数据
 * @param total 满足条件的总记录数
 * @param page  当前页码，从 0 开始
 * @param size  实际每页条数（已按服务端上限裁剪）
 * @param <T>   数据元素类型
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}