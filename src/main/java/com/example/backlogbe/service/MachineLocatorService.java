package com.example.backlogbe.service;

import com.example.backlogbe.dto.machinelocator.MachineLocatorBoxRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorBulkRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorSummaryDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorImportRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorMachineDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorRenameRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorSaveRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorVersionRequest;
import com.example.backlogbe.exception.MachineLocatorConflictException;
import com.example.backlogbe.model.MachineLocatorFloorRow;
import com.example.backlogbe.model.MachineLocatorMachineRow;
import com.example.backlogbe.repository.machinelocator.MachineLocatorRepository;
import com.example.backlogbe.service.auth.ProductionControlAuthService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// =========================================================
// MACHINE LOCATOR
//
// Port từ vite-plugins/machineLocatorDevStore.ts (FE) sang SQL Server.
//
// - Đọc: không cần đăng nhập.
// - Ghi: chỉ ADMIN; mỗi request kiểm tra ifVersion (lệch -> 409),
//   thành công thì version + 1 (1 lần / request) và ghi
//   dbo.F2_MachineLocator_History cho từng máy thay đổi.
//   Mọi lệnh ghi trả về floor đầy đủ (giống GET /floors/{id}).
// =========================================================
@Service
@RequiredArgsConstructor
public class MachineLocatorService {

	// =========================================================
	// CONFIG
	// =========================================================

	private static final Pattern CODE_PATTERN =
			Pattern.compile("^[AL]-\\d{2,5}$");

	private static final Set<String> VALID_FITS =
			Set.of("closed", "tight", "hull", "rect");

	private static final String SRC_MANUAL = "manual";
	private static final String SRC_CAD = "cad";
	private static final String SRC_OLD = "old";

	private static final Set<String> VALID_SRCS =
			Set.of(SRC_MANUAL, SRC_CAD, SRC_OLD);

	private static final String OP_SET = "set";
	private static final String OP_DEL = "del";

	private static final String ACTION_SAVE = "SAVE";
	private static final String ACTION_DELETE = "DELETE";
	private static final String ACTION_RENAME = "RENAME";
	private static final String ACTION_CONFIRM = "CONFIRM";
	private static final String ACTION_REVERT = "REVERT";
	private static final String ACTION_IMPORT = "IMPORT";

	private static final String ROLE_ADMIN = "ADMIN";

	// Số chữ số thập phân khi làm tròn tọa độ
	private static final int BOX_SCALE = 1;
	private static final int POLY_SCALE = 2;

	private static final TypeReference<List<List<Double>>> BOXES_TYPE =
			new TypeReference<>() {
			};

	private static final TypeReference<List<String>> FIT_TYPE =
			new TypeReference<>() {
			};


	private final MachineLocatorRepository repository;

	private final ProductionControlAuthService authService;

	private final ObjectMapper objectMapper;


	// Hình học đã validate + làm tròn
	private record Geometry(
			List<List<Double>> boxes,
			JsonNode polys,
			List<String> fit
	) {
	}


	// =========================================================
	// READ
	// =========================================================

	@Transactional(readOnly = true)
	public List<MachineLocatorFloorSummaryDto> getFloors() {

		return repository.findFloors()
				.stream()
				.map(floor -> new MachineLocatorFloorSummaryDto(
						floor.id(),
						floor.name(),
						floor.drawingTitle(),
						floor.imageUrl(),
						readTree(floor.exportMetaJson())
				))
				.toList();
	}

	@Transactional(readOnly = true)
	public MachineLocatorFloorDto getFloor(String floorId) {

		MachineLocatorFloorRow floor =
				repository.findFloor(floorId)
						.orElseThrow(() -> floorNotFound(floorId));

		return toFloorDto(floor);
	}


