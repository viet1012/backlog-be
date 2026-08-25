package com.example.backlogbe.service;

import com.example.backlogbe.dto.ShipmentDetailFilter;
import com.example.backlogbe.dto.ShipmentFulfillmentDto;
import com.example.backlogbe.dto.backlog.BacklogMainDto;
import com.example.backlogbe.repository.ShipmentDetailRepository;
import com.example.backlogbe.repository.ShipmentFulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShipmentFulfillmentService {

	private final ShipmentFulfillmentRepository shipmentFulfillmentRepository;

	private final ShipmentDetailRepository shipmentDetailRepository;


	@Transactional(readOnly = true)
	public List<ShipmentFulfillmentDto> getShipmentFulfillment(
			LocalDate fromD,
			LocalDate toD
	) {

		return shipmentFulfillmentRepository
				.findByDateRange(
						fromD,
						toD
				);
	}


	@Transactional(readOnly = true)
	public List<BacklogMainDto> getDetail(
			ShipmentDetailFilter filter
	) {

		boolean noCustomer =
				filter.cusId() == null
						|| filter.cusId().isBlank();

		boolean noShipBy =
				filter.shipBy() == null
						|| filter.shipBy().isBlank();

		boolean noExportDate =
				filter.exportDate() == null;


		if (
				noCustomer
						&& noShipBy
						&& noExportDate
		) {

			throw new IllegalArgumentException(
					"At least one filter is required: cusId, shipBy or exportDate"
			);
		}


		return shipmentDetailRepository
				.findDetail(filter);
	}
}