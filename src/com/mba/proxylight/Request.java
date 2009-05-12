package com.mba.proxylight;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Request {
	
	private String statusline=null, method=null, url=null, protocol=null;
	private Map<String, String> headers = new LinkedHashMap<String, String>();
	public String getStatusline() {
		return statusline;
	}
	public void setStatusline(String statusline) {
		this.statusline = statusline;
		
		// On decoupe
		int idx1 = statusline.indexOf(' ');
		if (idx1==-1 || idx1<3) { // au moins GET ...
			throw new IllegalArgumentException("statusline: "+statusline);
		}
		method=statusline.substring(0, idx1);
		
		do {
			idx1++;
		} while (statusline.charAt(idx1)==' ');
		int idx2 = statusline.indexOf(' ', idx1);
		if (idx2==-1) {
			throw new IllegalArgumentException("statusline: "+statusline);
		}
		url=statusline.substring(idx1, idx2);
		
		do {
			idx2++;
		} while (statusline.charAt(idx2)==' ');
		protocol=statusline.substring(idx2);
	}
	public String getMethod() {
		return method;
	}
	public void setMethod(String method) {
		this.method = method;
	}
	public String getUrl() {
		return url;
	}
	private static Pattern CONNECT_PATTERN = Pattern.compile("(.*):([\\d]+)");
	private static Pattern GETPOST_PATTERN = Pattern.compile("(https?)://([^:/]+)(:[\\d]+])?/.*");
	private String host = null; 
	private int port = -1;
	public String getHost() {
		if (host!=null) {
			return host;
		}
		if (getUrl()!=null) {
			if ("CONNECT".equals(method)) {
				Matcher m = CONNECT_PATTERN.matcher(getUrl());
				if (m.matches()) {
					host = m.group(1);
					port = Integer.parseInt(m.group(2));
				}
			} else {
				Matcher m = GETPOST_PATTERN.matcher(getUrl());
				if (m.matches()) {
					host = m.group(2);
					if (m.group(3)!=null) {
						Integer.parseInt(m.group(3).substring(1));
					} else {
						if ("http".equals(m.group(1))) {
							port=80;
						} else {
							port=443;
						}
					}
				}
			}
			if (host==null) {
				host = getHeaders().get("Host");
				int idx = host.indexOf(':');
				if (idx>-1) {
					port = Integer.parseInt(host.substring(idx+1));
					host = host.substring(0, idx);
				} else {
					port = 80;
				}
			}
		}
		return host;
	}
	public int getPort() {
		getHost();
		return port;	
	}
	public void setUrl(String url) {
		this.url = url;
	}
	public String getProtocol() {
		return protocol;
	}
	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}
	public Map<String, String> getHeaders() {
		return headers;
	}
	public void addHeader(String h) {
		int idx1 = 1;
		while (h.charAt(idx1)!=':' && h.charAt(idx1)!=' ') {
			idx1++;
		}
		String name = h.substring(0, idx1);
		do {
			idx1++;
		} while (h.charAt(idx1)==':' || h.charAt(idx1)==' ');
		String value = h.substring(idx1);
		
		headers.put(name, value);		
	}	
	
	public void dump() {
		System.err.println("Statusline: "+statusline);
		System.out.println("Method: "+method);
		System.out.println("Url: "+url);
		System.out.println("Protocol: "+protocol);
		System.out.println("Headers:");
		for (Entry<String, String> e : headers.entrySet()) {
			System.out.println("   "+e.getKey()+": "+e.getValue());
		}
	}
	
}
