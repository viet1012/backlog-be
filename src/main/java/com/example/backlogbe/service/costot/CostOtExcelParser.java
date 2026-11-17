package com.example.backlogbe.service.costot;

import com.example.backlogbe.repository.costmonitoring.CostOtRepository.OtRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// =========================================================
// ĐỌC ACTUAL OT TỪ FILE "FY26 Daily Cost monitoning_FY26.xlsx"
//
// Mỗi sheet COGS có cùng bố cục (kiểm 2026-11-17):
//   dòng 3: ... "Labor Cost" (G) ... "Material Cost" (N)
//   dòng 4: "FC" (G) ... "Actual" (J)
//   dòng 5: "Basic" "OT" "TTL" (G-I: FC) | "Basic" "OT" "TTL" (J-L: Actual)
//   từ dòng 7: cột B = ngày (cả năm tài chính, mỗi tháng có thêm dòng "Total" / "Variance")
// -> cột OT = ô "OT" đầu tiên bên phải (hoặc ngay dưới) ô "Actual" đầu tiên; hiện là K.
// Dò theo tiêu đề và theo ngày ở cột B, KHÔNG dùng số dòng cố định như Excel Cost Monitoring (K625...).
//
// Ô công thức: lấy giá trị Excel đã tính sẵn (cached) -> file phải được lưu bằng Excel sau khi tính.
// Ô trống: bỏ qua (chưa có số). Ô lỗi / chữ: bỏ qua + cảnh báo. Số âm: giữ nguyên (SQL quyết định).
// Đơn vị K$ (G2 "Currency: KUSD").
// =========================================================
public final class CostOtExcelParser {

