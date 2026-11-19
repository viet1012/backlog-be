package com.example.backlogbe.assistant.screens.costmonitoring.repository;

import java.time.LocalDate;
import java.util.List;

/*
 * Tìm / lọc / sắp xếp bảng giao dịch drawer Cost Monitoring (server-side).
 * Dùng bởi CostTopItemsRepository (findFilteredPage / summarizeFiltered / streamFilteredLines / findFacet).
 *
 * tokens     : từ khóa đã tách theo khoảng trắng, AND; tìm trong Code, Name, Category, CcCode, CcName, Machine
 *              (không phân biệt hoa thường / dấu; Đ = D). Chưa escape – repository tự escape cho LIKE.
 * dates      : UsedDate IN (...)
 * categories / costCenters / machines: giá trị IN (...), OR trong cùng cột, AND giữa các cột.
 *              "" = ô trống (Category / Machine NULL; Cost center tên trống, BE hiển thị "(trống)").
 *              costCenters so theo TÊN cost center (costCenterName – đúng giá trị FE hiển thị).
 * sort       : null = thứ tự mặc định (thành tiền giảm dần).
 * Danh sách rỗng = không lọc cột đó.
 */
public record CostTransactionFilter(
		List<String> tokens,
		List<LocalDate> dates,
		List<String> categories,
		List<String> costCenters,
		List<String> machines,
		SortField sort,
		boolean ascending
) {

	public static final CostTransactionFilter NONE =
			new CostTransactionFilter(List.of(), List.of(), List.of(), List.of(), List.of(), null, false);

	public CostTransactionFilter {
		tokens = List.copyOf(tokens);
		dates = List.copyOf(dates);
		categories = List.copyOf(categories);
		costCenters = List.copyOf(costCenters);
		machines = List.copyOf(machines);
	}

	// Không tìm / lọc / sắp xếp gì -> dùng đường truy vấn cũ (giữ nguyên hành vi)
	public boolean isEmpty() {
		return tokens.isEmpty() && dates.isEmpty() && categories.isEmpty()
				&& costCenters.isEmpty() && machines.isEmpty() && sort == null;
	}

	// Có điều kiện WHERE (tìm / lọc) – sắp xếp không tính
	public boolean hasConditions() {
		return !tokens.isEmpty() || !dates.isEmpty() || !categories.isEmpty()
				|| !costCenters.isEmpty() || !machines.isEmpty();
	}

	// Facet kiểu Excel: bỏ bộ lọc của chính cột đang hỏi (vẫn bỏ chọn / chọn thêm được)
	public CostTransactionFilter without(FacetColumn column) {
		return new CostTransactionFilter(
				tokens,
				column == FacetColumn.DATE ? List.of() : dates,
				column == FacetColumn.CATEGORY ? List.of() : categories,
				column == FacetColumn.COST_CENTER ? List.of() : costCenters,
				column == FacetColumn.MACHINE ? List.of() : machines,
				null,
				false
		);
	}

	// Cột sắp xếp được: tên tham số API -> cột của CTE B (whitelist, không ghép chuỗi từ request)
	public enum SortField {
		DATE("date", "UsedDate"),
		QTY("qty", "Qty"),
		UNIT_PRICE("unitPrice", "UnitPrice"),
		AMOUNT("amountK", "Amt");

		private final String param;
		private final String column;

		SortField(String param, String column) {
			this.param = param;
			this.column = column;
		}

		public String param() {
			return param;
		}

		public String column() {
			return column;
		}

		public static SortField fromParam(String value) {
			for (SortField f : values()) {
				if (f.param.equalsIgnoreCase(value)) {
					return f;
				}
			}
			return null;
		}
	}

	// Cột có dropdown lọc: tên tham số API -> biểu thức trên CTE B
	public enum FacetColumn {
		DATE("date", "CONVERT(VARCHAR(10), UsedDate, 23)"),
		CATEGORY("category", "Category"),
		COST_CENTER("costCenter", "NULLIF(CcName, N'(trống)')"),
		MACHINE("machine", "Machine");

		private final String param;
		private final String expression;

		FacetColumn(String param, String expression) {
			this.param = param;
			this.expression = expression;
		}

		public String param() {
			return param;
		}

		public String expression() {
			return expression;
		}

		public static FacetColumn fromParam(String value) {
			for (FacetColumn c : values()) {
				if (c.param.equalsIgnoreCase(value)) {
					return c;
				}
			}
			return null;
		}
	}
}
