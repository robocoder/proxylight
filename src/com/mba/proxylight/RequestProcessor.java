package com.mba.proxylight;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public abstract class RequestProcessor {
	private Thread t = null;
	private boolean alive = false;
	private boolean shutdown = false;

	private static int processorsCpt = 1;
	private static int processorsCount = 0;
	private int processorIdx = 1;
	private static long SOCKET_TIMEOUT = 15 * 1000; // 15 secondes d'inactivite
													// max.

	private Selector selector = null;
	private ByteBuffer readBuffer = ByteBuffer.allocate(128);
	private Socket inSocket = null;
	// Pour mieux gerer le keepalive
	private Map<String, Socket> outSockets = new HashMap<String, Socket>();
	private Socket currentOutSocket = null;

	private static final String CRLF = "\r\n";
	private static final byte[] CONNECT_OK = ("HTTP/1.0 200 Connection established"+CRLF+"Proxy-agent: ProxyLight"+CRLF+CRLF).getBytes();

	private enum REQUEST_STEP {
		STATUS_LINE, REQUEST_HEADERS, REQUEST_CONTENT, TRANSFER
	}

	public RequestProcessor() throws IOException {

		t = new Thread(new Runnable() {
			public void run() {
				processorsCount++;
				try {
					while (true) {
						synchronized (RequestProcessor.this) {
							alive = true;
							if (selector == null && !shutdown) {
								try {
									// We'll wait at most for 20 seconds.
									// If nothing for 20 seconds, we'll exit.
									RequestProcessor.this.wait(20 * 1000);
								} catch (InterruptedException e) {
									error(null, e);
									return;
								}
							}
							if (shutdown) {
								return;
							}
						}

						try {
							Request request = null;
							int contentLength = 0;
							REQUEST_STEP step = REQUEST_STEP.STATUS_LINE;
							while (selector != null) {
								selector.select(5000);
								if (inSocket == null) {
									break;
								}
								long now = System.currentTimeMillis();
								if (selector.selectedKeys().size() == 0) {
									long limit = now - SOCKET_TIMEOUT;
									// Faire le menage.
									for (Iterator<Entry<String, Socket>> i = outSockets.entrySet().iterator(); i.hasNext();) {
										Entry<String, Socket> e = i.next();
										Socket so = e.getValue();
										long lastOp = Math.max(so.lastRead, so.lastWrite);
										if (lastOp < limit) {
											debug("processeur " + processorIdx + " : Fermeture pour inactivite de la socket vers " + e.getKey());
											if (request != null && "CONNECT".equals(request.getMethod())) {
												// On ferme tout !
												closeAll();
												break;
											}
											// On ferme
											i.remove();
											try {
												so.socket.close();
											} catch (Exception es) {
												error("", es);
											}
											if (so == currentOutSocket) {
												currentOutSocket = null;
												// prb : je ne sais pas si j'ai répondu ....
											}
										}
									}
									if (outSockets.size() == 0) {
										// Plus rien ???
										closeAll();
									}
									if (inSocket == null) {
										break;
									}
								}
								Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
								while (selectedKeys.hasNext()) {
									SelectionKey key = (SelectionKey) selectedKeys.next();
									selectedKeys.remove();
									if (key.isValid()) {
										if (key.isReadable()) {
											Socket socket = (Socket) key.attachment();
											if (socket == inSocket) {
												readBuffer.clear();
												int numRead = read(inSocket, readBuffer, now);
												if (numRead == -1) {
													// Socket fermee ... On se barre
													closeAll();
													break;
												}
												if (numRead > 0) {
													readBuffer.flip();
													while (readBuffer.remaining() > 0) {
														if (step == REQUEST_STEP.STATUS_LINE) {
															String s = readNext(readBuffer);
															if (s != null) {
																request = new Request();
																request.setStatusline(s);
																step = REQUEST_STEP.REQUEST_HEADERS;
															}
														} else if (step == REQUEST_STEP.REQUEST_HEADERS) {
															String s = readNext(readBuffer);
															if (s != null) {
																if (s.length() == 0) {

																	// D'abord, on filtre
																	if (filterRequest(request)) {
																		throw new Exception("Requete interdite.");
																	}

																	boolean isGet = "GET".equals(request.getMethod());
																	boolean isPost = !isGet && "POST".equals(request.getMethod());
																	boolean isConnect = !isGet && !isPost && "CONNECT".equals(request.getMethod());

																	if (!isGet && !isPost && !isConnect) {
																		throw new RuntimeException("Unknown method : " + request.getMethod());
																	}

																	String oh = request.getHost() + ":" + request.getPort();
																	Socket outSocket = outSockets.get(oh);
																	if (outSocket == null) {
																		// On se connecte a la destination
																		// en synchrone
																		outSocket = new Socket();
																		outSocket.socket = SocketChannel.open();
																		outSocket.socket.configureBlocking(false);
																		if (!outSocket.socket.connect(new InetSocketAddress(resolve(request.getHost()), request.getPort()))) {
																			do {
																				Thread.sleep(50);
																			} while (!outSocket.socket.finishConnect());
																		}
																		outSocket.socket.register(selector, SelectionKey.OP_READ, outSocket);
																		outSockets.put(oh, outSocket);
																		debug("Ajout d'une socket vers " + oh + " sur le processeur " + processorIdx + ". Socket count=" + outSockets.size());
																	}
																	currentOutSocket = outSocket;

																	if (isConnect) {
																		// On répond un header standard et on  connect
																		// les deux sockets
																		ByteBuffer b = ByteBuffer.wrap(CONNECT_OK);
																		write(inSocket, b, now);
																		step = REQUEST_STEP.TRANSFER;
																	} else {
																		// Envoyer la requete et les headers
																		StringBuffer send = new StringBuffer(request.getMethod()).append(" ");
																		String url = request.getUrl();
																		if (!url.startsWith("/")) {
																			url = url.substring(url.indexOf('/', 8));
																		}
																		send.append(url).append(" ").append(request.getProtocol()).append(CRLF);
																		for (Entry<String, String> h : request.getHeaders().entrySet()) {
																			send.append(h.getKey()).append(": ").append(h.getValue()).append(CRLF);
																		}
																		send.append(CRLF);
																		byte[] sendBytes = send.toString().getBytes(); // TEMP
																		// ...
																		ByteBuffer b = ByteBuffer.wrap(sendBytes);
																		write(outSocket, b, now);

																		contentLength = 0;
																		if (isPost) {
																			contentLength = Integer.parseInt(request.getHeaders().get("Content-Length"));
																		}
																		step = contentLength == 0 ? REQUEST_STEP.STATUS_LINE : REQUEST_STEP.REQUEST_CONTENT;
																	}
																} else {
																	request.addHeader(s);
																}
															}
														} else if (step == REQUEST_STEP.REQUEST_CONTENT) {
															contentLength -= readBuffer.remaining();
															if (contentLength <= 0) {
																step = REQUEST_STEP.STATUS_LINE;
															}
															currentOutSocket.socket.write(readBuffer);
														} else if (step == REQUEST_STEP.TRANSFER) {
															write(currentOutSocket, readBuffer, now);
														}
													}
												}
											} else {
												if (socket != currentOutSocket) {
													// Pas de raison qu'il y ai de l'activité sur autre
													// chose que le socket en cours. On ferme.
													closeOutSocket(socket);

												} else {
													// Transférer tel que a inSocket
													if (!transfer(socket, inSocket, now)) {
														// En mode "Connect", on ferme tout.
														if ("CONNECT".equals(request.getMethod())) {
															closeAll();
														} else {
															// On ferme uniquement la socket en cours
															closeOutSocket(socket);
															currentOutSocket = null;
														}
														break;
													}
												}
											}
										}
									}
								}
							}
						} catch (Exception e) {
							error(null, e);
						} finally {
							closeAll();
							recycle();
						}
					}
				} finally {
					closeAll();
					processorsCount--;
					error("Fin du processor " + processorIdx, null);
				}
			}
		});
		t.setName("ProxyLight processor - " + (processorIdx = processorsCpt++));
		t.setDaemon(true);

		t.start();

		while (!isAlive()) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
		}

		debug("Processeur " + processorIdx + " demarre.");
	}

	private void write(Socket socket, ByteBuffer b, long when) throws IOException {
		socket.socket.write(b);
		socket.lastWrite = when;
	}

	private int read(Socket socket, ByteBuffer b, long when) throws IOException {
		int retour = socket.socket.read(b);
		if (retour > 0) {
			socket.lastWrite = when;
		}
		return retour;
	}

	private void closeOutSocket(Socket out) {
		try {
			for (Entry<String, Socket> e : outSockets.entrySet()) {
				if (e.getValue() == out) {
					outSockets.remove(e.getKey());
					debug("Fermeture de la socket vers " + e.getKey());
					break;
				}
			}
			if (out.socket.isOpen()) {
				out.socket.close();
			}
		} catch (Exception e) {
			error("", e);
		}
	}

	private void closeAll() {
		if (inSocket != null) {
			try {
				inSocket.socket.close();
			} catch (Exception e) {
				error(null, e);
			}
			inSocket = null;
		}
		for (Socket outSocket : outSockets.values()) {
			try {
				outSocket.socket.close();
			} catch (Exception e) {
				error(null, e);
			}
			outSocket = null;
		}
		outSockets.clear();
		currentOutSocket = null;
		if (selector != null) {
			try {
				selector.wakeup();
			} catch (Exception e) {
				error(null, e);
			}
			selector = null;
		}
	}

	private boolean transfer(Socket inSocket, Socket outSocket, long when) throws IOException {
		readBuffer.clear();
		int numRead = read(inSocket, readBuffer, when);
		if (numRead == -1) {
			// le socket etait ferme ...
			return false;
		}
		if (numRead > 0) {
			readBuffer.flip();
			write(outSocket, readBuffer, when);
		}
		return true;
	}

	public void process(SelectionKey key) throws IOException {
		synchronized (this) {
			ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

			// Accept the connection and make it non-blocking
			inSocket = new Socket();
			inSocket.socket = serverSocketChannel.accept();
			inSocket.socket.configureBlocking(false);

			selector = SelectorProvider.provider().openSelector();
			inSocket.socket.register(selector, SelectionKey.OP_READ, inSocket);

			notify();
		}
	}

	public abstract void recycle();

	public abstract void error(String message, Throwable t);

	public abstract void debug(String message);

	private char[] read_buf = new char[128];
	int read_offset = 0;

	private String readNext(ByteBuffer buffer) throws IOException {
		int ch;
		boolean atCR = false;
		while (buffer.remaining() > 0) {
			ch = buffer.get();
			if (ch == -1 || ch == '\n') {
				atCR = true;
				break;
			}

			if (ch != '\r') {
				if (read_offset == read_buf.length) {
					char tmpbuf[] = read_buf;
					read_buf = new char[tmpbuf.length * 2];
					System.arraycopy(tmpbuf, 0, read_buf, 0, read_offset);
				}
				read_buf[read_offset++] = (char) ch;
			}
		}
		if (!atCR) {
			return null;
		}
		String s = String.copyValueOf(read_buf, 0, read_offset);
		read_offset = 0;
		return s;
	}

	public void shutdown() {
		closeAll();
		shutdown = true;
		synchronized (this) {
			notify();
		}
	}

	public boolean isAlive() {
		return alive;
	}

	private boolean filterRequest(Request request) {
		List<RequestFilter> filters = getRequestFilters();
		if (filters.size() > 0) {
			for (int i = 0; i < filters.size(); i++) {
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
	
	public abstract InetAddress resolve(String host);
}
