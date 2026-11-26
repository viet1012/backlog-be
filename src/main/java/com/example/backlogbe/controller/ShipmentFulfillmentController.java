package com.example.backlogbe.controller;

import com.example.backlogbe.auth.Perm;
import com.example.backlogbe.auth.RequirePerm;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.dto.ShipmentDetailFilter;
import com.example.backlogbe.dto.ShipmentFulfillmentDto;
import com.example.backlogbe.service.ShipmentFulfillmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// Quyền: SHIPMENT (Shipment, Packing list) – GET = V (BE chưa có API export / packing list riêng)
@RestController
@RequestMapping("/api/shipment-fulfillment")
@RequiredArgsConstructor
@RequirePerm(feature = Perm.SHIPMENT, action = Perm.VIEW)
public class ShipmentFulfillmentController {
	private final ShipmentFulfillmentService service;

	@GetMapping
	public ResponseEntity<List<ShipmentFulfillmentDto>> getShipmentFulfillment(

			@RequestParam LocalDate fromD,

			@RequestParam LocalDate toD
	) {

		return ResponseEntity.ok(
				service.getShipmentFulfillment(fromD, toD)
		);
	}

	@GetMapping("/detail")
	public ResponseEntity<List<BacklogMainDto>> getDetail(
			@ModelAttribute ShipmentDetailFilter filter
	) {

		return ResponseEntity.ok(
				service.getDetail(filter)
		);
	}
}
