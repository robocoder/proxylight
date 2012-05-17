package com.mba.proxylight;

public interface ResponseFilter {
   /**
   * Filter response
   * @param response
   */
  public void filter(Response response);
}