	// sheet -> SubDiv (Guide = PG + MG, giống Guide_OT!P = AB + AC). "DP only" đã nằm trong PG+DP-COGS -> không đọc.
	public static final Map<String, String> SHEET_SUBDIV;

	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("PressCOGS", "PR");
		m.put("MoldCOGS", "MO");
		m.put("PG+DP-COGS", "PG");
		m.put("MG-COGS", "MG");
		SHEET_SUBDIV = java.util.Collections.unmodifiableMap(m);
	}

	private static final int HEADER_SCAN_ROWS = 15;
	private static final int DATE_SCAN_COLS = 4;
	private static final int MAX_WARNINGS = 50;

	private CostOtExcelParser() {
	}

	public record SheetResult(
			String sheet,
			String subDiv,
			List<OtRow> rows
	) {
	}

	public record ParseResult(
			List<SheetResult> sheets,
			List<String> warnings
	) {
	}

	/*
	 * month null = mọi tháng có trong file; ngày sau maxDate bị bỏ (chưa phát sinh).
	 */
	public static ParseResult parse(InputStream in, YearMonth month, LocalDate maxDate) throws IOException {

		List<String> warnings = new ArrayList<>();
		List<SheetResult> sheets = new ArrayList<>();

		try (Workbook wb = WorkbookFactory.create(in)) {

			List<String> missing = SHEET_SUBDIV.keySet().stream()
					.filter(name -> wb.getSheet(name) == null)
					.toList();

			if (!missing.isEmpty()) {
				throw new IllegalArgumentException(
						"File thiếu sheet " + String.join(", ", missing)
								+ ". Cần file \"FY.. Daily Cost monitoning\" có các sheet " + String.join(", ", SHEET_SUBDIV.keySet()) + ".");
			}

			for (Map.Entry<String, String> e : SHEET_SUBDIV.entrySet()) {
				sheets.add(parseSheet(wb.getSheet(e.getKey()), e.getValue(), month, maxDate, warnings));
			}
		} catch (IllegalArgumentException ex) {
			throw ex;
		} catch (IOException | RuntimeException ex) {
			// File hỏng / không phải xlsx / có mật khẩu
			throw new IllegalArgumentException("Không đọc được file Excel (.xlsx). Kiểm tra file không có mật khẩu và không bị hỏng.");
		}

		if (warnings.size() > MAX_WARNINGS) {
			int more = warnings.size() - MAX_WARNINGS;
			List<String> cut = new ArrayList<>(warnings.subList(0, MAX_WARNINGS));
			cut.add("... và " + more + " cảnh báo khác");
			warnings = cut;
		}

		return new ParseResult(sheets, warnings);
	}


	private static SheetResult parseSheet(
			Sheet sheet,
			String subDiv,
			YearMonth month,
			LocalDate maxDate,
			List<String> warnings
	) {

		String name = sheet.getSheetName();
		int[] header = findOtHeader(sheet);

		if (header == null) {
			throw new IllegalArgumentException(
					"Sheet " + name + ": không tìm thấy cột \"Actual / OT\" trong " + HEADER_SCAN_ROWS + " dòng đầu.");
		}

		int headerRow = header[0];
		int otCol = header[1];
		int dateCol = findDateColumn(sheet, headerRow);

		if (dateCol < 0) {
			throw new IllegalArgumentException("Sheet " + name + ": không tìm thấy cột ngày (cột B).");
		}

		// Ngày trùng: giữ dòng sau + cảnh báo
		TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>();

		for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {

			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}

			LocalDate date = dateValue(row.getCell(dateCol));
			if (date == null) {
				continue;   // dòng "Total", "Variance", trống
			}

			if (month != null && !YearMonth.from(date).equals(month)) {
				continue;
			}

			if (maxDate != null && date.isAfter(maxDate)) {
				continue;
			}

			Cell cell = row.getCell(otCol);
			CellType type = cellType(cell);

			if (type == null || type == CellType.BLANK) {
				continue;   // chưa có số
			}

			if (type != CellType.NUMERIC) {
				warnings.add(name + " dòng " + (r + 1) + " (" + date + "): ô OT không phải số – bỏ qua.");
				continue;
			}

			BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue()).setScale(6, RoundingMode.HALF_UP);

			if (byDate.put(date, value) != null) {
				warnings.add(name + ": ngày " + date + " xuất hiện nhiều lần – lấy dòng " + (r + 1) + ".");
			}
		}

		List<OtRow> rows = byDate.entrySet().stream()
				.map(en -> new OtRow(en.getKey(), subDiv, en.getValue()))
				.toList();

		return new SheetResult(name, subDiv, rows);
	}

	// {dòng tiêu đề "OT", cột OT} hoặc null
	private static int[] findOtHeader(Sheet sheet) {

		for (int r = 0; r < HEADER_SCAN_ROWS; r++) {

			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}

			int actualCol = -1;
			for (Cell c : row) {
				if ("actual".equals(text(c))) {
					actualCol = c.getColumnIndex();
					break;
				}
			}

			if (actualCol < 0) {
				continue;
			}

			// "OT" ở 1-2 dòng dưới, cột >= cột "Actual"
			for (int r2 = r + 1; r2 <= r + 2; r2++) {
				Row sub = sheet.getRow(r2);
				if (sub == null) {
					continue;
				}
				int best = -1;
				for (Cell c : sub) {
					if (c.getColumnIndex() >= actualCol && "ot".equals(text(c))
							&& (best < 0 || c.getColumnIndex() < best)) {
						best = c.getColumnIndex();
					}
				}
				if (best >= 0) {
					return new int[]{r2, best};
				}
			}
		}

		return null;
	}

	// Cột (A..D) có ô ngày đầu tiên dưới tiêu đề
	private static int findDateColumn(Sheet sheet, int headerRow) {

		int last = Math.min(sheet.getLastRowNum(), headerRow + 40);

		for (int r = headerRow + 1; r <= last; r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			for (int c = 0; c < DATE_SCAN_COLS; c++) {
				if (dateValue(row.getCell(c)) != null) {
					return c;
				}
			}
		}

		return -1;
	}

	private static LocalDate dateValue(Cell cell) {

		if (cellType(cell) != CellType.NUMERIC) {
			return null;
		}

		try {
			if (!DateUtil.isCellDateFormatted(cell)) {
				return null;
			}
			double v = cell.getNumericCellValue();
			if (!DateUtil.isValidExcelDate(v)) {
				return null;
			}
			LocalDate d = DateUtil.getLocalDateTime(v).toLocalDate();
			return d.getYear() >= 2000 && d.getYear() <= 2100 ? d : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	// Kiểu thực của ô; ô công thức -> kiểu kết quả đã tính sẵn
	private static CellType cellType(Cell cell) {

		if (cell == null) {
			return null;
		}

		CellType type = cell.getCellType();
		return type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
	}

	private static String text(Cell cell) {
		return cellType(cell) == CellType.STRING
				? cell.getStringCellValue().trim().toLowerCase(Locale.ROOT)
				: null;
	}
}
