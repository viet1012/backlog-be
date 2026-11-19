package com.example.backlogbe.assistant.screens.costmonitoring.repository;

import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemStatDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostLineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopCostCenterDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopItemDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTopMachineDto;
import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostTransactionLineDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// =========================================================
// COST MONITORING - TOP VẬT TƯ / COST CENTER / MÁY (dùng cho tool của trợ lý AI)
//
// Lọc giống [KVH].dbo.fn_CostActualDaily (sql/02_fn_cost_actual_daily.sql):
//   T&SL: CostTool, Factory 612*, GL 570300 / 570400
//   RM  : CostRm, Factory 612K, bỏ GL 580000 và Movingtype 551 / 552
// Cost center suy ra như function, nhưng CHỈ lấy cost center trực tiếp của bộ phận
// (không gồm chi phí chung F2 / HT2 / HT-G được phân bổ theo tỷ lệ).
// Amount âm (trả hàng / điều chỉnh) vẫn cộng vào tổng.
// Amount trả về đã chia 1000 -> K$.
// =========================================================
@Repository
@RequiredArgsConstructor
public class CostTopItemsRepository {

	// ---------- Cột dùng chung CostTool / CostRm ----------
	private static final String CODE_COL = "[Code]";
	private static final String NAME_COL = "[Text]";
	private static final String QTY_COL = "[Qty]";
	private static final String QTY_UNIT_COL = "[QtyUnit]";
	private static final String AMOUNT_COL = "[Amount]";
	private static final String DATE_COL = "[UsedDate]";
	private static final String CC_CODE_COL = "[PLcostcenter]";
	private static final String CC_NAME_COL = "[PLcostcentername]";
	private static final String CATEGORY_COL = "[Category]";
	private static final String MACHINE_COL = "[Machine]";

	// ---------- Đơn giá: tên cột khác nhau giữa 2 bảng ----------
	private static final String TOOL_UNIT_PRICE_COL = "[EnddingUnitPrice]";
	private static final String RM_UNIT_PRICE_COL = "[EndingUnitPrice]";

	// Cột chung của CTE B (alias bảng: x). Ký tự % trong LIKE phải viết %% vì dùng formatted().
	// Số: thử convert trực tiếp (cột số), không được thì bỏ dấu phẩy (cột chuỗi "1,234.5").
	// UnitPrice dùng cho danh sách giao dịch của drawer (findLines).
	private static final String COMMON_SELECT = """
			    SELECT NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(100), x.%1$s))), '')  AS Code,
			           NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(400), x.%2$s))), '')  AS Name,
			           NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(200), x.%3$s))), '')  AS Category,
			           NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(50),  x.%4$s))), '')  AS QtyUnit,
			           COALESCE(TRY_CONVERT(FLOAT, x.%5$s),
			                    TRY_CONVERT(FLOAT, REPLACE(CONVERT(NVARCHAR(50), x.%5$s), ',', ''))) AS Qty,
			           ISNULL(COALESCE(TRY_CONVERT(FLOAT, x.%6$s),
			                           TRY_CONVERT(FLOAT, REPLACE(CONVERT(NVARCHAR(50), x.%6$s), ',', ''))), 0) AS Amt,
			           NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(50),  x.%7$s))), '')  AS CcCode,
			           ISNULL(NULLIF(LTRIM(RTRIM(x.%8$s)), ''), N'(trống)')       AS CcName,
			           NULLIF(LTRIM(RTRIM(CONVERT(NVARCHAR(100), x.%9$s))), '')  AS Machine,
			           COALESCE(TRY_CONVERT(FLOAT, x.%10$s),
			                    TRY_CONVERT(FLOAT, REPLACE(CONVERT(NVARCHAR(50), x.%10$s), ',', ''))) AS UnitPrice,
			           u.UD                                                          AS UsedDate,
			""";

	private static final String TSL_BASE = ("""
			WITH B AS (
			""" + COMMON_SELECT + """
			           CASE WHEN NULLIF(LTRIM(RTRIM(x.%8$s)), '') IS NULL THEN N'F2'
			                ELSE ISNULL(m.CostCenter, N'No master') END AS CC
			    FROM [KVH].[dbo].[CostTool] x
			    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), x.%11$s), 112) AS UD) u
			    LEFT JOIN [KVH].[dbo].[CostCenterMaster] m
			           ON m.PLCostCenterName = LTRIM(RTRIM(x.%8$s))
			    WHERE x.Factory LIKE '612%%'
			      AND TRY_CONVERT(INT, x.GLAccount) IN (570300, 570400)
			      AND x.%11$s >= CONVERT(CHAR(8), CAST(:fromDate AS DATE), 112)
			      AND x.%11$s <  CONVERT(CHAR(8), DATEADD(DAY, 1, CAST(:toDate AS DATE)), 112)
			      AND u.UD BETWEEN :fromDate AND :toDate
			)
			""").formatted(
			CODE_COL, NAME_COL, CATEGORY_COL, QTY_UNIT_COL, QTY_COL, AMOUNT_COL,
			CC_CODE_COL, CC_NAME_COL, MACHINE_COL, TOOL_UNIT_PRICE_COL, DATE_COL
	);

