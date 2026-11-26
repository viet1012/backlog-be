package com.example.backlogbe.repository.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

// =========================================================
// dbo.F2_ProductionControl_Session (TokenHash CHAR(64) PK = SHA-256 hex của token)
//
// Thời gian lưu theo UTC (SYSUTCDATETIME), tính hoàn toàn trong SQL để không lệch múi giờ giữa
// máy BE (Docker) và SQL Server. (cần xác nhận: app khác dùng chung bảng cũng lưu UTC)
// Tham số TokenHash / EmpCode CAST sang kiểu VARCHAR/CHAR: JDBC gửi chuỗi dạng NVARCHAR,
// so trực tiếp với cột CHAR/VARCHAR sẽ ép kiểu cột và quét cả bảng.
// =========================================================
@Repository
@RequiredArgsConstructor
public class ProductionControlSessionRepository {

	private final JdbcTemplate jdbcTemplate;

	public record SessionRow(
			String empCode,
			Instant createdAt,
			Instant lastSeenAt,
			Instant expiresAt
	) {
	}

	private static final RowMapper<SessionRow> SESSION_MAPPER = (rs, rowNum) -> new SessionRow(
			rs.getString("EmpCode"),
			utc(rs.getTimestamp("CreatedAt")),
			utc(rs.getTimestamp("LastSeenAt")),
			utc(rs.getTimestamp("ExpiresAt"))
	);

	// Tạo session; trả ExpiresAt (= now + idleHours)
	public Instant insert(String tokenHash, String empCode, int idleHours) {

		String sql = """
				INSERT INTO dbo.F2_ProductionControl_Session
				    (TokenHash, EmpCode, CreatedAt, LastSeenAt, ExpiresAt, RevokedAt)
				OUTPUT INSERTED.ExpiresAt
				VALUES
				    (CAST(? AS CHAR(64)), ?, SYSUTCDATETIME(), SYSUTCDATETIME(),
				     DATEADD(HOUR, ?, SYSUTCDATETIME()), NULL)
				""";

		List<Instant> rows = jdbcTemplate.query(
				sql,
				(rs, rowNum) -> utc(rs.getTimestamp("ExpiresAt")),
				tokenHash, empCode, idleHours
		);

		return rows.isEmpty() ? null : rows.get(0);
	}

	// Session còn hiệu lực (chưa revoke, chưa hết hạn)
	public Optional<SessionRow> findActive(String tokenHash) {

		String sql = """
				SELECT EmpCode, CreatedAt, LastSeenAt, ExpiresAt
				FROM dbo.F2_ProductionControl_Session
				WHERE TokenHash = CAST(? AS CHAR(64))
				  AND RevokedAt IS NULL
				  AND ExpiresAt > SYSUTCDATETIME()
				""";

		return jdbcTemplate.query(sql, SESSION_MAPPER, tokenHash).stream().findFirst();
	}

	// Gia hạn trượt: ExpiresAt = min(now + idleHours, CreatedAt + maxDays); cập nhật LastSeenAt.
	// Session đã revoke / hết hạn -> không đổi, trả empty.
	public Optional<SessionRow> touch(String tokenHash, int idleHours, int maxDays) {

		String sql = """
				UPDATE dbo.F2_ProductionControl_Session
				SET LastSeenAt = SYSUTCDATETIME(),
				    ExpiresAt = CASE
				        WHEN DATEADD(HOUR, ?, SYSUTCDATETIME()) < DATEADD(DAY, ?, CreatedAt)
				            THEN DATEADD(HOUR, ?, SYSUTCDATETIME())
				        ELSE DATEADD(DAY, ?, CreatedAt)
				    END
				OUTPUT INSERTED.EmpCode, INSERTED.CreatedAt, INSERTED.LastSeenAt, INSERTED.ExpiresAt
				WHERE TokenHash = CAST(? AS CHAR(64))
				  AND RevokedAt IS NULL
				  AND ExpiresAt > SYSUTCDATETIME()
				""";

		return jdbcTemplate.query(
				sql, SESSION_MAPPER,
				idleHours, maxDays, idleHours, maxDays, tokenHash
		).stream().findFirst();
	}

	public void revoke(String tokenHash) {

		String sql = """
				UPDATE dbo.F2_ProductionControl_Session
				SET RevokedAt = SYSUTCDATETIME()
				WHERE TokenHash = CAST(? AS CHAR(64))
				  AND RevokedAt IS NULL
				""";

		jdbcTemplate.update(sql, tokenHash);
	}

	// Cột lưu UTC không kèm múi giờ -> đọc giá trị "đồng hồ" rồi gắn UTC
	private static Instant utc(Timestamp value) {
		return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
	}
}
