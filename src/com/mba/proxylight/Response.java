package com.mba.proxylight;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class Response {
  private Request request;
  private StringBuilder headersRaw = new StringBuilder();
  private HashMap<String, String> headers = new HashMap<String,String>();
  private int status = -1;

  public Response() {
  }

  public Response(Request request) {
    this.request = request;
  }

  public boolean addRaw(int read, ByteBuffer buf) {
    boolean headersComplete = false;
    String s = new String(buf.array());
    int idx = s.indexOf("\r\n\r\n");
    if (idx != -1) {
      s = s.substring(0, idx);
      headersComplete = true;
    }
    headersRaw.append(s);
    return headersComplete;
  }

  public int getStatus() {
    if (status == -1) parseHeaders();
    return status;
  }

  public Map<String, String> getHeaders() {
    if (status == -1) parseHeaders();
    return headers;
  }

  public Request getRequest() {
    return request;
  }

  public void setRequest(Request request) {
    this.request = request;
  }

  private synchronized void parseHeaders() {
    String[] arr = headersRaw.toString().split("\r\n");
    String[] statusLine = arr[0].split(" ");
    this.status = Integer.parseInt(statusLine[1]);
    for (int i = 1; i < arr.length; ++i) {
      String s = arr[i];
      int idx = s.indexOf(":");
      String key = s.substring(0, idx);
      String value = s.substring(idx + 1, s.length()).trim();
      headers.put(key, value);
    }
  }
}
