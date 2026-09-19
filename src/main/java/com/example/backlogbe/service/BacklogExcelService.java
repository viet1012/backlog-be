package com.example.backlogbe.service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backlogbe.dto.backlog.BacklogExcelExportRequest;
import com.example.backlogbe.dto.backlog.BacklogFilterItem;
import com.example.backlogbe.dto.backlog.BacklogFilterRequest;
import com.example.backlogbe.repository.backlog.BacklogMainRepository;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class BacklogExcelService {

	private static final int EXCEL_MAX_ROWS =
			1_048_576;


	// =========================================================
	// SHEET LAYOUT
	// =========================================================

	private static final int DETAIL_HEADER_ROW =
			5;

	private static final int DETAIL_DATA_START_ROW =
			6;


	private static final int MAX_DETAIL_ROWS =
			EXCEL_MAX_ROWS - DETAIL_DATA_START_ROW;


	private final BacklogMainRepository repository;


	// =========================================================
	// COLUMN DEFINITION
	// =========================================================

	private enum ColumnType {
		TEXT,
		NUMBER,
		DATETIME
	}


	private record ExcelColumn(
			String field,
			String title,
			ColumnType type,
			int width
	) {
	}


	private static final List<ExcelColumn> COLUMN_CATALOG =
			List.of(

					new ExcelColumn(
							"VBELN",
							"Sales Order",
							ColumnType.TEXT,
							16
					),

					new ExcelColumn(
							"ZGLOBAL_CODE",
							"Global Code",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"PIER_AUFNR",
							"Parent PO",
							ColumnType.TEXT,
							16
					),

					new ExcelColumn(
							"AUFNR",
							"Production Order",
							ColumnType.TEXT,
							16
					),

					new ExcelColumn(
							"IssueD",
							"Issue Date",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ProductionD",
							"Production Date",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"PromiseD",
							"Promise Date",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ExportD",
							"Export Date",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ORG_Date",
							"ORG Date",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"MSM_Ship",
							"MSM Ship",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"PNAME",
							"Product Name",
							ColumnType.TEXT,
							28
					),

					new ExcelColumn(
							"RRONYU1",
							"Customer",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"ShipBy",
							"Ship By",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"GAMNG",
							"Order Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"NETPR",
							"Net Price",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"PHCD",
							"PHCD",
							ColumnType.TEXT,
							12
					),

					new ExcelColumn(
							"KWMENG",
							"KWMENG",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"RODENK",
							"RODENK",
							ColumnType.TEXT,
							14
					),

					new ExcelColumn(
							"LOEKZ",
							"LOEKZ",
							ColumnType.TEXT,
							12
					),

					new ExcelColumn(
							"MTO_ID",
							"MTO ID",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"PRT_ADDCMT1",
							"Comment 1",
							ColumnType.TEXT,
							25
					),

					new ExcelColumn(
							"PRT_ADDCMT2",
							"Comment 2",
							ColumnType.TEXT,
							25
					),

					new ExcelColumn(
							"PRT_STS",
							"PRT Status",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"Div",
							"Division",
							ColumnType.TEXT,
							12
					),

					new ExcelColumn(
							"FERTH",
							"FERTH",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"PO_SRG_Convert",
							"PO SRG Convert",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"ToDrill",
							"To Drill",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ToHeat",
							"To Heat",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ToPK",
							"To Packing",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"Status",
							"Status",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"CurrentProcess",
							"Current Process",
							ColumnType.TEXT,
							24
					),

					new ExcelColumn(
							"HeatCharge",
							"Heat Charge",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"ProcessQty",
							"Process Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"Z300Qty",
							"Z300 Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"PkQty",
							"Packing Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"FinalQty",
							"Final Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"TimeSQuenching",
							"Heat Start",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"TimeFHeat",
							"Heat Finish",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"C_PRODH",
							"C_PRODH",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"C_KEYCONTROL1",
							"Key Control 1",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"C_KEYCONTROL3",
							"Key Control 3",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"Classify",
							"Classify",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"ProcessGrp2",
							"Process Group",
							ColumnType.TEXT,
							16
					),

					new ExcelColumn(
							"ProductGrp",
							"Product Group",
							ColumnType.TEXT,
							16
					),

					new ExcelColumn(
							"CountODBF",
							"Count ODBF",
							ColumnType.TEXT,
							14
					),

					new ExcelColumn(
							"Status2",
							"Status 2",
							ColumnType.TEXT,
							15
					),

					new ExcelColumn(
							"Pickup_Time",
							"Pickup Time",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"PK_Received",
							"PK Received",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"WaitingDays",
							"Waiting Days",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"Heat_Note",
							"Heat Note",
							ColumnType.TEXT,
							25
					),

					new ExcelColumn(
							"Updater",
							"Updater",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"UpdatedAt",
							"Updated At",
							ColumnType.DATETIME,
							18
					)
			);


	private static final Map<String, ExcelColumn> COLUMNS_BY_FIELD =
			buildColumnsByField();


	private static Map<String, ExcelColumn> buildColumnsByField() {

		Map<String, ExcelColumn> byField =
				new LinkedHashMap<>();


		for (
				ExcelColumn column :
				COLUMN_CATALOG
		) {

			ExcelColumn previous =
					byField.put(
							column.field(),
							column
					);


			if (previous != null) {

				throw new IllegalStateException(
						"Duplicate export column definition: "
								+ column.field()
				);
			}
		}


		return Map.copyOf(
				byField
		);
	}


	// =========================================================
	// COLUMN RESOLUTION
	//
	// Backend quyết định column nào hợp lệ.
	// Giữ nguyên thứ tự request.
	// =========================================================

	private List<ExcelColumn> resolveExportColumns(
			List<String> requested
	) {

		if (
				requested == null
						|| requested.isEmpty()
		) {

			throw new IllegalArgumentException(
					"At least one export column is required"
			);
		}


		Set<String> seen =
				new LinkedHashSet<>();


		List<ExcelColumn> resolved =
				new ArrayList<>(
						requested.size()
				);


		for (
				String field :
				requested
		) {

			ExcelColumn column =
					field == null
							? null
							: COLUMNS_BY_FIELD.get(
							field.trim()
					);


			if (column == null) {

				throw new IllegalArgumentException(
						"Unsupported export column: "
								+ field
				);
			}


			if (
					!seen.add(
							column.field()
					)
			) {

				throw new IllegalArgumentException(
						"Duplicate export column: "
								+ column.field()
				);
			}


			resolved.add(
					column
			);
		}


		return List.copyOf(
				resolved
		);
	}

// =========================================================
// EXPORT REQUEST VALIDATION
// =========================================================

public void validateExportRequest(
        BacklogExcelExportRequest request
) {

    if (request == null) {

        throw new IllegalArgumentException(
                "Export request is required"
        );
    }


    resolveExportColumns(
            request.columns()
    );
}
	// =========================================================
	// EXPORT
	// =========================================================

	@Transactional(readOnly = true)
	public void export(
			OutputStream outputStream,
			BacklogExcelExportRequest request
	) throws IOException {

		if (request == null) {

			throw new IllegalArgumentException(
					"Export request is required"
			);
		}


		String safeSearch =
				request.search() == null
						? ""
						: request.search()
						.trim();


		BacklogFilterRequest safeFilter =
				request.filter() == null
						? new BacklogFilterRequest(
						List.of(),
						"and"
				)
						: request.filter();


		String sort =
				request.sort();


		List<ExcelColumn> exportColumns =
				resolveExportColumns(
						request.columns()
				);


		// Keep only a limited number of rows in memory.
		try (
				SXSSFWorkbook workbook =
						new SXSSFWorkbook(200)
		) {

			workbook.setCompressTempFiles(true);


			ExcelStyles styles =
					createStyles(
							workbook
					);


			Sheet sheet =
					workbook.createSheet(
							"Backlog"
					);


			prepareDetailSheet(
					sheet,
					exportColumns
			);


			writeReportInfo(
					sheet,
					safeFilter,
					safeSearch,
					styles
			);


			createHeader(
					sheet,
					exportColumns,
					styles
			);


			AtomicInteger rowIndex =
					new AtomicInteger(
							DETAIL_DATA_START_ROW
					);


			repository.streamFilteredForExport(
					safeFilter,
					safeSearch,
					sort,

					rs -> {

						int excelRow =
								rowIndex.getAndIncrement();


						if (
								excelRow
										>= EXCEL_MAX_ROWS
						) {

							throw new IllegalStateException(
									"Excel export exceeded "
											+ MAX_DETAIL_ROWS
											+ " data rows"
							);
						}


						writeDataRow(
								sheet,
								excelRow,
								rs,
								exportColumns,
								styles
						);
					}
			);


			int lastDataRow =
					rowIndex.get() - 1;


			sheet.setAutoFilter(
					new CellRangeAddress(
							DETAIL_HEADER_ROW,
							Math.max(
									lastDataRow,
									DETAIL_HEADER_ROW
							),
							0,
							exportColumns.size() - 1
					)
			);


			if (
					lastDataRow
							>= DETAIL_DATA_START_ROW
			) {

				addStatusConditionalFormatting(
						sheet,
						exportColumns,
						lastDataRow
				);
			}


			workbook.write(
					outputStream
			);


			outputStream.flush();
		}
	}


	// =========================================================
	// REPORT INFO BLOCK
	// =========================================================

	private static final DateTimeFormatter EXPORTED_AT_FORMAT =
			DateTimeFormatter.ofPattern(
					"dd/MM/yyyy HH:mm:ss"
			);


	private void writeReportInfo(
			Sheet sheet,
			BacklogFilterRequest filter,
			String search,
			ExcelStyles styles
	) {

		Row titleRow =
				sheet.createRow(0);


		Cell title =
				titleRow.createCell(0);


		title.setCellValue(
				"BACKLOG EXPORT REPORT"
		);


		title.setCellStyle(
				styles.title()
		);


		writeInfoRow(
				sheet,
				1,
				"Exported at",
				LocalDateTime.now()
						.format(
								EXPORTED_AT_FORMAT
						),
				styles
		);


		writeInfoRow(
				sheet,
				2,
				"Filter",
				describeFilter(
						filter,
						search
				),
				styles
		);
	}


	// =========================================================
	// FILTER DESCRIPTION
	//
	// field=value, phân tách bằng dấu phẩy.
	// Global search được nối vào cuối.
	// =========================================================

	private String describeFilter(
			BacklogFilterRequest filter,
			String search
	) {

		List<String> parts =
				new ArrayList<>();


		if (
				filter != null
						&& filter.filters() != null
		) {

			for (
					BacklogFilterItem item :
					filter.filters()
			) {

				String described =
						describeFilterItem(
								item
						);


				if (described != null) {

					parts.add(
							described
					);
				}
			}
		}


		if (
				search != null
						&& !search.isBlank()
		) {

			parts.add(
					"Search="
							+ search.trim()
			);
		}


		if (parts.isEmpty()) {
			return "(none)";
		}


		return String.join(
				", ",
				parts
		);
	}


	private String describeFilterItem(
			BacklogFilterItem item
	) {

		if (
				item == null
						|| item.field() == null
						|| item.field().isBlank()
		) {
			return null;
		}


		// Multi-value: field=a|b|c
		if (
				item.values() != null
						&& !item.values().isEmpty()
		) {

			List<String> values =
					new ArrayList<>();


			for (
					String candidate :
					item.values()
			) {

				if (
						candidate != null
								&& !candidate.isBlank()
				) {

					values.add(
							candidate.trim()
					);
				}
			}


			if (!values.isEmpty()) {

				return item.field()
						+ "="
						+ String.join(
						"|",
						values
				);
			}
		}


		if (
				item.value() != null
						&& !item.value().isBlank()
		) {

			return item.field()
					+ "="
					+ item.value().trim();
		}


		return null;
	}


	// =========================================================
	// DETAIL SHEET
	// =========================================================

	private void prepareDetailSheet(
			Sheet sheet,
			List<ExcelColumn> exportColumns
	) {

		sheet.createFreezePane(
				Math.min(
						4,
						exportColumns.size()
				),
				DETAIL_DATA_START_ROW
		);


		for (
				int i = 0;
				i < exportColumns.size();
				i++
		) {

			int width =
					exportColumns
							.get(i)
							.width();


			sheet.setColumnWidth(
					i,
					Math.min(
							width * 256,
							255 * 256
					)
			);
		}


		sheet.setDefaultRowHeightInPoints(
				20
		);
	}


	private void createHeader(
			Sheet sheet,
			List<ExcelColumn> exportColumns,
			ExcelStyles styles
	) {

		Row row =
				sheet.createRow(
						DETAIL_HEADER_ROW
				);


		row.setHeightInPoints(
				28
		);


		for (
				int i = 0;
				i < exportColumns.size();
				i++
		) {

			ExcelColumn column =
					exportColumns.get(i);


			Cell cell =
					row.createCell(i);


			cell.setCellValue(
					column.title()
			);


			cell.setCellStyle(
					styles.header()
			);
		}
	}


	private void writeDataRow(
			Sheet sheet,
			int rowIndex,
			ResultSet rs,
			List<ExcelColumn> exportColumns,
			ExcelStyles styles
	) throws SQLException {

		Row row =
				sheet.createRow(
						rowIndex
				);


		boolean even =
				rowIndex % 2 == 0;


		for (
				int columnIndex = 0;
				columnIndex < exportColumns.size();
				columnIndex++
		) {

			ExcelColumn definition =
					exportColumns.get(
							columnIndex
					);


			Cell cell =
					row.createCell(
							columnIndex
					);


			writeCell(
					cell,
					rs,
					definition,
					even,
					styles
			);
		}
	}


	private void writeCell(
			Cell cell,
			ResultSet rs,
			ExcelColumn column,
			boolean even,
			ExcelStyles styles
	) throws SQLException {

		switch (
				column.type()
		) {

			case DATETIME -> {

				Timestamp value =
						rs.getTimestamp(
								column.field()
						);


				if (value != null) {

					cell.setCellValue(
							value.toLocalDateTime()
					);
				}


				cell.setCellStyle(
						even
								? styles.dateEven()
								: styles.dateOdd()
				);
			}


			case NUMBER -> {

				BigDecimal value =
						rs.getBigDecimal(
								column.field()
						);


				if (value != null) {

					cell.setCellValue(
							value.doubleValue()
					);
				}


				cell.setCellStyle(
						even
								? styles.numberEven()
								: styles.numberOdd()
				);
			}


			case TEXT -> {

				String value =
						rs.getString(
								column.field()
						);


				if (value != null) {

					cell.setCellValue(
							sanitizeExcelText(
									value
							)
					);
				}


				cell.setCellStyle(
						even
								? styles.textEven()
								: styles.textOdd()
				);
			}
		}
	}


	// =========================================================
	// FORMULA INJECTION PROTECTION
	// =========================================================

	private String sanitizeExcelText(
			String value
	) {

		if (
				value == null
						|| value.isEmpty()
		) {
			return value;
		}


		char first =
				value.charAt(0);


		if (
				first == '='
						|| first == '+'
						|| first == '-'
						|| first == '@'
		) {

			return "'"
					+ value;
		}


		return value;
	}


	// =========================================================
	// STATUS CONDITIONAL FORMATTING
	// =========================================================

	private void addStatusConditionalFormatting(
			Sheet sheet,
			List<ExcelColumn> exportColumns,
			int lastRow
	) {

		int statusIndex =
				-1;


		for (
				int i = 0;
				i < exportColumns.size();
				i++
		) {

			if (
					"Status".equals(
							exportColumns
									.get(i)
									.field()
					)
			) {

				statusIndex = i;
				break;
			}
		}


		if (statusIndex < 0) {
			return;
		}


		SheetConditionalFormatting scf =
				sheet
						.getSheetConditionalFormatting();


		String columnLetter =
				org.apache.poi.ss.util.CellReference
						.convertNumToColString(
								statusIndex
						);


		CellRangeAddress[] regions = {
				new CellRangeAddress(
						DETAIL_DATA_START_ROW,
						lastRow,
						statusIndex,
						statusIndex
				)
		};


		addStatusRule(
				scf,
				regions,
				columnLetter,
				"NYI",
				IndexedColors.LIGHT_YELLOW
		);


		addStatusRule(
				scf,
				regions,
				columnLetter,
				"NY Process",
				IndexedColors.LIGHT_ORANGE
		);


		addStatusRule(
				scf,
				regions,
				columnLetter,
				"WIP",
				IndexedColors.LIGHT_BLUE
		);


		addStatusRule(
				scf,
				regions,
				columnLetter,
				"WIP_FG",
				IndexedColors.LIGHT_GREEN
		);


		addStatusRule(
				scf,
				regions,
				columnLetter,
				"Finished",
				IndexedColors.LIGHT_GREEN
		);
	}


	private void addStatusRule(
			SheetConditionalFormatting scf,
			CellRangeAddress[] regions,
			String columnLetter,
			String status,
			IndexedColors color
	) {

		ConditionalFormattingRule rule =
				scf.createConditionalFormattingRule(
						"$"
								+ columnLetter
								+ (DETAIL_DATA_START_ROW + 1)
								+ "=\""
								+ status
								+ "\""
				);


		PatternFormatting formatting =
				rule.createPatternFormatting();


		formatting.setFillPattern(
				PatternFormatting.SOLID_FOREGROUND
		);


		formatting.setFillForegroundColor(
				color.getIndex()
		);


		scf.addConditionalFormatting(
				regions,
				rule
		);
	}


	private int writeInfoRow(
			Sheet sheet,
			int rowIndex,
			String label,
			String value,
			ExcelStyles styles
	) {

		Row row =
				sheet.createRow(
						rowIndex
				);


		Cell labelCell =
				row.createCell(0);


		labelCell.setCellValue(
				label
		);


		labelCell.setCellStyle(
				styles.infoLabel()
		);


		Cell valueCell =
				row.createCell(1);


		valueCell.setCellValue(
				sanitizeExcelText(
						value
				)
		);


		return rowIndex + 1;
	}


	// =========================================================
	// STYLES
	// =========================================================

	private ExcelStyles createStyles(
			Workbook workbook
	) {

		DataFormat dataFormat =
				workbook.createDataFormat();


		// HEADER
		CellStyle header =
				workbook.createCellStyle();


		header.setFillForegroundColor(
				IndexedColors.DARK_BLUE
						.getIndex()
		);


		header.setFillPattern(
				FillPatternType.SOLID_FOREGROUND
		);


		header.setAlignment(
				HorizontalAlignment.CENTER
		);


		header.setVerticalAlignment(
				VerticalAlignment.CENTER
		);


		header.setWrapText(true);


		Font headerFont =
				workbook.createFont();


		headerFont.setBold(true);


		headerFont.setColor(
				IndexedColors.WHITE
						.getIndex()
		);


		header.setFont(
				headerFont
		);


		setBorder(header);


		// TEXT
		CellStyle textOdd =
				workbook.createCellStyle();


		textOdd.setVerticalAlignment(
				VerticalAlignment.CENTER
		);


		setBorder(textOdd);


		CellStyle textEven =
				workbook.createCellStyle();


		textEven.cloneStyleFrom(
				textOdd
		);


		textEven.setFillForegroundColor(
				IndexedColors.GREY_25_PERCENT
						.getIndex()
		);


		textEven.setFillPattern(
				FillPatternType.SOLID_FOREGROUND
		);


		// NUMBER
		CellStyle numberOdd =
				workbook.createCellStyle();


		numberOdd.cloneStyleFrom(
				textOdd
		);


		numberOdd.setDataFormat(
				dataFormat.getFormat(
						"#,##0.####"
				)
		);


		CellStyle numberEven =
				workbook.createCellStyle();


		numberEven.cloneStyleFrom(
				textEven
		);


		numberEven.setDataFormat(
				dataFormat.getFormat(
						"#,##0.####"
				)
		);


		// DATE
		CellStyle dateOdd =
				workbook.createCellStyle();


		dateOdd.cloneStyleFrom(
				textOdd
		);


		dateOdd.setDataFormat(
				dataFormat.getFormat(
						"yyyy-mm-dd hh:mm"
				)
		);


		CellStyle dateEven =
				workbook.createCellStyle();


		dateEven.cloneStyleFrom(
				textEven
		);


		dateEven.setDataFormat(
				dataFormat.getFormat(
						"yyyy-mm-dd hh:mm"
				)
		);


		// TITLE
		CellStyle title =
				workbook.createCellStyle();


		title.setAlignment(
				HorizontalAlignment.LEFT
		);


		title.setVerticalAlignment(
				VerticalAlignment.CENTER
		);


		Font titleFont =
				workbook.createFont();


		titleFont.setBold(true);
		titleFont.setFontHeightInPoints(
				(short) 18
		);


		title.setFont(
				titleFont
		);


		// INFO LABEL
		CellStyle infoLabel =
				workbook.createCellStyle();


		Font infoFont =
				workbook.createFont();


		infoFont.setBold(true);


		infoLabel.setFont(
				infoFont
		);


		return new ExcelStyles(
				header,
				textOdd,
				textEven,
				numberOdd,
				numberEven,
				dateOdd,
				dateEven,
				title,
				infoLabel
		);
	}


	private void setBorder(
			CellStyle style
	) {

		style.setBorderBottom(
				BorderStyle.THIN
		);

		style.setBorderTop(
				BorderStyle.THIN
		);

		style.setBorderLeft(
				BorderStyle.THIN
		);

		style.setBorderRight(
				BorderStyle.THIN
		);


		short color =
				IndexedColors.GREY_25_PERCENT
						.getIndex();


		style.setBottomBorderColor(
				color
		);

		style.setTopBorderColor(
				color
		);

		style.setLeftBorderColor(
				color
		);

		style.setRightBorderColor(
				color
		);
	}


	private record ExcelStyles(
			CellStyle header,
			CellStyle textOdd,
			CellStyle textEven,
			CellStyle numberOdd,
			CellStyle numberEven,
			CellStyle dateOdd,
			CellStyle dateEven,
			CellStyle title,
			CellStyle infoLabel
	) {
	}
}