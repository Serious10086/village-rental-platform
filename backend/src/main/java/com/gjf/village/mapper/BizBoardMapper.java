package com.gjf.village.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gjf.village.dto.AdminBoardQueryDTO;
import com.gjf.village.dto.BoardQueryDTO;
import com.gjf.village.entity.BizBoard;
import com.gjf.village.vo.BoardItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 留言板 Mapper。
 *
 * <p>列表要联出作者姓名头像、村组名,以及"我点没点过赞" ——
 * 最后这项是个 {@code EXISTS} 子查询,不联的话前端要为列表里
 * 每一条留言各发一次请求。
 */
@Mapper
public interface BizBoardMapper extends BaseMapper<BizBoard> {

    /** 前台主帖列表(只含正常状态、未删除、{@code parent_id IS NULL} 的行) */
    IPage<BoardItemVO> selectRootPage(IPage<BoardItemVO> page,
                                      @Param("q") BoardQueryDTO query,
                                      @Param("me") Long me);

    /** 某条主帖下的回复,按时间正序 —— 对话要顺着读 */
    IPage<BoardItemVO> selectReplyPage(IPage<BoardItemVO> page,
                                       @Param("parentId") Long parentId,
                                       @Param("me") Long me);

    /** 后台留言列表(含回复、含已隐藏) */
    IPage<BoardItemVO> selectAdminPage(IPage<BoardItemVO> page,
                                       @Param("q") AdminBoardQueryDTO query);

    /**
     * 按 id 取主帖,同时锁住这一行({@code SELECT ... FOR UPDATE})。
     *
     * <p><b>所有会同时碰"主帖行"和"它的子行 / 冗余计数"的事务都必须用这个方法
     * 取代 {@code selectById}。</b>一句话的规则是:<b>先锁主帖行,后动子行和计数。</b>
     * 不这么做的话,并发的点赞 / 回复会卡在"外键检查加在主帖行上的共享锁"
     * 与"计数自增要的排他锁"之间,互相等成死锁 —— 详细过程见 XML 里的注释。
     *
     * <p>锁会一直持有到事务提交,所以调用方要意识到"同一个帖子上的写操作
     * 是串行的"。村级留言板的并发量下这个代价换来的确定性是划算的。
     */
    BizBoard selectByIdForUpdate(@Param("id") Long id);

    /**
     * 点赞数 +1,在数据库端自增。
     *
     * <p>写成"查出来 +1 再写回"在并发下会丢更新 —— 两个人同时点赞,
     * 都读到 5,各自写回 6,最终停在 6 而不是 7。
     * 这和余额自增是同一类问题。
     */
    int increaseLikeCount(@Param("id") Long id);

    /**
     * 点赞数 -1。
     *
     * <p>{@code AND like_count > 0} 是防御性条件:正常情况下取消点赞
     * 必然对应着一条已有的点赞记录,计数不会为 0。但冗余计数的正确性
     * 依赖"每次增删都成对",一旦某处漏了配对,这里至少能保证它
     * 不会掉成负数 —— 负的点赞数在界面上是没法解释的。
     */
    int decreaseLikeCount(@Param("id") Long id);

    /** 主帖回复数 +1 */
    int increaseReplyCount(@Param("id") Long id);

    /** 主帖回复数 -1(删除回复时用),同样带非负保护 */
    int decreaseReplyCount(@Param("id") Long id);
}