	private static final String RM_BASE = ("""
			WITH B AS (
			""" + COMMON_SELECT + """
			           CASE WHEN x.%2$s LIKE N'%%CAC%%' AND x.%3$s LIKE N'%%MO%%' THEN N'614200'
			                WHEN NULLIF(LTRIM(RTRIM(x.%8$s)), '') IS NULL THEN N'F2'
			                ELSE COALESCE(m.CostCenter, c.CostCenter, N'614100') END AS CC
			    FROM [KVH].[dbo].[CostRm] x
			    CROSS APPLY (SELECT TRY_CONVERT(DATE, CONVERT(VARCHAR(8), x.%11$s), 112) AS UD) u
			    LEFT JOIN [KVH].[dbo].[CostCenterMaster] m
			           ON m.PLCostCenterName = LTRIM(RTRIM(x.%8$s))
			    LEFT JOIN [KVH].[dbo].[CostCodeMaster] c
			           ON c.Code = LTRIM(RTRIM(x.%1$s))
			    WHERE x.Factory = '612K'
			      AND x.%11$s >= CONVERT(CHAR(8), CAST(:fromDate AS DATE), 112)
			      AND x.%11$s <  CONVERT(CHAR(8), DATEADD(DAY, 1, CAST(:toDate AS DATE)), 112)
			      AND u.UD BETWEEN :fromDate AND :toDate
			      AND ISNULL(TRY_CONVERT(INT, x.GLAccount), 0) <> 580000
			      AND ISNULL(TRY_CONVERT(INT, x.Movingtype), 0) NOT IN (551, 552)
			)
			""").formatted(
			CODE_COL, NAME_COL, CATEGORY_COL, QTY_UNIT_COL, QTY_COL, AMOUNT_COL,
			CC_CODE_COL, CC_NAME_COL, MACHINE_COL, RM_UNIT_PRICE_COL, DATE_COL
	);

	// TotalK = tổng cả khung (mọi nhóm, tính trước TOP) -> nạp sẵn cache findTotal, không cần truy vấn TOTAL riêng
	private static final String TOP_ITEMS = """
			SELECT TOP (:limit)
			       Code,
			       MAX(Name)          AS Name,
			       MAX(Category)      AS Category,
			       QtyUnit,
			       SUM(Qty)           AS Qty,
			       SUM(Amt) / 1000.0  AS AmtK,
			       SUM(SUM(Amt)) OVER () / 1000.0 AS TotalK
			FROM B
			WHERE CC IN (:costCenters)
			GROUP BY Code, QtyUnit
			ORDER BY SUM(Amt) DESC
			""";

	private static final String TOP_COST_CENTERS = """
			SELECT TOP (:limit)
			       CcName,
			       MAX(CcCode)        AS CcCode,
			       SUM(Amt) / 1000.0  AS AmtK,
			       SUM(SUM(Amt)) OVER () / 1000.0 AS TotalK
			FROM B
			WHERE CC IN (:costCenters)
			GROUP BY CcName
			ORDER BY SUM(Amt) DESC
			""";

	private static final String TOP_MACHINES = """
			SELECT TOP (:limit)
			       Machine,
			       SUM(Amt) / 1000.0  AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			  AND Machine IS NOT NULL
			GROUP BY Machine
			ORDER BY SUM(Amt) DESC
			""";

	// Tổng theo Category (mọi dòng, gồm âm) — dùng cho explain_variance
	private static final String SUM_BY_CATEGORY = """
			SELECT ISNULL(Category, N'(trống)') AS Label,
			       SUM(Amt) / 1000.0           AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			GROUP BY ISNULL(Category, N'(trống)')
			""";

	// Tổng theo vật tư (Code + Text)
	private static final String SUM_BY_ITEM = """
			SELECT Code,
			       Name,
			       SUM(Amt) / 1000.0 AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			GROUP BY Code, Name
			""";

	// Số dòng xuất kho thỏa bộ lọc (vd số dòng vật tư xuất trong 1 ngày)
	private static final String COUNT_ROWS = """
			SELECT COUNT(*) AS Cnt
			FROM B
			WHERE CC IN (:costCenters)
			""";

