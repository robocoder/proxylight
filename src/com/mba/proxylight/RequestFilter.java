package com.mba.proxylight;

public interface RequestFilter {

	/**
	 * Retourne true si le proxy ne doit meme pas repondre ...
	 */
	public boolean filter(Request request);
}
