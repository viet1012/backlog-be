package com.example.backlogbe.service;

import com.example.backlogbe.dto.costmonitoring.CostDetailResponse.Transaction;
import com.example.backlogbe.service.CostMonitoringDetailService.TransactionExport;
import com.example.backlogbe.service.CostMonitoringDetailService.TransactionScope;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

// =========================================================
// COST MONITORING – EXPORT GIAO DỊCH (div × chart × khoảng ngày) ra .xlsx
//
// Dữ liệu: CostMonitoringDetailService.forEachTransaction (cùng bộ lọc / thứ tự với bảng drawer:
// thành tiền giảm dần, chỉ cost center trực tiếp, không gồm phân bổ F2 / HT2 / HT-G).
// SXSSF: chỉ giữ ROW_WINDOW dòng trong bộ nhớ; tối đa MAX_EXPORT_ROWS dòng dữ liệu.
// =========================================================
@Service
@RequiredArgsConstructor
public class CostMonitoringTransactionExcelService {

	private static final int ROW_WINDOW = 200;

	private static final String SHEET_NAME = "Transactions";

	private static final DateTimeFormatter EXPORTED_AT_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// Cột giống bảng drawer (CostDetailResponse.Transaction) – nhãn / độ rộng (đơn vị 1/256 ký tự)
	private static final List<String> COLUMNS = List.of(
			"Date", "Code", "Item name", "Category", "Qty", "Unit",
			"Unit price", "Amount ($)", "Amount (K$)", "Cost center", "Cost center name", "Machine"
	);

	private static final int[] COLUMN_WIDTHS = {
			12, 18, 40, 20, 12, 8,
			14, 16, 14, 12, 28, 16
	};

	private final CostMonitoringDetailService detailService;


	public void export(OutputStream outputStream, TransactionExport plan) throws IOException {

		try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW)) {

			workbook.setCompressTempFiles(true);

			Styles styles = Styles.create(workbook);
			Sheet sheet = workbook.createSheet(SHEET_NAME);

			for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
				sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
			}

			int headerRow = writeInfo(sheet, plan, styles);
			writeHeader(sheet, headerRow, styles);

			int firstDataRow = headerRow + 1;
			int[] next = {firstDataRow};

			detailService.forEachTransaction(plan, tx -> writeRow(sheet.createRow(next[0]++), tx, styles));

			int lastRow = Math.max(next[0] - 1, headerRow);

			sheet.setAutoFilter(new CellRangeAddress(headerRow, lastRow, 0, COLUMNS.size() - 1));
			sheet.createFreezePane(0, firstDataRow);

			workbook.write(outputStream);
			outputStream.flush();
		}
	}


	// =========================================================
	// HEADER: div, chart, khoảng ngày, đơn vị, số dòng, tổng
	// =========================================================

	// Trả về chỉ số dòng tiêu đề cột
	private static int writeInfo(Sheet sheet, TransactionExport plan, Styles styles) {

		TransactionScope scope = plan.scope();

		int r = 0;

		Row title = sheet.createRow(r++);
		text(title, 0, "Cost Monitoring – Transactions", styles.title);

		r = info(sheet, r, "Div", scope.divCode(), styles);
		r = info(sheet, r, "Chart", scope.cost(), styles);
		r = info(sheet, r, "Period", scope.from() + " ~ " + scope.to(), styles);
		r = info(sheet, r, "Unit", "Unit price, Amount: $ ; Amount (K$) = Amount / 1000", styles);
		r = info(sheet, r, "Scope",
				"Direct cost centers only (excludes F2 / HT2 / HT-G allocation); sorted by amount, descending",
				styles);
		r = info(sheet, r, "Rows", String.valueOf(plan.rowCount()), styles);
		r = info(sheet, r, "Total (K$)",
				plan.totalAmountK() != null ? String.format(Locale.ROOT, "%,.2f", plan.totalAmountK()) : "-",
				styles);
		r = info(sheet, r, "Exported at", LocalDateTime.now().format(EXPORTED_AT_FORMAT), styles);

		return r + 1; // 1 dòng trống trước bảng
	}

	private static int info(Sheet sheet, int rowIndex, String label, String value, Styles styles) {

		Row row = sheet.createRow(rowIndex);
		text(row, 0, label, styles.infoLabel);
		text(row, 1, value, null);

		return rowIndex + 1;
	}

	private static void writeHeader(Sheet sheet, int rowIndex, Styles styles) {

		Row row = sheet.createRow(rowIndex);

		for (int i = 0; i < COLUMNS.size(); i++) {
			text(row, i, COLUMNS.get(i), styles.header);
		}
	}


	// =========================================================
	// DATA
	// =========================================================

	private static void writeRow(Row row, Transaction tx, Styles styles) {

		date(row, 0, tx.date(), styles.date);
		text(row, 1, tx.code(), null);
		text(row, 2, tx.name(), null);
		text(row, 3, tx.category(), null);
		number(row, 4, tx.qty(), styles.qty);
		text(row, 5, tx.qtyUnit(), null);
		number(row, 6, tx.unitPrice(), styles.money);
		number(row, 7, tx.amount(), styles.money);
		number(row, 8, tx.amountK(), styles.money);
		text(row, 9, tx.costCenter(), null);
		text(row, 10, tx.costCenterName(), null);
		text(row, 11, tx.machine(), null);
	}

	private static void text(Row row, int col, String value, CellStyle style) {

		if (value == null) {
			return;
		}

		Cell cell = row.createCell(col);
		cell.setCellValue(value);

		if (style != null) {
			cell.setCellStyle(style);
		}
	}

	private static void number(Row row, int col, Double value, CellStyle style) {

		if (value == null) {
			return;
		}

		Cell cell = row.createCell(col);
		cell.setCellValue(value);
		cell.setCellStyle(style);
	}

	private static void date(Row row, int col, LocalDate value, CellStyle style) {

		if (value == null) {
			return;
		}

		Cell cell = row.createCell(col);
		cell.setCellValue(value);
		cell.setCellStyle(style);
	}


	// =========================================================
	// STYLE
	// =========================================================

	private record Styles(
			CellStyle title,
			CellStyle infoLabel,
			CellStyle header,
			CellStyle date,
			CellStyle qty,
			CellStyle money
	) {

		static Styles create(Workbook wb) {

			Font bold = wb.createFont();
			bold.setBold(true);

			Font titleFont = wb.createFont();
			titleFont.setBold(true);
			titleFont.setFontHeightInPoints((short) 14);

			CellStyle title = wb.createCellStyle();
			title.setFont(titleFont);

			CellStyle infoLabel = wb.createCellStyle();
			infoLabel.setFont(bold);

			CellStyle header = wb.createCellStyle();
			header.setFont(bold);
			header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			header.setBorderBottom(BorderStyle.THIN);
			header.setBorderTop(BorderStyle.THIN);
			header.setBorderLeft(BorderStyle.THIN);
			header.setBorderRight(BorderStyle.THIN);

			short dateFormat = wb.createDataFormat().getFormat("yyyy-mm-dd");
			short qtyFormat = wb.createDataFormat().getFormat("General");
			short moneyFormat = wb.createDataFormat().getFormat("#,##0.00");

			CellStyle date = wb.createCellStyle();
			date.setDataFormat(dateFormat);

			CellStyle qty = wb.createCellStyle();
			qty.setDataFormat(qtyFormat);

			CellStyle money = wb.createCellStyle();
			money.setDataFormat(moneyFormat);

			return new Styles(title, infoLabel, header, date, qty, money);
		}
	}
}