	private static final String TOTAL = """
			SELECT SUM(Amt) / 1000.0 AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			""";

	// Từng dòng xuất kho (drawer chi tiết 1 ô), thành tiền giảm dần, phân trang OFFSET / FETCH
	private static final String LINES_PAGE = """
			SELECT UsedDate,
			       Code,
			       Name,
			       Category,
			       Qty,
			       QtyUnit,
			       UnitPrice,
			       Amt,
			       CcCode,
			       CcName,
			       Machine
			FROM B
			WHERE CC IN (:costCenters)
			ORDER BY Amt DESC, UsedDate, Code
			OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
			""";

	// Thống kê theo vật tư (Code + QtyUnit) – câu hỏi chi tiết vật tư (CostMonitoringItemAnswers).
	// %1$s = cột Period, %2$s = điều kiện thêm, %3$s = GROUP BY. Đơn giá dòng = Amt / Qty (chỉ dòng Qty > 0, Amt > 0).
	private static final String ITEM_AGG = """
			SELECT %1$s                    AS Period,
			       Code,
			       MAX(Name)               AS Name,
			       MAX(Category)           AS Category,
			       QtyUnit,
			       SUM(Qty)                AS Qty,
			       SUM(Amt) / 1000.0       AS AmtK,
			       COUNT(*)                AS Lines,
			       COUNT(DISTINCT UsedDate) AS Days,
			       SUM(CASE WHEN Qty > 0 AND Amt > 0 THEN Qty END) AS PosQty,
			       SUM(CASE WHEN Qty > 0 AND Amt > 0 THEN Amt END) AS PosAmt,
			       MIN(CASE WHEN Qty > 0 AND Amt > 0 THEN Amt / Qty END) AS MinPrice,
			       MAX(CASE WHEN Qty > 0 AND Amt > 0 THEN Amt / Qty END) AS MaxPrice,
			       MAX(UsedDate)           AS LastDate
			FROM B
			WHERE CC IN (:costCenters)
			  AND Code IS NOT NULL
			  %2$s
			GROUP BY %3$s
			""";

	private static final String ITEM_STATS = ITEM_AGG.formatted(
			"CAST(NULL AS VARCHAR(10))", "", "Code, QtyUnit");

	private static final String ITEM_MONTHLY = ITEM_AGG.formatted(
			"CONVERT(VARCHAR(7), UsedDate, 23)", "", "Code, QtyUnit, CONVERT(VARCHAR(7), UsedDate, 23)");

	private static final String ITEM_DAILY = ITEM_AGG.formatted(
			"CONVERT(VARCHAR(10), UsedDate, 23)", "AND Code = :code",
			"Code, QtyUnit, CONVERT(VARCHAR(10), UsedDate, 23)") + "ORDER BY Period\n";

	// Từ khóa nhận diện khoản đặc biệt (bản quyền / phần mềm), so khớp trên Text và Category (lowercase)
	private static final List<String> UNUSUAL_KEYWORDS = List.of(
			"license", "licence", "renewal", "software",
			"bản quyền", "phần mềm", "ban quyen", "phan mem"
	);

	// Dòng xuất kho đơn lẻ, Amt > 0, và: Amt ≥ :minAmt (USD; null = bỏ điều kiện này) hoặc khớp từ khóa
	private static final String UNUSUAL_LINES = """
			SELECT TOP (:limit)
			       Code,
			       Name,
			       Category,
			       UsedDate,
			       Amt / 1000.0 AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			  AND Amt > 0
			  AND ((:minAmt IS NOT NULL AND Amt >= :minAmt)
			""" + keywordConditions() + """
			      )
			ORDER BY Amt DESC
			""";

	private static String keywordConditions() {

		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < UNUSUAL_KEYWORDS.size(); i++) {
			sb.append("       OR LOWER(ISNULL(Name, N'')) LIKE :kw").append(i)
					.append(" OR LOWER(ISNULL(Category, N'')) LIKE :kw").append(i)
					.append('\n');
		}

