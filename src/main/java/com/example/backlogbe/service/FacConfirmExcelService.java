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
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.backlogbe.dto.facconfirm.FacConfirmExcelExportRequest;
import com.example.backlogbe.dto.facconfirm.FacConfirmFilterItem;
import com.example.backlogbe.repository.facconfirm.FacConfirmRepository;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class FacConfirmExcelService {

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


	private static final DateTimeFormatter EXPORTED_AT_FORMAT =
			DateTimeFormatter.ofPattern(
					"dd/MM/yyyy HH:mm:ss"
			);


	private final FacConfirmRepository repository;


	// =========================================================
	// COLUMN DEFINITION
	// =========================================================

	private enum ColumnType {
		TEXT,
		NUMBER,
		DATETIME,
		BOOLEAN
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
							"FERTH",
							"FERTH",
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
							"AUFNR",
							"Production Order",
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
							"PNAME",
							"Product Name",
							ColumnType.TEXT,
							28
					),

					new ExcelColumn(
							"IssueD",
							"Issue Date",
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
							"CusId",
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
							"MTO_ID",
							"MTO ID",
							ColumnType.TEXT,
							18
					),

					new ExcelColumn(
							"PRT_ADDCMT2",
							"Comment 2",
							ColumnType.TEXT,
							25
					),

					new ExcelColumn(
							"CurrentProcess",
							"Current Process",
							ColumnType.TEXT,
							24
					),

					new ExcelColumn(
							"FinalQty",
							"Final Qty",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"WaitingDays",
							"Waiting Days",
							ColumnType.NUMBER,
							14
					),

					new ExcelColumn(
							"HasHeatProcess",
							"Has Heat Process",
							ColumnType.BOOLEAN,
							16
					),

					new ExcelColumn(
							"IsDC53",
							"DC53",
							ColumnType.BOOLEAN,
							12
					),

					new ExcelColumn(
							"IsTD",
							"TD",
							ColumnType.BOOLEAN,
							12
					),

					new ExcelColumn(
							"IsMolypden",
							"Molypden",
							ColumnType.BOOLEAN,
							12
					),

					new ExcelColumn(
							"Note",
							"Note",
							ColumnType.TEXT,
							25
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
							"Heat_Start",
							"Heat Start",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"Heat_Finish",
							"Heat Finish",
							ColumnType.DATETIME,
							18
					),

					new ExcelColumn(
							"ToPK",
							"To Packing",
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


			String dataGridField =
					toDataGridField(
							column.field()
					);


			previous =
					byField.put(
							dataGridField,
							column
					);


			if (
					previous != null
							&& previous != column
			) {

				throw new IllegalStateException(
						"Duplicate DataGrid export field definition: "
								+ dataGridField
				);
			}
		}


		return Map.copyOf(
				byField
		);
	}


	private static String toDataGridField(
			String resultSetField
	) {

		return switch (resultSetField) {
			case "FERTH" -> "ferth";
			case "ProductGrp" -> "productGrp";
			case "AUFNR" -> "aufnr";
			case "ZGLOBAL_CODE" -> "zglobalCode";
			case "PNAME" -> "pname";
			case "IssueD" -> "issueD";
			case "ExportD" -> "exportD";
			case "CusId" -> "cusId";
			case "ShipBy" -> "shipBy";
			case "MTO_ID" -> "mtoId";
			case "PRT_ADDCMT2" -> "prtAddcmt2";
			case "CurrentProcess" -> "currentProcess";
			case "FinalQty" -> "finalQty";
			case "WaitingDays" -> "waitingDays";
			case "HasHeatProcess" -> "hasHeatProcess";
			case "IsDC53" -> "isDC53";
			case "IsTD" -> "isTD";
			case "IsMolypden" -> "isMolypden";
			case "Note" -> "note";
			case "ToDrill" -> "toDrill";
			case "ToHeat" -> "toHeat";
			case "Heat_Start" -> "heatStart";
			case "Heat_Finish" -> "heatFinish";
			case "ToPK" -> "toPk";
			default -> throw new IllegalStateException(
					"Missing DataGrid field mapping for export column: "
							+ resultSetField
			);
		};
	}


	// =========================================================
	// COLUMN RESOLUTION
	//
	// Backend quyet dinh column nao hop le.
	// Giu nguyen thu tu request.
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
	//
	// Goi truoc khi stream bat dau de tra ve 400.
	// =========================================================

	public void validateExportRequest(
			FacConfirmExcelExportRequest request
	) {

		if (request == null) {

			throw new IllegalArgumentException(
					"Export request is required"
			);
		}


		// =====================================================
		// BASE FILTER
		//
		// Cung semantics voi FacConfirmService.
		// =====================================================

		if (request.expD() == null) {

			throw new IllegalArgumentException(
					"expD is required"
			);
		}


		if (
				request.div() == null
						|| request.div().isBlank()
		) {

			throw new IllegalArgumentException(
					"div is required"
			);
		}


		if (
				request.procGrp() == null
						|| request.procGrp().isBlank()
		) {

			throw new IllegalArgumentException(
					"procGrp is required"
			);
		}


		// =====================================================
		// EXPORT COLUMNS
		// =====================================================

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
			FacConfirmExcelExportRequest request
	) throws IOException {

		if (request == null) {

			throw new IllegalArgumentException(
					"Export request is required"
			);
		}


		String safeSearch =
				request.search() == null
								|| request.search().isBlank()
						? null
						: request.search().trim();


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
							"Fac Confirm"
					);


			prepareDetailSheet(
					sheet,
					exportColumns
			);


			writeReportInfo(
					sheet,
					request,
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


			repository.streamSearchForExport(
					request.div(),
					request.expD(),
					request.procGrp(),
					request.classify(),
					request.heatType(),
					safeSearch,
					request.filters(),
					request.logicOperator(),

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


			workbook.write(
					outputStream
			);


			outputStream.flush();
		}
	}


	// =========================================================
	// REPORT INFO BLOCK
	// =========================================================

	private void writeReportInfo(
			Sheet sheet,
			FacConfirmExcelExportRequest request,
			String search,
			ExcelStyles styles
	) {

		Row titleRow =
				sheet.createRow(0);


		Cell title =
				titleRow.createCell(0);


		title.setCellValue(
				"FAC CONFIRM EXPORT REPORT"
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
						request,
						search
				),
				styles
		);
	}


	// =========================================================
	// FILTER DESCRIPTION
	//
	// field=value, phan tach bang dau phay.
	// Global search duoc noi vao cuoi.
	// =========================================================

	private String describeFilter(
			FacConfirmExcelExportRequest request,
			String search
	) {

		List<String> parts =
				new ArrayList<>();


		addBasePart(
				parts,
				"Div",
				request.div()
		);


		if (request.expD() != null) {

			parts.add(
					"ExpD="
							+ request.expD()
			);
		}


		addBasePart(
				parts,
				"ProcGrp",
				request.procGrp()
		);


		addBasePart(
				parts,
				"Classify",
				request.classify()
		);


		addBasePart(
				parts,
				"HeatType",
				request.heatType()
		);


		if (request.filters() != null) {

			for (
					FacConfirmFilterItem item :
					request.filters()
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


	private void addBasePart(
			List<String> parts,
			String label,
			String value
	) {

		if (
				value != null
						&& !value.isBlank()
		) {

			parts.add(
					label
							+ "="
							+ value.trim()
			);
		}
	}


	private String describeFilterItem(
			FacConfirmFilterItem item
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


			case BOOLEAN -> {

				boolean value =
						rs.getBoolean(
								column.field()
						);


				// NULL -> blank
				if (
						!rs.wasNull()
								&& value
				) {

					cell.setCellValue(
							"Yes"
					);
				}


				cell.setCellStyle(
						even
								? styles.textEven()
								: styles.textOdd()
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
