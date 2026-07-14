package io.omnnu.finbot.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.TreeMap;

final class CanonicalRowCodec {
    private static final String ROW_ID_COLUMN = "__finbot_rowid";
    private final ObjectMapper objectMapper;

    CanonicalRowCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CanonicalRow read(ResultSet resultSet) throws SQLException {
        var metadata = resultSet.getMetaData();
        var content = new TreeMap<String, Object>();
        for (var column = 1; column <= metadata.getColumnCount(); column++) {
            var label = metadata.getColumnLabel(column);
            if (!ROW_ID_COLUMN.equals(label)) {
                content.put(label, normalize(resultSet.getObject(column)));
            }
        }
        var ordinal = resultSet.getLong(ROW_ID_COLUMN);
        try {
            var json = objectMapper.writeValueAsString(content);
            return new CanonicalRow(ordinal, Long.toString(ordinal), json, Hashing.sha256(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode legacy row " + ordinal, exception);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof byte[] bytes) {
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }
}
