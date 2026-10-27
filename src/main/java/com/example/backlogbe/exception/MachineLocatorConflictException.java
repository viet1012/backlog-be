package com.example.backlogbe.exception;

import lombok.Getter;

// ifVersion lệch với version hiện tại trong DB -> 409 kèm version hiện tại.
@Getter
public class MachineLocatorConflictException extends RuntimeException {

	private final int currentVersion;

	public MachineLocatorConflictException(int currentVersion) {
		super("Floor data has been changed by someone else. Please reload.");
		this.currentVersion = currentVersion;
	}
}
