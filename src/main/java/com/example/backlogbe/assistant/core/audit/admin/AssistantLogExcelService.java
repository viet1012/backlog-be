package com.example.backlogbe.assistant.core.audit.admin;

import com.example.backlogbe.assistant.core.audit.admin.AssistantLogQueryService.ExportPlan;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

// =========================================================
// EXPORT AUDIT LOG TRỢ LÝ AI ra .xlsx (đủ field chính, kể cả câu trả lời).
// SXSSF: chỉ giữ ROW_WINDOW dòng trong bộ nhớ; ≤ AssistantLogQueryService.MAX_EXPORT_ROWS dòng.
// Ô văn bản cắt ở giới hạn Excel (32 767 ký tự).
// =========================================================
@Service
public class AssistantLogExcelService {

	private static final int ROW_WINDOW = 200;

	private static final int MAX_CELL_TEXT = 32_767;

	private static final String SHEET_NAME = "Assistant logs";

	private static final DateTimeFormatter EXPORTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final List<String> COLUMNS = List.of(
			"Timestamp", "RID", "Source", "User ID", "Screen", "Lang", "OK", "Error code", "Error message",
			"Total ms", "Context ms", "Asked months ms", "Prefetch ms", "LLM calls", "Prompt tokens",
			"Completion tokens", "Tools", "Model", "Reasoning", "Params", "Question", "Answer"
	);

	private static final int[] COLUMN_WIDTHS = {
			26, 10, 8, 12, 18, 6, 6, 16, 40,
			10, 10, 10, 10, 9, 12,
			12, 40, 22, 10, 40, 60, 100
	};

	public void export(OutputStream outputStream, ExportPlan plan) throws IOException {

		SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);

		try {
			workbook.setCompressTempFiles(true);

			Styles styles = Styles.create(workbook);
			Sheet sheet = workbook.createSheet(SHEET_NAME);

			for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
				sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
			}

			int headerRow = writeInfo(sheet, plan, styles);
			writeHeader(sheet, headerRow, styles);

			int r = headerRow + 1;

			for (AssistantLogEntry e : plan.rows()) {
				writeRow(sheet.createRow(r++), e, styles);
			}

			sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(r - 1, headerRow), 0, COLUMNS.size() - 1));
			sheet.createFreezePane(0, headerRow + 1);

			workbook.write(outputStream);
			outputStream.flush();

		} finally {
			// Xoá file tạm poi-sxssf-*.gz (close() không tự xoá, xem docs/API_AUDIT.md)
			workbook.dispose();
			workbook.close();
		}
	}

	private static int writeInfo(Sheet sheet, ExportPlan plan, Styles styles) {

		AssistantLogFilter f = plan.filter();

		int r = 0;

		text(sheet.createRow(r++), 0, "AI Assistant – Audit logs", styles.title);

		r = info(sheet, r, "Period", f.from() + " ~ " + f.to(), styles);
		r = info(sheet, r, "Filter", describe(f), styles);
		r = info(sheet, r, "Rows", plan.truncated()
				? plan.rows().size() + " / " + plan.totalMatched() + " (truncated)"
				: String.valueOf(plan.rows().size()), styles);
		r = info(sheet, r, "Exported at", LocalDateTime.now().format(EXPORTED_AT_FORMAT), styles);

		return r + 1;
	}

	private static String describe(AssistantLogFilter f) {

		StringBuilder sb = new StringBuilder();

		append(sb, "userId", f.userId());
		append(sb, "q", f.q());
		append(sb, "ok", f.ok() != null ? f.ok().toString() : null);
		append(sb, "source", f.source());
		append(sb, "minMs", f.minMs() != null ? f.minMs().toString() : null);

		return sb.length() == 0 ? "-" : sb.toString();
	}

	private static void append(StringBuilder sb, String name, String value) {
		if (value != null) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(name).append('=').append(value);
		}
	}

	private static int info(Sheet sheet, int rowIndex, String label, String value, Styles styles) {

		Row row = sheet.createRow(rowIndex);
		text(row, 0, label, styles.bold);
		text(row, 1, value, null);

		return rowIndex + 1;
	}

	private static void writeHeader(Sheet sheet, int rowIndex, Styles styles) {

		Row row = sheet.createRow(rowIndex);

		for (int i = 0; i < COLUMNS.size(); i++) {
			text(row, i, COLUMNS.get(i), styles.header);
		}
	}

	private static void writeRow(Row row, AssistantLogEntry e, Styles styles) {

		int c = 0;

		text(row, c++, e.timestamp().toString(), null);
		text(row, c++, e.rid(), null);
		text(row, c++, e.source(), null);
		text(row, c++, e.userId(), null);
		text(row, c++, e.screenKey(), null);
		text(row, c++, e.lang(), null);
		text(row, c++, e.ok() ? "Y" : "N", null);
		text(row, c++, e.errorCode(), null);
		text(row, c++, e.errorMessage(), null);
		number(row, c++, e.totalMs(), styles.integer);
		number(row, c++, e.contextMs(), styles.integer);
		number(row, c++, e.askedMonthsMs(), styles.integer);
		number(row, c++, e.prefetchMs(), styles.integer);
		number(row, c++, (long) e.llmCallCount(), styles.integer);
		number(row, c++, e.promptTokens(), styles.integer);
		number(row, c++, e.completionTokens(), styles.integer);
		text(row, c++, e.tools(), null);
		text(row, c++, e.model(), null);
		text(row, c++, e.reasoningEffort(), null);
		text(row, c++, e.params(), null);
		text(row, c++, e.question(), null);
		text(row, c, e.answer(), null);
	}

	private static void text(Row row, int col, String value, CellStyle style) {

		if (value == null) {
			return;
		}

		Cell cell = row.createCell(col);
		cell.setCellValue(value.length() > MAX_CELL_TEXT ? value.substring(0, MAX_CELL_TEXT) : value);

		if (style != null) {
			cell.setCellStyle(style);
		}
	}

	private static void number(Row row, int col, Long value, CellStyle style) {

		if (value == null) {
			return;
		}

		Cell cell = row.createCell(col);
		cell.setCellValue(value);
		cell.setCellStyle(style);
	}

	private record Styles(CellStyle title, CellStyle bold, CellStyle header, CellStyle integer) {

		static Styles create(Workbook wb) {

			Font boldFont = wb.createFont();
			boldFont.setBold(true);

			Font titleFont = wb.createFont();
			titleFont.setBold(true);
			titleFont.setFontHeightInPoints((short) 14);

			CellStyle title = wb.createCellStyle();
			title.setFont(titleFont);

			CellStyle bold = wb.createCellStyle();
			bold.setFont(boldFont);

			CellStyle header = wb.createCellStyle();
			header.setFont(boldFont);
			header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
			header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			header.setBorderBottom(BorderStyle.THIN);
			header.setBorderTop(BorderStyle.THIN);
			header.setBorderLeft(BorderStyle.THIN);
			header.setBorderRight(BorderStyle.THIN);

			CellStyle integer = wb.createCellStyle();
			integer.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

			return new Styles(title, bold, header, integer);
		}
	}
}
