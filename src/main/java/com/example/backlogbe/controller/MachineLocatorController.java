package com.example.backlogbe.controller;

import com.example.backlogbe.auth.CurrentUser;
import com.example.backlogbe.auth.Perm;
import com.example.backlogbe.auth.RequirePerm;
import com.example.backlogbe.dto.machinelocator.MachineLocatorBulkRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorFloorSummaryDto;
import com.example.backlogbe.dto.machinelocator.MachineLocatorImportRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorRenameRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorSaveRequest;
import com.example.backlogbe.dto.machinelocator.MachineLocatorVersionRequest;
import com.example.backlogbe.service.MachineLocatorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/*
 * Quyền: MACHINE_LOCATOR – GET = V; PUT (upsert) / rename / confirm / revert / bulk = E; DELETE = D; import = X.
 * Mã NV ghi history lấy từ phiên đăng nhập (CurrentUser).
 * Mọi lệnh ghi trả về floor đầy đủ như GET /floors/{id}.
 */
@RestController
@RequestMapping("/api/machine-locator")
@RequiredArgsConstructor
@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.VIEW)
public class MachineLocatorController {

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
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EDIT)
	public ResponseEntity<MachineLocatorFloorDto> saveMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorSaveRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.saveMachine(id, code, request, CurrentUser.empCodeOrNull(httpRequest))
		);
	}

	@DeleteMapping("/floors/{id}/machines/{code}")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.DELETE)
	public ResponseEntity<MachineLocatorFloorDto> deleteMachine(
			@PathVariable String id,
			@PathVariable String code,
			// Không đánh dấu required để service trả lỗi thống nhất
			@RequestParam(required = false) Integer ifVersion,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.deleteMachine(id, code, ifVersion, CurrentUser.empCodeOrNull(httpRequest))
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/rename")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EDIT)
	public ResponseEntity<MachineLocatorFloorDto> renameMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorRenameRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.renameMachine(id, code, request, CurrentUser.empCodeOrNull(httpRequest))
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/confirm")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EDIT)
	public ResponseEntity<MachineLocatorFloorDto> confirmMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorVersionRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.confirmMachine(id, code, request, CurrentUser.empCodeOrNull(httpRequest))
		);
	}

	@PostMapping("/floors/{id}/machines/{code}/revert")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EDIT)
	public ResponseEntity<MachineLocatorFloorDto> revertMachine(
			@PathVariable String id,
			@PathVariable String code,
			@RequestBody(required = false) MachineLocatorVersionRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.revertMachine(id, code, request, CurrentUser.empCodeOrNull(httpRequest))
		);
	}


	// =========================================================
	// BULK / IMPORT
	// =========================================================

	// Bulk gồm cả op "set" và "del": chỉ kiểm E (cần xác nhận: có cần thêm D khi có op del)
	@PostMapping("/floors/{id}/bulk")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EDIT)
	public ResponseEntity<MachineLocatorFloorDto> bulk(
			@PathVariable String id,
			@RequestBody(required = false) MachineLocatorBulkRequest request,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.bulk(id, request, CurrentUser.empCodeOrNull(httpRequest))
		);
	}

	// Body là nguyên file LayoutData; ifVersion tùy chọn (query param)
	@PostMapping("/floors/{id}/import")
	@RequirePerm(feature = Perm.MACHINE_LOCATOR, action = Perm.EXPORT_IMPORT)
	public ResponseEntity<MachineLocatorFloorDto> importLayout(
			@PathVariable String id,
			@RequestBody(required = false) MachineLocatorImportRequest request,
			@RequestParam(required = false) Integer ifVersion,
			HttpServletRequest httpRequest
	) {

		return ResponseEntity.ok(
				service.importLayout(id, request, ifVersion, CurrentUser.empCodeOrNull(httpRequest))
		);
	}
}
