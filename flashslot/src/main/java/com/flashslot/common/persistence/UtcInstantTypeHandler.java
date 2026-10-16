package com.flashslot.common.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** MySQL UTC DATETIME(3) 与 Instant 转换，不依赖 JVM 默认时区。 */
@MappedTypes(Instant.class)
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Instant value, JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, Timestamp.from(value), utc());
    }

    @Override
    public Instant getNullableResult(ResultSet rs, String column) throws SQLException {
        return instant(rs.getTimestamp(column, utc()));
    }

    @Override
    public Instant getNullableResult(ResultSet rs, int index) throws SQLException {
        return instant(rs.getTimestamp(index, utc()));
    }

    @Override
    public Instant getNullableResult(CallableStatement cs, int index) throws SQLException {
        return instant(cs.getTimestamp(index, utc()));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
