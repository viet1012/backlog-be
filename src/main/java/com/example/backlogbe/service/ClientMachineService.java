package com.example.backlogbe.service;


import org.springframework.stereotype.Service;

import java.net.InetAddress;

@Service
public class ClientMachineService {

	public String resolveMachineName(String ip) {

		if (ip == null || ip.isBlank()) {
			return "UNKNOWN";
		}

		try {
			InetAddress address =
					InetAddress.getByName(ip);

			String hostName =
					address.getCanonicalHostName();

			if (
					hostName == null
							|| hostName.isBlank()
							|| hostName.equals(ip)
			) {
				return ip;
			}

			// Nếu DNS trả dạng:
			// PC001.company.local
			// và bạn chỉ muốn PC001
			int dotIndex = hostName.indexOf('.');

			if (dotIndex > 0) {
				hostName =
						hostName.substring(0, dotIndex);
			}

			return hostName.toUpperCase();

		} catch (Exception e) {
			return ip;
		}
	}
}