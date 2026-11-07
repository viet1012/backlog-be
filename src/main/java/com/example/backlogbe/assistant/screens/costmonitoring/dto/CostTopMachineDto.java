package com.example.backlogbe.assistant.screens.costmonitoring.dto;

// Top máy theo amount (K$), không gồm dòng Machine rỗng
public record CostTopMachineDto(
		String machine,
		Double amountK
) {
}
