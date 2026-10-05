package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gjf.village.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 用户 Mapper。
 *
 * <p>其余 CRUD 由 MyBatis-Plus 的 {@link BaseMapper} 提供,这里只写框架生成不了的。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按手机号查询用户 —— <b>含密码字段</b>,只用于"要比对密码"的两个场景:
     * 登录,以及登录后修改密码时校验原密码
     * ({@code SysUserServiceImpl#changePassword})。
     *
     * <p>为什么不直接用 MyBatis-Plus 的 selectOne:因为实体上的
     * {@code @TableField(select = false)} 会让框架生成的 SELECT 不带 password 列,
     * 比对时拿不到密码。这里手写 SQL 显式把密码查出来。
     *
     * <p>这个方法<b>只能</b>用来比对密码,不要用它返回给前端 ——
     * 返回对象里含密码哈希。
     */
    @Select("SELECT * FROM sys_user WHERE phone = #{phone} AND deleted = 0 LIMIT 1")
    SysUser selectByPhoneForLogin(@Param("phone") String phone);

    /**
     * 余额自增 —— <b>必须在数据库端做加法</b>。
     *
     * <p>看似等价、实际致命的写法是:
     * <pre>
     *   SysUser u = selectById(id);                 // 读到 100
     *   updateById(u.setBalance(u.getBalance()+50));// 写回 150
     * </pre>
     * 两个并发请求都读到 100,各自写回 150,总入账 100 而不是 100 ——
     * 这就是经典的<b>丢失更新</b>。写成 {@code SET balance = balance + ?}
     * 后,加法由数据库在行锁保护下完成,并发下也不会丢。
     *
     * <p>本方法<b>不返回新余额</b>。要返回余额必须先查一次再改一次,
     * 那就重新引入了竞态窗口;真需要精确余额时应该在同一个事务里
     * 用 {@code SELECT ... FOR UPDATE} 读。
     */
    @Update("UPDATE sys_user SET balance = balance + #{amount} WHERE id = #{userId} AND deleted = 0")
    int increaseBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 按ID读取用户,并<b>持有行锁直到事务结束</b>。
     *
     * <p><b>只用于提现申请。</b>提现是"先校验余额够不够、再扣减"的两步操作,
     * 两步之间如果没有任何保护,并发下会出现:
     * <pre>
     *   余额 100,两个请求同时提 100
     *   T1: 读到余额 100 → 够 → 扣 100
     *   T2: 读到余额 100 → 够 → 扣 100      ← T1 的扣减还没提交,T2 读到旧值
     *   结果:两笔都成功,余额 -100
     * </pre>
     * 加了 {@code FOR UPDATE} 后,T2 会阻塞在 SELECT 上,
     * 等 T1 提交后才读到<b>已经被扣过的</b>余额 0,于是被正常拒绝。
     * 这是典型的<b>检查后使用(TOCTOU)</b> 竞态 —— 详见
     * {@code BizOrderServiceImpl.create()} 里同样的处理。
     *
     * <p>方法是 {@code SELECT *},返回对象含密码哈希,<b>不要</b>把它
     * 直接返回给前端。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") Long id);

    /**
     * 信用分自增(可为负)。理由同上:加法必须在数据库端做。
     *
     * <p>分数有下限保护 —— 跌破 0 没有业务含义,而且会让"信用分低"的
     * 用户看起来像数据出错。用 {@code GREATEST(..., 0)} 兜住。
     */
    @Update("UPDATE sys_user SET credit_score = GREATEST(credit_score + #{delta}, 0) "
            + "WHERE id = #{userId} AND deleted = 0")
    int increaseCreditScore(@Param("userId") Long userId, @Param("delta") Integer delta);
}
