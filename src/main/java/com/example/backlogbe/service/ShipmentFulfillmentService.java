package com.example.backlogbe.service;


import com.example.backlogbe.dto.ShipmentFulfillmentDto;
import com.example.backlogbe.repository.ShipmentFulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShipmentFulfillmentService {

	private final ShipmentFulfillmentRepository repository;

	@Transactional(readOnly = true)
	public List<ShipmentFulfillmentDto> getShipmentFulfillment(LocalDate fromD,
	                                                           LocalDate toD) {

		return repository.findByDateRange(fromD, toD);
	}
}