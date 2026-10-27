package com.example.backlogbe.service;

import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseDto;
import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseItemDto;
import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseMachineDto;
import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseSummaryDto;
import com.example.backlogbe.dto.machinemonitor.MachineMonitorHseUnassignedDto;
import com.example.backlogbe.model.MachineMonitorHseRow;
import com.example.backlogbe.repository.machinelocator.MachineLocatorRepository;
import com.example.backlogbe.repository.machinemonitor.MachineMonitorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// =========================================================
// MACHINE MONITOR
//
// Chỉ đọc, không cần đăng nhập.
// Tab "An toàn": lỗi patrol (dbo.F2_Patrol_Report, type = 'Patrol')
// gom theo máy trên 1 floor của Machine Locator, cộng các lỗi
// không gắn máy thuộc division của floor đó.
// =========================================================
@Service
@RequiredArgsConstructor
public class MachineMonitorService {

	// =========================================================
	// CONFIG
	// =========================================================

	private static final List<String> RISK_LEVELS =
			List.of("I", "II", "III", "IV", "V");

	// So sánh không phân biệt hoa/thường (đã trim)
	private static final Set<String> EMPTY_MACHINE_TEXTS =
			Set.of("", "null", "<null>", "none");

	private static final String NO_MACHINE_LABEL = "(Không ghi máy)";

	// Thứ tự khai báo = mức độ tệ giảm dần (dùng cho sắp xếp + trạng thái máy)
	private enum HseStatus {
		OVERDUE, NG, OPEN, WAIT_HSE, SAFE
	}


	private final MachineMonitorRepository repository;

	private final MachineLocatorRepository machineLocatorRepository;

	// Không final: @RequiredArgsConstructor không chép @Value sang constructor
	@Value("${machine-monitor.hse.plants:Fac_2}")
	private List<String> plants;

	// Dạng "division=floorId, division=floorId"
	@Value("${machine-monitor.hse.division-floor:}")
	private String divisionFloor;


	// Một lỗi đã tính trạng thái
	private record Item(
			MachineMonitorHseRow row,
			HseStatus status
	) {
	}

	// Số liệu chung của 1 máy / 1 nhóm không gắn máy
	private record Group(
			HseStatus status,
			int openCount,
			int overdueCount,
			int waitCount,
			int safeCount,
			String maxRisk,
			List<MachineMonitorHseItemDto> items
	) {
	}


	// =========================================================
	// HSE
	// =========================================================

	public MachineMonitorHseDto getHse(
			String floorId,
			String safeFrom,
			String safeTo
	) {

		String safeFloorId = floorId != null ? floorId.trim() : "";

		if (safeFloorId.isEmpty()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"floorId is required."
			);
		}

		LocalDate safeFromDate = parseDate(safeFrom, "safeFrom");
		LocalDate safeToDate = parseDate(safeTo, "safeTo");

