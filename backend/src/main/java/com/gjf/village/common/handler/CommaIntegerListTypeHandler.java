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
import java.util.List;

/**
 * 逗号分隔字符串 ↔ List&lt;Integer&gt; 的类型转换器。
 *
 * <p>专门用于 {@code biz_resource.rentable_weekdays} —— 它存的是
 * {@code "1,2,3,4,5"} 表示周一至周五可租。
 *
 * <p>单独写一个而不复用 {@link CommaStringListTypeHandler}:后者返回的是
 * {@code List<String>},前端拿到 {@code ["1","2"]} 还得自己 parseInt,
 * 而且没法直接和星期几做比较。类型在边界上转换干净,后面就都省事。
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class CommaIntegerListTypeHandler extends BaseTypeHandler<List<Integer>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Integer> parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")));
    }

    @Override
    public List<Integer> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return split(rs.getString(columnName));
    }

    @Override
    public List<Integer> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return split(rs.getString(columnIndex));
    }

    @Override
    public List<Integer> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return split(cs.getString(columnIndex));
    }

    /**
     * 切分并转成整数。
     *
     * <p>非法值直接跳过而不是抛异常:数据库里混进一个手工改坏的字符
     * 不应该让整个详情页 500。宁可少显示一天可租日。
     */
    private List<Integer> split(String value) {
        List<Integer> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 跳过非法值,见方法注释
            }
        }
        return result;
    }
}