	// =========================================================
	// PUT MACHINE
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto saveMachine(
			String floorId,
			String code,
			MachineLocatorSaveRequest request,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		String safeCode = normalizeCode(code);

		Geometry geometry =
				validateBoxes(safeCode, request.boxes());

		int currentVersion =
				checkVersion(floorId, request.ifVersion());

		applySet(
				floorId,
				safeCode,
				geometry,
				Boolean.TRUE.equals(request.keepStatus()),
				user
		);

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// DELETE MACHINE
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto deleteMachine(
			String floorId,
			String code,
			Integer ifVersion,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		String safeCode = normalizeCode(code);

		int currentVersion =
				checkVersion(floorId, ifVersion);

		MachineLocatorMachineRow before =
				findMachineOrThrow(floorId, safeCode);

		repository.deleteMachine(floorId, safeCode);

		writeHistory(
				floorId,
				safeCode,
				ACTION_DELETE,
				machineJson(before),
				null,
				user
		);

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// RENAME
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto renameMachine(
			String floorId,
			String code,
			MachineLocatorRenameRequest request,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		String fromCode = normalizeCode(code);

		String toCode = normalizeCode(request.to());

		if (fromCode.equals(toCode)) {
			throw new IllegalArgumentException(
					"New code must be different from " + fromCode + "."
			);
		}

		int currentVersion =
				checkVersion(floorId, request.ifVersion());

		findMachineOrThrow(floorId, fromCode);

		if (repository.findMachine(floorId, toCode).isPresent()) {
			throw new IllegalArgumentException(
					"Machine " + toCode + " already exists on this floor."
			);
		}

		repository.renameMachine(floorId, fromCode, toCode, user);

		writeHistory(
				floorId,
				fromCode,
				ACTION_RENAME,
				toJson(Map.of("code", fromCode)),
				toJson(Map.of("code", toCode)),
				user
		);

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// CONFIRM: bỏ cờ review
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto confirmMachine(
			String floorId,
			String code,
			MachineLocatorVersionRequest request,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		String safeCode = normalizeCode(code);

		int currentVersion =
				checkVersion(floorId, request.ifVersion());

		MachineLocatorMachineRow before =
				findMachineOrThrow(floorId, safeCode);

		MachineLocatorMachineRow after =
				before.withReview(Boolean.FALSE);

		repository.saveMachine(floorId, after, user);

		writeHistory(
				floorId,
				safeCode,
				ACTION_CONFIRM,
				machineJson(before),
				machineJson(after),
				user
		);

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// REVERT: chép cad_* về dữ liệu hiện tại
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto revertMachine(
			String floorId,
			String code,
			MachineLocatorVersionRequest request,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		String safeCode = normalizeCode(code);

		int currentVersion =
				checkVersion(floorId, request.ifVersion());

		MachineLocatorMachineRow before =
				findMachineOrThrow(floorId, safeCode);

		if (before.cadBoxesJson() == null) {
			throw new IllegalArgumentException(
					"Machine " + safeCode + " has no CAD data to revert to."
			);
		}

		MachineLocatorMachineRow after =
				before.withCurrent(
						before.cadBoxesJson(),
						before.cadPolysJson(),
						before.cadFitJson(),
						before.uncertain(),
						SRC_CAD,
						Boolean.FALSE
				);

		repository.saveMachine(floorId, after, user);

		writeHistory(
				floorId,
				safeCode,
				ACTION_REVERT,
				machineJson(before),
				machineJson(after),
				user
		);

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// BULK: set / del trong 1 transaction
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto bulk(
			String floorId,
			MachineLocatorBulkRequest request,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		if (request.ops() == null || request.ops().isEmpty()) {
			throw new IllegalArgumentException("ops is required.");
		}

		// Validate hết trước khi ghi
		List<String> codes = new ArrayList<>();
		List<Geometry> geometries = new ArrayList<>();

		for (int i = 0; i < request.ops().size(); i++) {

			MachineLocatorBulkRequest.Op op = request.ops().get(i);

			if (op == null) {
				throw new IllegalArgumentException("ops[" + i + "] is required.");
			}

			String safeCode = normalizeCode(op.code());

			codes.add(safeCode);

			if (OP_SET.equals(op.type())) {
				geometries.add(
						validateBoxes(safeCode, op.boxes())
				);
			} else if (OP_DEL.equals(op.type())) {
				geometries.add(null);
			} else {
				throw new IllegalArgumentException(
						"ops[" + i + "].type must be 'set' or 'del'."
				);
			}
		}

		int currentVersion =
				checkVersion(floorId, request.ifVersion());

		boolean keepStatus =
				Boolean.TRUE.equals(request.keepStatus());

		for (int i = 0; i < codes.size(); i++) {

			String safeCode = codes.get(i);
			Geometry geometry = geometries.get(i);

			if (geometry != null) {
				applySet(floorId, safeCode, geometry, keepStatus, user);
				continue;
			}

			// del máy không tồn tại: bỏ qua, không ghi history
			MachineLocatorMachineRow before =
					repository.findMachine(floorId, safeCode)
							.orElse(null);

			if (before != null) {
				repository.deleteMachine(floorId, safeCode);

				writeHistory(
						floorId,
						safeCode,
						ACTION_DELETE,
						machineJson(before),
						null,
						user
				);
			}
		}

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// IMPORT: nạp file LayoutData {crop, page, ppp, m}
	//
	// Metadata floor (name, drawingTitle, imageUrl, exportMeta) đã seed sẵn,
	// chỉ cập nhật crop/page/ppp và upsert máy. Máy không có trong file
	// được giữ nguyên.
	// =========================================================

	@Transactional
	public MachineLocatorFloorDto importLayout(
			String floorId,
			MachineLocatorImportRequest request,
			Integer ifVersion,
			String employeeId
	) {
		String user = requireAdmin(employeeId);

		requireBody(request);

		validateCrop(request.crop());

		validatePage(request.page());

		if (request.ppp() == null || !Double.isFinite(request.ppp())) {
			throw new IllegalArgumentException("ppp is required.");
		}

		if (request.m() == null) {
			throw new IllegalArgumentException("m is required.");
		}

		// Validate hết trước khi ghi
		Map<String, Geometry> geometries = new LinkedHashMap<>();

		for (Map.Entry<String, MachineLocatorMachineDto> entry : request.m().entrySet()) {

			String safeCode = normalizeCode(entry.getKey());

			MachineLocatorMachineDto machine = entry.getValue();

			if (machine == null) {
				throw new IllegalArgumentException(safeCode + ": machine data is required.");
			}

			if (machine.src() != null && !VALID_SRCS.contains(machine.src())) {
				throw new IllegalArgumentException(
						safeCode + ": src must be 'manual', 'cad' or 'old'."
				);
			}

			if (geometries.containsKey(safeCode)) {
				throw new IllegalArgumentException("Duplicate machine code " + safeCode + ".");
			}

			geometries.put(
					safeCode,
					validateGeometry(safeCode, machine.b(), machine.p(), machine.fit())
			);
		}

		// ifVersion tùy chọn: không gửi thì chỉ khóa floor và lấy version hiện tại
		int currentVersion =
				ifVersion != null
						? checkVersion(floorId, ifVersion)
						: lockFloor(floorId);

		repository.updateFloorLayout(
				floorId,
				toJson(request.crop()),
				request.page().get(0),
				request.page().get(1),
				request.ppp()
		);

		Map<String, MachineLocatorMachineRow> existing =
				repository.findMachines(floorId)
						.stream()
						.collect(Collectors.toMap(
								MachineLocatorMachineRow::code,
								Function.identity()
						));

		for (Map.Entry<String, MachineLocatorMachineDto> entry : request.m().entrySet()) {

			String safeCode = normalizeCode(entry.getKey());

			MachineLocatorMachineDto machine = entry.getValue();

			Geometry geometry = geometries.get(safeCode);

			String src =
					machine.src() != null
							? machine.src()
							: SRC_MANUAL;

			String boxesJson = toJson(geometry.boxes());
			String polysJson = toJson(geometry.polys());
			String fitJson = toJson(geometry.fit());

			MachineLocatorMachineRow before = existing.get(safeCode);

			// Máy từ CAD / bản cũ: lưu luôn bản gốc để revert
			boolean isCadSource =
					SRC_CAD.equals(src) || SRC_OLD.equals(src);

			MachineLocatorMachineRow after =
					new MachineLocatorMachineRow(
							safeCode,
							boxesJson,
							polysJson,
							fitJson,
							Boolean.TRUE.equals(machine.u()),
							src,
							Boolean.TRUE.equals(machine.r()),
							isCadSource ? boxesJson : before != null ? before.cadBoxesJson() : null,
							isCadSource ? polysJson : before != null ? before.cadPolysJson() : null,
							isCadSource ? fitJson : before != null ? before.cadFitJson() : null
					);

			repository.saveMachine(floorId, after, user);

			writeHistory(
					floorId,
					safeCode,
					ACTION_IMPORT,
					machineJson(before),
					machineJson(after),
					user
			);
		}

		bumpVersion(floorId, currentVersion, user);

		return getFloor(floorId);
	}


	// =========================================================
	// SET 1 MÁY (dùng chung PUT + bulk)
	//
	// keepStatus = false -> src = 'manual', u = false, bỏ r (review = 0).
	// keepStatus = true  -> giữ u / src / r trong DB (máy mới thì như false).
	// cad_* luôn giữ nguyên.
	// =========================================================

	private void applySet(
			String floorId,
			String code,
			Geometry geometry,
			boolean keepStatus,
			String user
	) {
		MachineLocatorMachineRow before =
				repository.findMachine(floorId, code)
						.orElse(null);

		boolean keep = keepStatus && before != null;

		MachineLocatorMachineRow base =
				before != null
						? before
						: new MachineLocatorMachineRow(
								code, null, null, null,
								null, null, null,
								null, null, null
						);

		MachineLocatorMachineRow after =
				base.withCurrent(
						toJson(geometry.boxes()),
						toJson(geometry.polys()),
						toJson(geometry.fit()),
						keep ? before.uncertain() : Boolean.FALSE,
						keep ? before.src() : SRC_MANUAL,
						keep ? before.review() : Boolean.FALSE
				);

		repository.saveMachine(floorId, after, user);

		writeHistory(
				floorId,
				code,
				ACTION_SAVE,
				machineJson(before),
				machineJson(after),
				user
		);
	}


	// =========================================================
	// AUTH
	// =========================================================

	private String requireAdmin(String employeeId) {

		String user =
				employeeId == null
						? ""
						: employeeId.trim().toUpperCase(Locale.ROOT);

		if (user.isBlank()) {
			throw new ResponseStatusException(
					HttpStatus.UNAUTHORIZED,
					"Login is required."
			);
		}

		List<String> roles =
				authService.findAccountRoles(user)
						.orElseThrow(() ->
								new ResponseStatusException(
										HttpStatus.UNAUTHORIZED,
										"Account does not exist."
								)
						);

		if (!roles.contains(ROLE_ADMIN)) {
			throw new ResponseStatusException(
					HttpStatus.FORBIDDEN,
					"Only ADMIN can edit Machine Locator."
			);
		}

		return user;
	}


	// =========================================================
	// VERSION (optimistic locking)
	// =========================================================

	private int checkVersion(
			String floorId,
			Integer ifVersion
	) {
		if (ifVersion == null) {
			throw new IllegalArgumentException("ifVersion is required.");
		}

		int currentVersion = lockFloor(floorId);

		if (currentVersion != ifVersion) {
			throw new MachineLocatorConflictException(currentVersion);
		}

		return currentVersion;
	}

	private int lockFloor(String floorId) {
		return repository.lockFloorVersion(floorId)
				.orElseThrow(() -> floorNotFound(floorId));
	}

	private void bumpVersion(
			String floorId,
			int currentVersion,
			String user
	) {
		// Dòng floor đã bị UPDLOCK nên lệch ở đây là bất thường
		if (repository.bumpVersion(floorId, currentVersion, user) == 0) {
			throw new MachineLocatorConflictException(
					lockFloor(floorId)
			);
		}
	}


	// =========================================================
	// VALIDATE
	// =========================================================

	private void requireBody(Object request) {
		if (request == null) {
			throw new IllegalArgumentException("Request body is required.");
		}
	}

	private String normalizeCode(String code) {

		String safeCode =
				code == null
						? ""
						: code.trim().toUpperCase(Locale.ROOT);

		if (!CODE_PATTERN.matcher(safeCode).matches()) {
			throw new IllegalArgumentException(
					"Invalid machine code '" + code + "'. Expected A-123 or L-123 (2-5 digits)."
			);
		}

		return safeCode;
	}

	/*
	 * Box dạng object của FE ({x, y, w, h, p?, fit?}) -> 3 mảng song song
	 * như RawMachine (b, p, fit), rồi validate / làm tròn bằng validateGeometry.
	 * Mọi p (hoặc mọi fit) đều null / thiếu -> lưu NULL.
	 */
	private Geometry validateBoxes(
			String code,
			List<MachineLocatorBoxRequest> boxes
	) {
		if (boxes == null || boxes.isEmpty()) {
			throw new IllegalArgumentException(code + ": boxes is required.");
		}

		List<List<Double>> rects = new ArrayList<>(boxes.size());
		ArrayNode polys = JsonNodeFactory.instance.arrayNode();
		List<String> fit = new ArrayList<>(boxes.size());

		boolean hasPoly = false;
		boolean hasFit = false;

		for (int i = 0; i < boxes.size(); i++) {

			MachineLocatorBoxRequest box = boxes.get(i);

			if (box == null) {
				throw new IllegalArgumentException(
						code + ": boxes[" + i + "] must be {x, y, w, h}."
				);
			}

			// Arrays.asList cho phép null -> validateGeometry báo lỗi số
			rects.add(Arrays.asList(box.x(), box.y(), box.w(), box.h()));

			JsonNode p = box.p();

			if (p == null || p.isNull()) {
				polys.add(NullNode.getInstance());
			} else {
				polys.add(p);
				hasPoly = true;
			}

			fit.add(box.fit());
			hasFit |= box.fit() != null;
		}

		return validateGeometry(
				code,
				rects,
				hasPoly ? polys : null,
				hasFit ? fit : null
		);
	}

	private Geometry validateGeometry(
			String code,
			List<List<Double>> boxes,
			JsonNode polys,
			List<String> fit
	) {
		if (boxes == null || boxes.isEmpty()) {
			throw new IllegalArgumentException(code + ": boxes is required.");
		}

		int size = boxes.size();

		// b: [x, y, w, h], làm tròn 1 chữ số
		List<List<Double>> roundedBoxes = new ArrayList<>(size);

		for (int i = 0; i < size; i++) {

			List<Double> box = boxes.get(i);

			if (box == null || box.size() != 4) {
				throw new IllegalArgumentException(
						code + ": boxes[" + i + "] must be [x, y, w, h]."
				);
			}

			List<Double> roundedBox = new ArrayList<>(4);

			for (Double value : box) {

				if (value == null || !Double.isFinite(value)) {
					throw new IllegalArgumentException(
							code + ": boxes[" + i + "] must contain numbers only."
					);
				}

				roundedBox.add(round(value, BOX_SCALE));
			}

			roundedBoxes.add(roundedBox);
		}

		// p: (Polygon[] | null)[], làm tròn 2 chữ số
		JsonNode roundedPolys = null;

		if (polys != null && !polys.isNull()) {

			if (!polys.isArray() || polys.size() != size) {
				throw new IllegalArgumentException(
						code + ": polys must be an array with the same length as boxes."
				);
			}

			for (int i = 0; i < size; i++) {

				JsonNode item = polys.get(i);

				if (!item.isNull() && !item.isArray()) {
					throw new IllegalArgumentException(
							code + ": polys[" + i + "] must be an array of polygons or null."
					);
				}
			}

			roundedPolys = roundNode(polys);
		}

		// fit: ('closed' | 'tight' | 'hull' | 'rect' | null)[]
		if (fit != null) {

			if (fit.size() != size) {
				throw new IllegalArgumentException(
						code + ": fit must have the same length as boxes."
				);
			}

			for (int i = 0; i < size; i++) {

				String value = fit.get(i);

				if (value != null && !VALID_FITS.contains(value)) {
					throw new IllegalArgumentException(
							code + ": fit[" + i + "] must be 'closed', 'tight', 'hull', 'rect' or null."
					);
				}
			}
		}

		return new Geometry(roundedBoxes, roundedPolys, fit);
	}

	private void validateCrop(JsonNode crop) {

		boolean valid =
				crop != null
						&& crop.isArray()
						&& crop.size() == 4;

		if (valid) {
			for (JsonNode value : crop) {
				valid &= value.isNumber();
			}
		}

		if (!valid) {
			throw new IllegalArgumentException("crop must be [x, y, w, h].");
		}
	}

	private void validatePage(List<Double> page) {

		boolean valid =
				page != null
						&& page.size() == 2
						&& page.stream().allMatch(value -> value != null && Double.isFinite(value));

		if (!valid) {
			throw new IllegalArgumentException("page must be [w, h].");
		}
	}


	// =========================================================
	// ROUND
	// Giống JS: Math.round(v * 10^n) / 10^n
	// =========================================================

	private static double round(double value, int scale) {

		double factor = Math.pow(10, scale);

		return Math.round(value * factor) / factor;
	}

	private JsonNode roundNode(JsonNode node) {

		if (node.isNumber()) {
			return DoubleNode.valueOf(
					round(node.doubleValue(), POLY_SCALE)
			);
		}

		if (node.isArray()) {

			ArrayNode result = JsonNodeFactory.instance.arrayNode();

			node.forEach(child -> result.add(roundNode(child)));

			return result;
		}

		if (node.isObject()) {

			ObjectNode result = JsonNodeFactory.instance.objectNode();

			node.properties().forEach(field ->
					result.set(field.getKey(), roundNode(field.getValue()))
			);

			return result;
		}

		return node;
	}


	// =========================================================
	// MAPPING
	// =========================================================

	private MachineLocatorFloorDto toFloorDto(MachineLocatorFloorRow floor) {

		Map<String, MachineLocatorMachineDto> machines = new LinkedHashMap<>();

		for (MachineLocatorMachineRow row : repository.findMachines(floor.id())) {
			machines.put(row.code(), toMachineDto(row));
		}

		List<Double> page =
				floor.pageW() != null && floor.pageH() != null
						? List.of(floor.pageW(), floor.pageH())
						: null;

		return new MachineLocatorFloorDto(
				floor.id(),
				floor.name(),
				floor.drawingTitle(),
				floor.imageUrl(),
				readTree(floor.cropJson()),
				page,
				floor.ppp(),
				readTree(floor.exportMetaJson()),
				floor.version(),
				machines
		);
	}

	private MachineLocatorMachineDto toMachineDto(MachineLocatorMachineRow row) {

		return new MachineLocatorMachineDto(
				readValue(row.boxesJson(), BOXES_TYPE),
				readTree(row.polysJson()),
				readValue(row.fitJson(), FIT_TYPE),
				Boolean.TRUE.equals(row.uncertain()),
				row.src(),
				// review = 0 -> không có key "r" (giống FE xóa r)
				Boolean.TRUE.equals(row.review()) ? Boolean.TRUE : null
		);
	}

	private MachineLocatorMachineRow findMachineOrThrow(
			String floorId,
			String code
	) {
		return repository.findMachine(floorId, code)
				.orElseThrow(() ->
						new ResponseStatusException(
								HttpStatus.NOT_FOUND,
								"Machine " + code + " not found on floor " + floorId + "."
						)
				);
	}

	private ResponseStatusException floorNotFound(String floorId) {
		return new ResponseStatusException(
				HttpStatus.NOT_FOUND,
				"Floor " + floorId + " not found."
		);
	}


	// =========================================================
	// HISTORY
	// =========================================================

	private String machineJson(MachineLocatorMachineRow row) {
		return row != null ? toJson(toMachineDto(row)) : null;
	}

	private void writeHistory(
			String floorId,
			String code,
			String action,
			String beforeJson,
			String afterJson,
			String user
	) {
		repository.insertHistory(
				floorId,
				code,
				action,
				beforeJson,
				afterJson,
				user
		);
	}


	// =========================================================
	// JSON
	// =========================================================

	private String toJson(Object value) {

		if (value == null) {
			return null;
		}

		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private JsonNode readTree(String json) {

		if (json == null || json.isBlank()) {
			return null;
		}

		try {
			return objectMapper.readTree(json);
		} catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private <T> T readValue(
			String json,
			TypeReference<T> type
	) {
		if (json == null || json.isBlank()) {
			return null;
		}

		try {
			return objectMapper.readValue(json, type);
		} catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
