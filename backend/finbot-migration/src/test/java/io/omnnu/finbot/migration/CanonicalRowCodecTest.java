package io.omnnu.finbot.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

final class CanonicalRowCodecTest {
    @Test
    void serializesColumnsInStableNameOrderAndPreservesBinaryValues() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                var create = connection.createStatement()) {
            create.execute("CREATE TABLE source_table (z_value TEXT, a_value INTEGER, payload BLOB)");
            try (var insert = connection.prepareStatement("INSERT INTO source_table VALUES (?, ?, ?)")) {
                insert.setString(1, "last");
                insert.setInt(2, 7);
                insert.setBytes(3, new byte[] {1, 2, 3});
                insert.executeUpdate();
            }
            try (var query = connection.createStatement();
                    var rows = query.executeQuery(
                            "SELECT rowid AS \"__finbot_rowid\", * FROM source_table ORDER BY rowid")) {
                rows.next();
                var result = new CanonicalRowCodec(new ObjectMapper()).read(rows);
                assertEquals(1, result.ordinal());
                assertEquals("1", result.sourceKey());
                assertEquals(
                        "{\"a_value\":7,\"payload\":\"base64:AQID\",\"z_value\":\"last\"}",
                        result.json());
                assertEquals(Hashing.sha256(result.json()), result.sha256());
            }
        }
    }
}
