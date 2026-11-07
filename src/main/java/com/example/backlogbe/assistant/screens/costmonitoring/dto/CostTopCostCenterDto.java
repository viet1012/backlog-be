package com.example.backlogbe.assistant.screens.costmonitoring.dto;

// Top cost center theo amount (K$): name = PLcostcentername, code = PLcostcenter
public record CostTopCostCenterDto(
		String name,
		String code,
		Double amountK
) {
}
