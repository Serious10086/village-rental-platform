package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.BizBoardLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 留言点赞 Mapper。
 *
 * <p>点赞的增删查都是单表操作,{@code BaseMapper} 提供的方法够用,
 * 不需要手写 SQL —— 这和 {@link BizBoardMapper} 不一样,那边要联表
 * 拿作者姓名和"我点没点过",只能用 XML。
 *
 * <p><b>真正防重复点赞的是 {@code uk_board_user(board_id, user_id)}
 * 这个唯一约束</b>,不是 Service 里的"先查再插"。Service 里那次查询
 * 只是为了让重复点赞的用户看到一句"你已经点过赞了"而不是一个
 * 数据库异常,它挡不住并发 —— 两个请求同时通过检查、同时插入,
 * 最终是唯一约束在数据库那层把第二条顶掉。
 */
@Mapper
public interface BizBoardLikeMapper extends BaseMapper<BizBoardLike> {
}
