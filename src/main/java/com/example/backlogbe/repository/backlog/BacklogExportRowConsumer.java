package com.example.backlogbe.repository.backlog;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface BacklogExportRowConsumer {

	void accept(ResultSet rs)
			throws SQLException;
} 