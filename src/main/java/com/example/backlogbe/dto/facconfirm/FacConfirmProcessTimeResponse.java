package com.example.backlogbe.dto.facconfirm;

public record FacConfirmProcessTimeResponse(
		boolean success,
		int updatedCount,
		String message
) {
}