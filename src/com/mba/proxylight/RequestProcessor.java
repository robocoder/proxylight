package com.mba.proxylight;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

public abstract class RequestProcessor {
	private Thread t = null;
	private boolean alive = false;
	private boolean shutdown=false;
	
	private static int processorsCpt = 1;
	private static int processorsCount = 0;
	private int processorIdx = 1;
	
	private Selector selector = null;
	private ByteBuffer readBuffer = ByteBuffer.allocate(128);
	private SocketChannel inSocket = null;
	private SocketChannel outSocket = null;

	private static byte[] CONNECT_OK = "HTTP/1.0 200 Connection established\nProxy-agent: ProxyLight\n\n".getBytes();

	private enum REQUEST_STEP {
		STATUS_LINE, HEADERS, CONTENT, TRANSFER
	}

	public RequestProcessor() throws IOException {
		
		t = new Thread(
			new Runnable() {
				public void run() {
					processorsCount++;
					try {
						while (true) {
							synchronized(RequestProcessor.this) {
								alive=true;
								if (selector==null && !shutdown) {
									try {
										RequestProcessor.this.wait(20*1000);
									} catch (InterruptedException e) {
										log(null, e);
										return;
									}
								}
								if (shutdown) {
									return;
								}
							}
	
							try {
								Request request = null;
								String outHost = null;
								int contentLength = 0;
								REQUEST_STEP step = REQUEST_STEP.STATUS_LINE;
								while (selector!=null) {
									selector.select();
									if (inSocket==null) {
										break;
									}
									Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
							        while (selectedKeys.hasNext()) {
							        	SelectionKey key = (SelectionKey) selectedKeys.next();
							            selectedKeys.remove();
							            if (key.isValid()) {
							            	if (key.isReadable()) {
							            		SocketChannel socket = (SocketChannel)key.attachment();
							            		if (socket==inSocket) {
							            			readBuffer.clear();
								            		int numRead = socket.read(readBuffer);
								            		if (numRead==-1) {
								            			// Socket fermee ...
								            			// On se barre
								            			closeAll();
								            			break;
								            		}
								            		if (numRead>0) {
									            		readBuffer.flip();
									            		while (readBuffer.remaining()>0) {
										            		if (step==REQUEST_STEP.STATUS_LINE) {
										            			String s = readNext(readBuffer);
										            			if (s!=null) {
											            			request = new Request();
											            			request.setStatusline(s);
											            			step=REQUEST_STEP.HEADERS;										            			
										            			}
										            		} else
										            		if (step==REQUEST_STEP.HEADERS) {
										            			String s = readNext(readBuffer);
										            			if (s!=null) {
											            			if (s.length()==0) {
											            				
											            				// D'abord, on filtre
											            				if (filterRequest(request)) {
											            					throw new Exception("Requete interdite.");
											            				}
											            				
											            				// C'est la fin ...
											            				if (request.getMethod().equals("CONNECT")) {
											            					// On va juste wrapper input et output
											            					step=REQUEST_STEP.TRANSFER;
											            					// On se connecte a la destination
											            					outSocket = SocketChannel.open();
											            					outSocket.configureBlocking(false);
											            					
											            					if (!outSocket.connect(new InetSocketAddress(request.getHost(), request.getPort()))) {
											            						do {
											            							Thread.sleep(50);
											            						} while (!outSocket.finishConnect());
											            					}
										            						outSocket.register(selector, SelectionKey.OP_READ, outSocket);	
		
										            						// On repond ok
											            					ByteBuffer b = ByteBuffer.wrap(CONNECT_OK);
											            					inSocket.write(b);
											            				} else
											            				if (request.getMethod().equals("GET")) {
											            					String oh = request.getHost()+":"+request.getPort();
											            					if (outSocket!=null) {
											            						if (!outHost.equals(oh)) {
											            							outSocket.close();
											            							outSocket=null;
											            						}
											            					}
											            					
											            					if (outSocket==null) {
												            					// On se connecte a la destination
												            					outSocket = SocketChannel.open();
												            					outSocket.configureBlocking(false);
												            					outHost=oh;
												            					if (!outSocket.connect(new InetSocketAddress(request.getHost(), request.getPort()))) {
												            						do {
												            							Thread.sleep(50);
												            						} while (!outSocket.finishConnect());
												            					}
											            						outSocket.register(selector, SelectionKey.OP_READ, outSocket);	
											            					}
											            					
										            						// Envoyer la requete et les headers
										            						StringBuffer send = new StringBuffer("GET ");
										            						String url = request.getUrl();
										            						if (!url.startsWith("/")) {
										            							url = url.substring(url.indexOf('/', 8));
										            						}
										            						send.append(url).append(" ").append(request.getProtocol()).append("\n");
										            						for (Entry<String, String> h : request.getHeaders().entrySet()) {
										            							send.append(h.getKey()).append(": ").append(h.getValue()).append("\n");
										            						}
										            						send.append("\n");
										            						byte[] sendBytes = send.toString().getBytes(); // TEMP ...
											            					ByteBuffer b = ByteBuffer.wrap(sendBytes);
											            					outSocket.write(b);
											            					
											            					// On reinitialise afin que la prochaine demande soit traitee correctement
											            					step=REQUEST_STEP.STATUS_LINE;
											            				} else 
											            				if (request.getMethod().equals("POST")) {
											            					String oh = request.getHost()+":"+request.getPort();
											            					if (outSocket!=null) {
											            						if (!outHost.equals(oh)) {
											            							outSocket.close();
											            							outSocket=null;
											            						}
											            					}
											            					
											            					if (outSocket==null) {
												            					// On se connecte a la destination
												            					outSocket = SocketChannel.open();
												            					outSocket.configureBlocking(false);
												            					outHost=oh;
												            					if (!outSocket.connect(new InetSocketAddress(request.getHost(), request.getPort()))) {
												            						do {
												            							Thread.sleep(50);
												            						} while (!outSocket.finishConnect());
												            					}
											            						outSocket.register(selector, SelectionKey.OP_READ, outSocket);	
											            					}
											            					
										            						// Envoyer la requete et les headers
										            						StringBuffer send = new StringBuffer("POST ");
										            						String url = request.getUrl();
										            						if (!url.startsWith("/")) {
										            							url = url.substring(url.indexOf('/', 8));
										            						}
										            						send.append(url).append(" ").append(request.getProtocol()).append("\n");
										            						for (Entry<String, String> h : request.getHeaders().entrySet()) {
										            							send.append(h.getKey()).append(": ").append(h.getValue()).append("\n");
										            						}
										            						send.append("\n");
										            						byte[] sendBytes = send.toString().getBytes(); // TEMP ...
											            					ByteBuffer b = ByteBuffer.wrap(sendBytes);
											            					outSocket.write(b);
	
											            					// Transferer le contenu de la requete
											            					step=REQUEST_STEP.CONTENT;
											            					contentLength = Integer.parseInt(request.getHeaders().get("Content-Length"));
											            				}
											            			} else {
											            				request.addHeader(s);
											            			}
										            			}
										            		} else
										            		if (step==REQUEST_STEP.CONTENT) {
										            			contentLength-=readBuffer.remaining();
									            				if (contentLength<=0) {
									            					step=REQUEST_STEP.STATUS_LINE;
									            				}
										            			outSocket.write(readBuffer);		
										            		} else
										            		if (step==REQUEST_STEP.TRANSFER) {
										            			outSocket.write(readBuffer);		
										            		}
									            		}
								            		}
							            		} else
							            		if (socket==outSocket) {
							            			if (key.isReadable()) {
								            			// Transférer tel que a inSocket
								            			if (!transfer(outSocket, inSocket)) {
								            				closeAll();
								            				break;
								            			}
							            			}
							            		}
							            	}
							            }
							        }
								}
							} catch (Exception e) {
								log(null, e);
							} finally {
								closeAll();
								recycle();
							}
						}		
					} finally {
						closeAll();
						processorsCount--;
					}
				}
			}
		);
		t.setName("ProxyLight processor - "+(processorIdx=processorsCpt++));
		t.setDaemon(true);
		
		t.start();
		
		while (!isAlive()) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
		}
	}

	private void closeAll() {
		if (inSocket!=null) {
			try {
				inSocket.close();
			} catch (Exception e) {
				log(null, e);
			}
			inSocket=null;
		}
		if (outSocket!=null) {
			try {
				outSocket.close();
			} catch (Exception e) {
				log(null, e);	
			}
			outSocket=null;
		}
		if (selector!=null) {
			try {
				selector.wakeup();
			} catch (Exception e) {
				log(null, e);
			}
			selector=null;
		}
	}
	
	private boolean transfer(SocketChannel inSocket, SocketChannel outSocket) throws IOException {
		readBuffer.clear();
		int numRead = inSocket.read(readBuffer);
		if (numRead==-1) {
			// le socket etait ferme ...
			return false;
		}
		if (numRead>0) {
    		readBuffer.flip();
			outSocket.write(readBuffer);
		}
		return true;
	}
	
	public void process(SelectionKey key) throws IOException {
		synchronized(this) {
			ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
	
		    // Accept the connection and make it non-blocking
		    inSocket = serverSocketChannel.accept();
		    inSocket.configureBlocking(false);
	
		    selector = SelectorProvider.provider().openSelector();
		    inSocket.register(selector, SelectionKey.OP_READ, inSocket);
		    
	    	notify();
	    }
	}
	
	public abstract void recycle();
	public abstract void log(String message, Throwable t);
	
	private char[] read_buf = new char[128];
	int read_offset = 0;
	private String readNext(ByteBuffer buffer) throws IOException {
		int ch;
		boolean atCR = false;
		while (buffer.remaining()>0) {
		    ch = buffer.get();
		    if (ch == -1 || ch == '\n'){
		    	atCR=true;
		    	break;
		    }
		    
		    if (ch != '\r'){
				if (read_offset == read_buf.length) {
				    char tmpbuf[] = read_buf;
				    read_buf = new char[tmpbuf.length * 2];
				    System.arraycopy(tmpbuf, 0, read_buf, 0, read_offset);
				}
				read_buf[read_offset++] = (char) ch;
		    }
		}
		if (!atCR)  {
			return null;
		}
		String s = String.copyValueOf(read_buf, 0, read_offset);
		read_offset = 0;
		return s;
    }
	
	
	public void shutdown() {
		closeAll();
		shutdown=true;
		synchronized(this) {
			notify();
		}
	}
	
	public boolean isAlive() {
		return alive;
	}
	
	private boolean filterRequest(Request request) {
		List<RequestFilter> filters = getRequestFilters();
		if (filters.size()>0) {
			for (int i=0; i<filters.size(); i++) {
				RequestFilter filter = filters.get(i);
				if (filter.filter(request)) {
					return true;
				}
			}
		}
		return false;
	}
	public abstract List<RequestFilter> getRequestFilters();
	
	public abstract String getRemoteProxyHost();
	
	public abstract int getRemoteProxyPort();
}
