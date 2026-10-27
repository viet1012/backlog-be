package com.example.backlogbe.controller;

import com.example.backlogbe.dto.machinelocator.MachineLocatorBulkRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorSummaryDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorImportRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorRenameRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorSaveRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorVersionRequest;
import com.example.backlogbe.service.MachineLocatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/*
 * Các API ghi cần header X-Employee-Id (MSNV đã login).
 * Service kiểm tra tài khoản HSE_Patrol_Account + role ADMIN.
 * Mọi lệnh ghi trả về floor đầy đủ như GET /floors/{id}.
 *
 * CORS: cùng kiểu @CrossOrigin(origins = "*") như các controller khác,
 * khai báo rõ X-Employee-Id để preflight của PUT / DELETE / POST cho phép.
 */
@CrossOrigin(
		origins = "*",
		allowedHeaders = {"Content-Type", MachineLocatorController.EMPLOYEE_HEADER}
)
@RestController
@RequestMapping("/api/machine-locator")
@RequiredArgsConstructor
public class MachineLocatorController {

	// Không để private: @CrossOrigin ở cấp class cần tham chiếu
	static final String EMPLOYEE_HEADER = "X-Employee-Id";

	private final MachineLocatorService service;


	// =========================================================
	// FLOORS
	// =========================================================

	@GetMapping("/floors")
	public ResponseEntity<List<MachineLocatorFloorSummaryDto>> getFloors() {

		return ResponseEntity.ok(
				service.getFloors()
		);
	}

	@GetMapping("/floors/{id}")
	public ResponseEntity<MachineLocatorFloorDto> getFloor(
			@PathVariable String id
	) {

		return ResponseEntity.ok(
				service.getFloor(id)
		);
	}


	// =========================================================
	// MACHINE
	// =========================================================

	@PutMapping("/floors/{id}/machines/{code}")
	public ResponseEntity<MachineLocatorFloorDto> saveMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorSaveRequest request,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.saveMachine(id, code, request, employeeId)
		);
	}

	@DeleteMapping("/floors/{id}/machines/{code}")
	public ResponseEntity<MachineLocatorFloorDto> deleteMachine(
			@PathVariable String id,
			@PathVariable String code,
			// Không đánh dấu required để service trả lỗi thống nhất
			@RequestParam(required = false) Integer ifVersion,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.deleteMachine(id, code, ifVersion, employeeId)
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/rename")
	public ResponseEntity<MachineLocatorFloorDto> renameMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorRenameRequest request,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.renameMachine(id, code, request, employeeId)
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/confirm")
	public ResponseEntity<MachineLocatorFloorDto> confirmMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorVersionRequest request,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.confirmMachine(id, code, request, employeeId)
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/revert")
	public ResponseEntity<MachineLocatorFloorDto> revertMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorVersionRequest request,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.revertMachine(id, code, request, employeeId)
		);
	}


	// =========================================================
	// BULK / IMPORT
	// =========================================================

	@PostMapping("/floors/{id}/bulk")
	public ResponseEntity<MachineLocatorFloorDto> bulk(
			@PathVariable String id,
			@RequestBody(required = false) MachineLocatorBulkRequest request,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.bulk(id, request, employeeId)
		);
	}

	// Body là nguyên file LayoutData; ifVersion tùy chọn (query param)
	@PostMapping("/floors/{id}/import")
	public ResponseEntity<MachineLocatorFloorDto> importLayout(
			@PathVariable String id,
			@RequestBody(required = false) MachineLocatorImportRequest request,
			@RequestParam(required = false) Integer ifVersion,
			@RequestHeader(value = EMPLOYEE_HEADER, required = false) String employeeId
	) {

		return ResponseEntity.ok(
				service.importLayout(id, request, ifVersion, employeeId)
		);
	}
}
