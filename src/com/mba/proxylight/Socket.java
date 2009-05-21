package com.mba.proxylight;

import java.nio.channels.SocketChannel;

public class Socket {

	public SocketChannel socket = null;
	public long created = System.currentTimeMillis();
	public long lastWrite = created;
	public long lastRead = created;
}