		return sb.toString();
	}

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// ---------- Cache ngắn hạn cho truy vấn tổng hợp (top / sum / total) ----------
	// Test AI 2026-11-10: câu so cùng kỳ 6 khung (12 truy vấn RM / T&SL) mất 6–11 s lần đầu, 1–3 s lần sau.
	// Dữ liệu actual cập nhật theo ngày -> TTL 5 phút đủ an toàn. Trả bản sao (caller có thể sửa list / map).
	// Kỳ đã kết thúc hẳn (toDate trước đầu tháng hiện tại) gần như không đổi -> TTL dài hơn (test 0758: câu theo khoảng
	// nhiều tháng 7–42 s mỗi lần vì cache 5 phút hết hạn giữa các lượt hỏi). Dữ liệu tháng cũ được sửa lại -> chậm tối đa
	// CLOSED_TTL mới thấy (cần xác nhận mức chấp nhận được với nghiệp vụ).
	private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
	private static final long CLOSED_TTL_MS = 6 * 60 * 60 * 1000L;
	private static final int CACHE_MAX = 800;

	private record CacheEntry(long expiresAt, Object value) {
	}

	private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	private <T> T cached(String key, LocalDate to, java.util.function.Supplier<T> loader) {
		long now = System.currentTimeMillis();
		CacheEntry e = cache.get(key);
		if (e != null && e.expiresAt() > now) {
			return (T) e.value();
		}
		T value = loader.get();
		put(key, to, value);
		return value;
	}

	private void put(String key, LocalDate to, Object value) {
		long now = System.currentTimeMillis();
		if (cache.size() >= CACHE_MAX) {
			cache.entrySet().removeIf(x -> x.getValue().expiresAt() <= now);
			if (cache.size() >= CACHE_MAX) {
				cache.clear();
			}
		}
		cache.put(key, new CacheEntry(now + ttl(to), value));
	}

	private static long ttl(LocalDate to) {
		return to != null && to.isBefore(LocalDate.now().withDayOfMonth(1)) ? CLOSED_TTL_MS : CACHE_TTL_MS;
	}

	// Tổng khung lấy kèm từ truy vấn top (cột TotalK) -> findTotal cùng kỳ không cần truy vấn lại
	private void seedTotal(String cost, LocalDate from, LocalDate to, List<String> costCenters, Double totalK) {
		put(key("total", cost, from, to, costCenters, 0), to, totalK == null ? List.of() : List.of(totalK));
	}

	private static String key(String kind, String cost, LocalDate from, LocalDate to, List<String> costCenters, int limit) {
		return kind + '|' + cost + '|' + from + '|' + to + '|' + costCenters + '|' + limit;
	}

	/*
	 * Khoản lớn bất thường (explain_variance): dòng xuất kho đơn lẻ ≥ minAmountK
	 * hoặc Text / Category chứa từ khóa license / renewal / software / bản quyền / phần mềm.
	 * minAmountK null -> chỉ lọc theo từ khóa. Lớn trước, tối đa limit dòng.
	 */
	public List<CostLineDto> findUnusualLines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			Double minAmountK,
			int limit
	) {

		MapSqlParameterSource params = params(from, to, costCenters, limit)
				.addValue("minAmt", minAmountK != null ? minAmountK * 1000.0 : null, Types.DOUBLE);

		for (int i = 0; i < UNUSUAL_KEYWORDS.size(); i++) {
			params.addValue("kw" + i, "%" + UNUSUAL_KEYWORDS.get(i) + "%", Types.NVARCHAR);
		}

		return jdbcTemplate.query(
				base(cost) + UNUSUAL_LINES,
				params,
				(rs, rowNum) -> {
					Date usedDate = rs.getDate("UsedDate");
					return new CostLineDto(
							rs.getString("Code"),
							rs.getString("Name"),
							rs.getString("Category"),
							usedDate != null ? usedDate.toLocalDate() : null,
							getDouble(rs, "AmtK")
					);
				}
		);
	}

	/*
	 * Dòng xuất kho của các cost center trực tiếp trong [from, to], thành tiền giảm dần, phân trang.
	 * Cùng bộ lọc với countRows / findTotal (dùng để tính tổng số dòng / tổng thành tiền).
	 */
	public List<CostTransactionLineDto> findLines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int offset,
			int limit
	) {

		return jdbcTemplate.query(
				base(cost) + LINES_PAGE,
				params(from, to, costCenters, limit)
						.addValue("offset", offset, Types.INTEGER),
				(rs, rowNum) -> toLine(rs)
		);
	}

	/*
	 * Giống findLines (cùng SQL, thứ tự thành tiền giảm dần) nhưng đọc từng dòng qua callback,
	 * không gom list – dùng cho export Excel. Tối đa limit dòng.
	 */
	public void streamLines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit,
			Consumer<CostTransactionLineDto> consumer
	) {

		jdbcTemplate.query(
				base(cost) + LINES_PAGE,
				params(from, to, costCenters, limit)
						.addValue("offset", 0, Types.INTEGER),
				(RowCallbackHandler) rs -> consumer.accept(toLine(rs))
		);
	}

	private static CostTransactionLineDto toLine(ResultSet rs) throws SQLException {

		Date usedDate = rs.getDate("UsedDate");

		return new CostTransactionLineDto(
				usedDate != null ? usedDate.toLocalDate() : null,
				rs.getString("Code"),
				rs.getString("Name"),
				rs.getString("Category"),
				getDouble(rs, "Qty"),
				rs.getString("QtyUnit"),
				getDouble(rs, "UnitPrice"),
				getDouble(rs, "Amt"),
				rs.getString("CcCode"),
				rs.getString("CcName"),
				rs.getString("Machine")
		);
	}

	// cost: "T&SL" hoặc "RM"
	public List<CostTopItemDto> findTopItems(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return new java.util.ArrayList<>(cached(key("topItems", cost, from, to, costCenters, limit), to, () -> {
			Double[] total = {null};
			List<CostTopItemDto> rows = jdbcTemplate.query(
					base(cost) + TOP_ITEMS,
					params(from, to, costCenters, limit),
					(rs, rowNum) -> {
						total[0] = getDouble(rs, "TotalK");
						return new CostTopItemDto(
								rs.getString("Code"),
								rs.getString("Name"),
								rs.getString("Category"),
								getDouble(rs, "Qty"),
								rs.getString("QtyUnit"),
								getDouble(rs, "AmtK")
						);
					}
			);
			seedTotal(cost, from, to, costCenters, total[0]);
			return rows;
		}));
	}

	public List<CostTopCostCenterDto> findTopCostCenters(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return new java.util.ArrayList<>(cached(key("topCostCenters", cost, from, to, costCenters, limit), to, () -> {
			Double[] total = {null};
			List<CostTopCostCenterDto> rows = jdbcTemplate.query(
					base(cost) + TOP_COST_CENTERS,
					params(from, to, costCenters, limit),
					(rs, rowNum) -> {
						total[0] = getDouble(rs, "TotalK");
						return new CostTopCostCenterDto(
								rs.getString("CcName"),
								rs.getString("CcCode"),
								getDouble(rs, "AmtK")
						);
					}
			);
			seedTotal(cost, from, to, costCenters, total[0]);
			return rows;
		}));
	}

	public List<CostTopMachineDto> findTopMachines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return new java.util.ArrayList<>(cached(key("topMachines", cost, from, to, costCenters, limit), to, () -> jdbcTemplate.query(
				base(cost) + TOP_MACHINES,
				params(from, to, costCenters, limit),
				(rs, rowNum) -> new CostTopMachineDto(
						rs.getString("Machine"),
						getDouble(rs, "AmtK")
				)
		)));
	}

	// Thống kê từng vật tư trong [from, to] (cả khoảng)
	public List<CostItemStatDto> itemStats(String cost, LocalDate from, LocalDate to, List<String> costCenters) {
		return new java.util.ArrayList<>(cached(key("itemStats", cost, from, to, costCenters, 0), to, () -> jdbcTemplate.query(
				base(cost) + ITEM_STATS, params(from, to, costCenters, 1), (rs, rowNum) -> toItemStat(rs))));
	}

	// Thống kê từng vật tư theo tháng (period = yyyy-MM)
	public List<CostItemStatDto> itemMonthly(String cost, LocalDate from, LocalDate to, List<String> costCenters) {
		return new java.util.ArrayList<>(cached(key("itemMonthly", cost, from, to, costCenters, 0), to, () -> jdbcTemplate.query(
				base(cost) + ITEM_MONTHLY, params(from, to, costCenters, 1), (rs, rowNum) -> toItemStat(rs))));
	}

	// 1 vật tư theo ngày (period = yyyy-MM-dd), tăng dần theo ngày
	public List<CostItemStatDto> itemDaily(String cost, LocalDate from, LocalDate to, List<String> costCenters, String code) {
		return new java.util.ArrayList<>(cached(key("itemDaily:" + code, cost, from, to, costCenters, 0), to, () -> jdbcTemplate.query(
				base(cost) + ITEM_DAILY,
				params(from, to, costCenters, 1).addValue("code", code, Types.NVARCHAR),
				(rs, rowNum) -> toItemStat(rs))));
	}

	private static CostItemStatDto toItemStat(ResultSet rs) throws SQLException {

		Date last = rs.getDate("LastDate");

		return new CostItemStatDto(
				rs.getString("Period"),
				rs.getString("Code"),
				rs.getString("Name"),
				rs.getString("Category"),
				rs.getString("QtyUnit"),
				getDouble(rs, "Qty"),
				getDouble(rs, "AmtK"),
				rs.getInt("Lines"),
				rs.getInt("Days"),
				getDouble(rs, "PosQty"),
				getDouble(rs, "PosAmt"),
				getDouble(rs, "MinPrice"),
				getDouble(rs, "MaxPrice"),
				last != null ? last.toLocalDate() : null
		);
	}

	// Tổng K$ của mọi dòng thỏa bộ lọc (gồm cả dòng âm); không có dòng -> null
	public Double findTotal(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		List<Double> result = cached(key("total", cost, from, to, costCenters, 0), to, () -> jdbcTemplate.query(
				base(cost) + TOTAL,
				params(from, to, costCenters, 1),
				(rs, rowNum) -> getDouble(rs, "AmtK")
		));

		return result.isEmpty() ? null : result.get(0);
	}

	public int countRows(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		Integer count = jdbcTemplate.queryForObject(
				base(cost) + COUNT_ROWS,
				params(from, to, costCenters, 1),
				Integer.class
		);

		return count != null ? count : 0;
	}

	// Category -> K$ (key "(trống)" nếu không có Category)
	public Map<String, Double> sumByCategory(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {
		return new LinkedHashMap<>(cached(key("sumByCategory", cost, from, to, costCenters, 0), to, () -> {
			Map<String, Double> map = sumByLabel(base(cost) + SUM_BY_CATEGORY, from, to, costCenters);
			// Mọi dòng đều thuộc 1 Category (kể cả "(trống)") -> tổng = tổng khung, nạp sẵn findTotal
			if (!map.isEmpty()) {
				seedTotal(cost, from, to, costCenters, map.values().stream().mapToDouble(Double::doubleValue).sum());
			}
			return map;
		}));
	}

	// Tổng K$ theo vật tư (Code + Text, trả riêng từng cột để tool tự ghép nhãn)
	public List<CostItemAmountDto> sumByItem(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		return new java.util.ArrayList<>(cached(key("sumByItem", cost, from, to, costCenters, 0), to, () -> jdbcTemplate.query(
				base(cost) + SUM_BY_ITEM,
				params(from, to, costCenters, 1),
				(rs, rowNum) -> new CostItemAmountDto(
						rs.getString("Code"),
						rs.getString("Name"),
						getDouble(rs, "AmtK")
				)
		)));
	}

	private Map<String, Double> sumByLabel(
			String sql,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		Map<String, Double> result = new LinkedHashMap<>();

		jdbcTemplate.query(
				sql,
				params(from, to, costCenters, 1),
				(RowCallbackHandler) rs -> {
					String label = rs.getString("Label");
					Double amount = getDouble(rs, "AmtK");
					// Label trùng (vd Code khác nhau nhưng rỗng) -> cộng dồn
					result.merge(
							label == null || label.isBlank() ? "(trống)" : label,
							amount != null ? amount : 0.0,
							Double::sum
					);
				}
		);

		return result;
	}

	// =========================================================
	// BẢNG GIAO DỊCH CÓ TÌM / LỌC / SẮP XẾP (drawer – /detail/transactions)
	//
	// Điều kiện đều chạy trên CTE B (cột đã chuẩn hóa) -> cùng phạm vi dòng với findLines / countRows.
	// 1 truy vấn cho cả trang + tổng (COUNT / SUM OVER ()), thay vì countRows + findTotal + findLines.
	// Không cache (bộ lọc rất đa dạng); phạm vi 1 khung × tối đa 1 tháng.
	// =========================================================

	// Chuỗi tìm: ghép các cột chữ (NULL -> ''), Đ/đ -> D/d, so sánh không phân biệt hoa thường / dấu
	private static final String SEARCH_TEXT =
			"(REPLACE(REPLACE(CONCAT(Code, N' ', Name, N' ', Category, N' ', CcCode, N' ', "
					+ "NULLIF(CcName, N'(trống)'), N' ', Machine), N'Đ', N'D'), N'đ', N'd') COLLATE Latin1_General_CI_AI)";

	// %1$s = điều kiện thêm (AND ...), %2$s = ORDER BY
	private static final String FILTERED_PAGE = """
			SELECT UsedDate,
			       Code,
			       Name,
			       Category,
			       Qty,
			       QtyUnit,
			       UnitPrice,
			       Amt,
			       CcCode,
			       CcName,
			       Machine,
			       COUNT(*) OVER ()          AS Cnt,
			       SUM(Amt) OVER () / 1000.0 AS TotK
			FROM B
			WHERE CC IN (:costCenters)
			%1$s
			ORDER BY %2$s
			OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
			""";

	private static final String FILTERED_SUMMARY = """
			SELECT COUNT(*) AS Cnt, SUM(Amt) / 1000.0 AS TotK
			FROM B
			WHERE CC IN (:costCenters)
			%1$s
			""";

	// Giá trị khác nhau của 1 cột + số dòng; Groups = tổng số giá trị (trước TOP). %1$s = biểu thức cột, %2$s = điều kiện
	private static final String FACET = """
			SELECT TOP (:limit)
			       %1$s             AS Value,
			       COUNT(*)         AS Cnt,
			       COUNT(*) OVER () AS Groups
			FROM B
			WHERE CC IN (:costCenters)
			%2$s
			GROUP BY %1$s
			ORDER BY CASE WHEN %1$s IS NULL THEN 1 ELSE 0 END, %1$s
			""";

	// Tie-breaker cố định để phân trang ổn định (không lặp / thiếu dòng giữa các trang).
	// Mỗi phần tử 1 cột: orderBy bỏ cột đang sắp xếp ra khỏi đây – SQL Server cấm 1 cột xuất hiện 2 lần
	// trong ORDER BY (Msg 169, trước đây sort=qty / amountK lỗi 500).
	private static final List<String> TIE_BREAKERS = List.of(
			"Amt DESC", "UsedDate", "Code", "Name", "CcName", "Machine", "Qty", "UnitPrice"
	);
	private static final String DEFAULT_ORDER = String.join(", ", TIE_BREAKERS);

	public record FilteredSummary(long total, Double totalK) {
	}

	public record FilteredPage(long total, Double totalK, List<CostTransactionLineDto> lines) {
	}

	public record FacetRow(String value, long count) {
	}

	// groups = tổng số giá trị khác nhau (có thể > rows.size() khi bị cắt bởi limit)
	public record FacetRows(long groups, List<FacetRow> rows) {
	}

	/*
	 * 1 trang giao dịch theo bộ lọc + tổng số dòng / tổng K$ của mọi dòng thỏa bộ lọc.
	 * Trang vượt quá số dòng -> không có dòng nào mang Cnt, đếm lại bằng summarizeFiltered.
	 */
	public FilteredPage findFilteredPage(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			CostTransactionFilter filter,
			int offset,
			int limit
	) {

		MapSqlParameterSource params = params(from, to, costCenters, limit)
				.addValue("offset", offset, Types.INTEGER);
		String sql = base(cost) + FILTERED_PAGE.formatted(conditions(filter, params), orderBy(filter));

		long[] total = {-1};
		Double[] totalK = {null};

		List<CostTransactionLineDto> lines = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
			total[0] = rs.getLong("Cnt");
			totalK[0] = getDouble(rs, "TotK");
			return toLine(rs);
		});

		if (total[0] < 0) {
			FilteredSummary summary = summarizeFiltered(cost, from, to, costCenters, filter);
			return new FilteredPage(summary.total(), summary.totalK(), List.of());
		}

		return new FilteredPage(total[0], totalK[0], lines);
	}

	// Số dòng + tổng K$ thỏa bộ lọc (không có dòng -> total 0, totalK null)
	public FilteredSummary summarizeFiltered(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			CostTransactionFilter filter
	) {

		MapSqlParameterSource params = params(from, to, costCenters, 1);
		String sql = base(cost) + FILTERED_SUMMARY.formatted(conditions(filter, params));

		List<FilteredSummary> rows = jdbcTemplate.query(sql, params,
				(rs, rowNum) -> new FilteredSummary(rs.getLong("Cnt"), getDouble(rs, "TotK")));

		return rows.isEmpty() ? new FilteredSummary(0, null) : rows.get(0);
	}

	// Như findFilteredPage nhưng đọc từng dòng qua callback (export), tối đa limit dòng
	public void streamFilteredLines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			CostTransactionFilter filter,
			int limit,
			Consumer<CostTransactionLineDto> consumer
	) {

		MapSqlParameterSource params = params(from, to, costCenters, limit)
				.addValue("offset", 0, Types.INTEGER);
		String sql = base(cost) + FILTERED_PAGE.formatted(conditions(filter, params), orderBy(filter));

		jdbcTemplate.query(sql, params, (RowCallbackHandler) rs -> consumer.accept(toLine(rs)));
	}

	/*
	 * Giá trị cho dropdown lọc của 1 cột: áp dụng từ khóa + bộ lọc các cột KHÁC (bỏ bộ lọc của chính cột này).
	 * facetQ: chỉ giữ giá trị chứa chuỗi này (không phân biệt hoa thường / dấu). Giá trị trống -> null.
	 * Thứ tự: theo giá trị tăng dần (ngày: yyyy-MM-dd nên đúng thứ tự thời gian), trống cuối cùng.
	 */
	public FacetRows findFacet(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			CostTransactionFilter filter,
			CostTransactionFilter.FacetColumn column,
			String facetQ,
			int limit
	) {

		MapSqlParameterSource params = params(from, to, costCenters, limit);
		String where = conditions(filter.without(column), params);

		String q = searchToken(facetQ);
		if (!q.isEmpty()) {
			where += "  AND (REPLACE(REPLACE(" + column.expression() + ", N'Đ', N'D'), N'đ', N'd') "
					+ "COLLATE Latin1_General_CI_AI) LIKE :facetQ ESCAPE '\\'\n";
			params.addValue("facetQ", "%" + likeEscape(q) + "%", Types.NVARCHAR);
		}

		long[] groups = {0};
		List<FacetRow> rows = jdbcTemplate.query(
				base(cost) + FACET.formatted(column.expression(), where),
				params,
				(rs, rowNum) -> {
					groups[0] = rs.getLong("Groups");
					return new FacetRow(rs.getString("Value"), rs.getLong("Cnt"));
				}
		);

		return new FacetRows(groups[0], rows);
	}

	// AND ... cho bộ lọc; giá trị đưa vào params (không ghép chuỗi từ request vào SQL)
	private static String conditions(CostTransactionFilter filter, MapSqlParameterSource params) {

		StringBuilder sb = new StringBuilder();

		List<String> tokens = filter.tokens().stream()
				.map(CostTopItemsRepository::searchToken)
				.filter(t -> !t.isEmpty())
				.toList();

		for (int i = 0; i < tokens.size(); i++) {
			sb.append("  AND ").append(SEARCH_TEXT).append(" LIKE :q").append(i).append(" ESCAPE '\\'\n");
			params.addValue("q" + i, "%" + likeEscape(tokens.get(i)) + "%", Types.NVARCHAR);
		}

		if (!filter.dates().isEmpty()) {
			sb.append("  AND UsedDate IN (:fDates)\n");
			params.addValue("fDates", filter.dates().stream().map(Date::valueOf).toList());
		}

		inClause(sb, params, "Category", "fCategory", filter.categories());
		inClause(sb, params, "NULLIF(CcName, N'(trống)')", "fCostCenter", filter.costCenters());
		inClause(sb, params, "Machine", "fMachine", filter.machines());

		return sb.toString();
	}

	// values có "" -> thêm "OR cột IS NULL" (ô trống)
	private static void inClause(
			StringBuilder sb,
			MapSqlParameterSource params,
			String expression,
			String name,
			List<String> values
	) {

		if (values.isEmpty()) {
			return;
		}

		List<String> nonBlank = values.stream()
				.filter(v -> v != null && !v.isBlank())
				.map(String::trim)
				.distinct()
				.toList();
		boolean blank = values.stream().anyMatch(v -> v == null || v.isBlank());

		List<String> parts = new java.util.ArrayList<>();

		if (!nonBlank.isEmpty()) {
			parts.add(expression + " IN (:" + name + ")");
			params.addValue(name, nonBlank);
		}
		if (blank) {
			parts.add(expression + " IS NULL");
		}

		sb.append("  AND (").append(String.join(" OR ", parts)).append(")\n");
	}

	// Cột sắp xếp lấy từ enum (whitelist); NULL luôn cuối; sau đó tie-breaker cố định
	private static String orderBy(CostTransactionFilter filter) {

		if (filter.sort() == null) {
			return DEFAULT_ORDER;
		}

		String column = filter.sort().column();

		// tie-breaker trừ cột đang sắp xếp ("Amt DESC" -> cột "Amt")
		String rest = TIE_BREAKERS.stream()
				.filter(t -> !t.split(" ")[0].equals(column))
				.collect(java.util.stream.Collectors.joining(", "));

		return "CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END, "
				+ column + (filter.ascending() ? " ASC" : " DESC") + ", "
				+ rest;
	}

	// Đ/đ -> D/d (khớp REPLACE phía SQL); bỏ khoảng trắng thừa
	private static String searchToken(String value) {
		return value == null ? "" : value.trim().replace('Đ', 'D').replace('đ', 'd');
	}

	// Ký tự đặc biệt của LIKE (dùng ESCAPE '\')
	private static String likeEscape(String value) {
		return value
				.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_")
				.replace("[", "\\[");
	}

	private static String base(String cost) {

		return switch (cost) {
			case "T&SL" -> TSL_BASE;
			case "RM" -> RM_BASE;
			default -> throw new IllegalArgumentException("cost must be T&SL or RM");
		};
	}

	private static MapSqlParameterSource params(
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return new MapSqlParameterSource()
				.addValue("fromDate", Date.valueOf(from), Types.DATE)
				.addValue("toDate", Date.valueOf(to), Types.DATE)
				.addValue("costCenters", costCenters)
				.addValue("limit", limit, Types.INTEGER);
	}

	private static Double getDouble(ResultSet rs, String column) throws SQLException {

		Object value = rs.getObject(column);

		return value instanceof Number number ? number.doubleValue() : null;
	}
}