		if (safeFromDate != null && safeToDate != null && safeToDate.isBefore(safeFromDate)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"safeTo must not be before safeFrom."
			);
		}

		if (machineLocatorRepository.findFloor(safeFloorId).isEmpty()) {
			throw new ResponseStatusException(
					HttpStatus.NOT_FOUND,
					"Floor " + safeFloorId + " not found."
			);
		}

		LocalDate today = LocalDate.now();


		// ---------- MACHINES ----------

		// Repository đã ORDER BY m.code -> LinkedHashMap giữ thứ tự máy
		Map<String, List<Item>> itemsByMachine = new LinkedHashMap<>();

		for (MachineMonitorHseRow row : repository.findHseItems(safeFloorId, plants)) {
			Item item = toItem(row, today, safeFromDate, safeToDate);

			if (item != null) {
				itemsByMachine
						.computeIfAbsent(row.machineCode(), code -> new ArrayList<>())
						.add(item);
			}
		}

		Map<HseStatus, Integer> machineCountByStatus = new EnumMap<>(HseStatus.class);
		Map<String, MachineMonitorHseMachineDto> machines = new LinkedHashMap<>();

		itemsByMachine.forEach((code, items) -> {
			Group group = toGroup(items);

			machines.put(
					code,
					new MachineMonitorHseMachineDto(
							group.status().name(),
							group.openCount(),
							group.overdueCount(),
							group.waitCount(),
							group.safeCount(),
							group.maxRisk(),
							group.items()
					)
			);
			machineCountByStatus.merge(group.status(), 1, Integer::sum);
		});


		// ---------- UNASSIGNED ----------

		List<String> divisions = divisionsOf(safeFloorId);

		Map<String, List<Item>> itemsByLabel = new LinkedHashMap<>();

		if (!divisions.isEmpty()) {
			for (MachineMonitorHseRow row : repository.findUnassignedHseItems(divisions, plants)) {
				Item item = toItem(row, today, safeFromDate, safeToDate);

				if (item != null) {
					itemsByLabel
							.computeIfAbsent(unassignedLabel(row.machineText()), label -> new ArrayList<>())
							.add(item);
				}
			}
		}

		int unassignedOpen = 0;
		List<MachineMonitorHseUnassignedDto> unassigned = new ArrayList<>();

		for (Map.Entry<String, List<Item>> entry : itemsByLabel.entrySet()) {
			Group group = toGroup(entry.getValue());

			unassignedOpen += group.items().size() - group.safeCount();
			unassigned.add(
					new MachineMonitorHseUnassignedDto(
							entry.getKey(),
							group.status().name(),
							group.openCount(),
							group.overdueCount(),
							group.waitCount(),
							group.safeCount(),
							group.maxRisk(),
							group.items()
					)
			);
		}

		// Status tệ nhất trước, rồi nhiều lỗi trước, rồi label
		unassigned.sort(
				Comparator.comparing((MachineMonitorHseUnassignedDto group) -> HseStatus.valueOf(group.status()))
						.thenComparing(group -> group.items().size(), Comparator.reverseOrder())
						.thenComparing(MachineMonitorHseUnassignedDto::label)
		);


		MachineMonitorHseSummaryDto summary =
				new MachineMonitorHseSummaryDto(
						machines.size(),
						machineCountByStatus.getOrDefault(HseStatus.OPEN, 0),
						machineCountByStatus.getOrDefault(HseStatus.OVERDUE, 0),
						machineCountByStatus.getOrDefault(HseStatus.NG, 0),
						machineCountByStatus.getOrDefault(HseStatus.WAIT_HSE, 0),
						machineCountByStatus.getOrDefault(HseStatus.SAFE, 0),
						unassignedOpen
				);

		return new MachineMonitorHseDto(
				safeFloorId,
				LocalDateTime.now(),
				summary,
				machines,
				unassigned
		);
	}


	// =========================================================
	// PARAM / CONFIG
	// =========================================================

	// null / rỗng -> không lọc
	private static LocalDate parseDate(
			String value,
			String name
	) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					name + " must be yyyy-MM-dd."
			);
		}
	}

	// Các division map ra floorId (cấu hình sai định dạng thì bỏ qua cặp đó)
	private List<String> divisionsOf(String floorId) {

		if (divisionFloor == null) {
			return List.of();
		}

		List<String> divisions = new ArrayList<>();

		for (String pair : divisionFloor.split(",")) {
			String[] parts = pair.split("=", 2);

			if (parts.length == 2
					&& !parts[0].isBlank()
					&& parts[1].trim().equals(floorId)) {
				divisions.add(parts[0].trim());
			}
		}

		return divisions;
	}

	private static String unassignedLabel(String machineText) {

		String text = machineText != null ? machineText.trim() : "";

		return EMPTY_MACHINE_TEXTS.contains(text.toLowerCase(Locale.ROOT))
				? NO_MACHINE_LABEL
				: text;
	}


	// =========================================================
	// STATUS
	// =========================================================

	/*
	 * null nếu lỗi SAFE nằm ngoài [safeFrom, safeTo] theo ngày của hseDate
	 * (thiếu đầu nào bỏ điều kiện đầu đó; có lọc mà không có hseDate cũng bị bỏ).
	 */
	private static Item toItem(
			MachineMonitorHseRow row,
			LocalDate today,
			LocalDate safeFrom,
			LocalDate safeTo
	) {

		HseStatus status = resolveStatus(row, today);

		if (status == HseStatus.SAFE && (safeFrom != null || safeTo != null)) {
			LocalDate hseDay = row.hseDate() != null ? row.hseDate().toLocalDate() : null;

			if (hseDay == null
					|| (safeFrom != null && hseDay.isBefore(safeFrom))
					|| (safeTo != null && hseDay.isAfter(safeTo))) {
				return null;
			}
		}

		return new Item(row, status);
	}

	private static HseStatus resolveStatus(
			MachineMonitorHseRow row,
			LocalDate today
	) {

		HseStatus status;

		if (equalsTrimmed(row.hseJudge(), "OK")) {
			status = HseStatus.SAFE;
		} else if (equalsTrimmed(row.atStatus(), "Redo")) {
			status = HseStatus.NG;
		} else if (equalsTrimmed(row.atStatus(), "Pro_Done")
				|| equalsTrimmed(row.atStatus(), "Closed")) {
			status = HseStatus.WAIT_HSE;
		} else {
			status = HseStatus.OPEN;
		}

		boolean overdue =
				(status == HseStatus.OPEN || status == HseStatus.NG)
						&& row.dueDate() != null
						&& row.dueDate().isBefore(today);

		return overdue ? HseStatus.OVERDUE : status;
	}

	private static boolean equalsTrimmed(
			String value,
			String expected
	) {
		return value != null && value.trim().equalsIgnoreCase(expected);
	}


	// =========================================================
	// MAPPING
	// =========================================================

	// items không rỗng
	private static Group toGroup(List<Item> items) {

		// Chưa SAFE trước (theo thứ tự enum), trong nhóm createdAt giảm dần
		List<Item> sorted =
				items.stream()
						.sorted(
								Comparator.comparing(Item::status)
										.thenComparing(
												item -> item.row().createdAt(),
												Comparator.nullsLast(Comparator.reverseOrder())
										)
						)
						.toList();

		int openCount = 0;
		int overdueCount = 0;
		int waitCount = 0;
		int safeCount = 0;
		int maxRiskRank = -1;

		for (Item item : sorted) {
			switch (item.status()) {
				case OVERDUE -> {
					openCount++;
					overdueCount++;
				}
				case NG, OPEN -> openCount++;
				case WAIT_HSE -> waitCount++;
				case SAFE -> safeCount++;
			}

			if (item.status() != HseStatus.SAFE) {
				maxRiskRank = Math.max(maxRiskRank, riskRank(item.row().riskTotal()));
			}
		}

		return new Group(
				sorted.get(0).status(),
				openCount,
				overdueCount,
				waitCount,
				safeCount,
				maxRiskRank >= 0 ? RISK_LEVELS.get(maxRiskRank) : null,
				sorted.stream()
						.map(MachineMonitorService::toItemDto)
						.toList()
		);
	}

	// -1 nếu không phải I..V
	private static int riskRank(String risk) {
		return risk != null
				? RISK_LEVELS.indexOf(risk.trim().toUpperCase(Locale.ROOT))
				: -1;
	}

	private static MachineMonitorHseItemDto toItemDto(Item item) {

		MachineMonitorHseRow row = item.row();

		return new MachineMonitorHseItemDto(
				row.id(),
				row.stt(),
				item.status().name(),
				row.machineText(),
				row.division(),
				row.area(),
				row.riskTotal(),
				row.riskFreq(),
				row.riskProb(),
				row.riskSev(),
				row.comment(),
				row.countermeasure(),
				row.patrolUser(),
				row.createdAt(),
				row.pic(),
				row.dueDate(),
				row.atStatus(),
				row.atUser(),
				row.atDate(),
				row.atComment(),
				row.hseJudge(),
				row.hseUser(),
				row.hseDate(),
				row.hseComment(),
				splitNames(row.imageNames()),
				splitNames(row.atImageNames()),
				splitNames(row.hseImageNames())
		);
	}

	private static List<String> splitNames(String value) {

		if (value == null) {
			return List.of();
		}

		return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(name -> !name.isEmpty())
				.toList();
	}
}
