package com.gjf.village.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 逗号分隔字符串 ↔ List&lt;String&gt; 的类型转换器。
 *
 * <p>用途:数据库里 {@code images}、{@code tags} 这类字段存的是
 * {@code "a.jpg,b.jpg,c.jpg"},但接口对外应该返回数组
 * {@code ["a.jpg","b.jpg","c.jpg"]}。
 *
 * <p><b>为什么不建关联表:</b>图片和标签都是"跟随资源整体读写"的 ——
 * 不会单独查询某张图片、也不会按标签反查资源(标签检索已由全文索引覆盖)。
 * 为它们各建一张关联表,收益只是形式上的规范化,代价是每次列表查询
 * 多两次 JOIN。等真需要"标签云导航"时再升级,升级路径是清晰的。
 *
 * <p><b>为什么用 TypeHandler 而不是在 Service 里 split:</b>
 * 后者要在每个用到的地方都写一遍转换,漏一处就返回一个逗号字符串给前端。
 * 放在这里则是一次性配置,所有查询自动生效。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class CommaStringListTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, String.join(",", parameter));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return split(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return split(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return split(cs.getString(columnIndex));
    }

    /**
     * 切分并清理空值。
     *
     * <p>必须过滤空串:数据库里如果存的是 {@code "a.jpg,"}(末尾多了逗号),
     * 直接 split 会得到一个空字符串元素,前端就会渲染出一个破图。
     */
    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
