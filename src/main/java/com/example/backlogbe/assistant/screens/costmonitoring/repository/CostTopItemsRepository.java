package com.example.backlogbe.assistant.screens.costmonitoring.repository;

import com.example.backlogbe.assistant.screens.costmonitoring.dto.CostItemAmountDto;
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
			    WHERE LEFT(x.Factory, 3) = '612'
			      AND TRY_CONVERT(INT, x.GLAccount) IN (570300, 570400)
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
			      AND u.UD BETWEEN :fromDate AND :toDate
			      AND ISNULL(TRY_CONVERT(INT, x.GLAccount), 0) <> 580000
			      AND ISNULL(TRY_CONVERT(INT, x.Movingtype), 0) NOT IN (551, 552)
			)
			""").formatted(
			CODE_COL, NAME_COL, CATEGORY_COL, QTY_UNIT_COL, QTY_COL, AMOUNT_COL,
			CC_CODE_COL, CC_NAME_COL, MACHINE_COL, RM_UNIT_PRICE_COL, DATE_COL
	);

	private static final String TOP_ITEMS = """
			SELECT TOP (:limit)
			       Code,
			       MAX(Name)          AS Name,
			       MAX(Category)      AS Category,
			       QtyUnit,
			       SUM(Qty)           AS Qty,
			       SUM(Amt) / 1000.0  AS AmtK
			FROM B
			WHERE CC IN (:costCenters)
			GROUP BY Code, QtyUnit
			ORDER BY SUM(Amt) DESC
			""";

	private static final String TOP_COST_CENTERS = """
			SELECT TOP (:limit)
			       CcName,
			       MAX(CcCode)        AS CcCode,
			       SUM(Amt) / 1000.0  AS AmtK
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

		return jdbcTemplate.query(
				base(cost) + TOP_ITEMS,
				params(from, to, costCenters, limit),
				(rs, rowNum) -> new CostTopItemDto(
						rs.getString("Code"),
						rs.getString("Name"),
						rs.getString("Category"),
						getDouble(rs, "Qty"),
						rs.getString("QtyUnit"),
						getDouble(rs, "AmtK")
				)
		);
	}

	public List<CostTopCostCenterDto> findTopCostCenters(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return jdbcTemplate.query(
				base(cost) + TOP_COST_CENTERS,
				params(from, to, costCenters, limit),
				(rs, rowNum) -> new CostTopCostCenterDto(
						rs.getString("CcName"),
						rs.getString("CcCode"),
						getDouble(rs, "AmtK")
				)
		);
	}

	public List<CostTopMachineDto> findTopMachines(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters,
			int limit
	) {

		return jdbcTemplate.query(
				base(cost) + TOP_MACHINES,
				params(from, to, costCenters, limit),
				(rs, rowNum) -> new CostTopMachineDto(
						rs.getString("Machine"),
						getDouble(rs, "AmtK")
				)
		);
	}

	// Tổng K$ của mọi dòng thỏa bộ lọc (gồm cả dòng âm); không có dòng -> null
	public Double findTotal(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		List<Double> result = jdbcTemplate.query(
				base(cost) + TOTAL,
				params(from, to, costCenters, 1),
				(rs, rowNum) -> getDouble(rs, "AmtK")
		);

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
		return sumByLabel(base(cost) + SUM_BY_CATEGORY, from, to, costCenters);
	}

	// Tổng K$ theo vật tư (Code + Text, trả riêng từng cột để tool tự ghép nhãn)
	public List<CostItemAmountDto> sumByItem(
			String cost,
			LocalDate from,
			LocalDate to,
			List<String> costCenters
	) {

		return jdbcTemplate.query(
				base(cost) + SUM_BY_ITEM,
				params(from, to, costCenters, 1),
				(rs, rowNum) -> new CostItemAmountDto(
						rs.getString("Code"),
						rs.getString("Name"),
						getDouble(rs, "AmtK")
				)
		);
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
