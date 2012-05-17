package com.mba.proxylight;

public interface RequestFilter {

  /**
   * Filter request
   * @param request
   * @return true if proxy should filter/block the request
   */
	public boolean filter(Request request);
}
