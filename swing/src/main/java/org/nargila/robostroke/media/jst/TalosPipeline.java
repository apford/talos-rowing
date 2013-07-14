/* (based on CortadoPipeline)
 * Copyright (c) 2012 Tal Shalif
 * 
 * This file is part of Talos-Rowing.
 * 
 * Talos-Rowing is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Talos-Rowing is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Talos-Rowing.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.nargila.robostroke.media.jst;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.net.URL;
import java.util.Vector;

import javax.swing.JFrame;

import com.fluendo.jst.Caps;
import com.fluendo.jst.CapsListener;
import com.fluendo.jst.Clock;
import com.fluendo.jst.Element;
import com.fluendo.jst.ElementFactory;
import com.fluendo.jst.Format;
import com.fluendo.jst.Message;
import com.fluendo.jst.Pad;
import com.fluendo.jst.PadListener;
import com.fluendo.jst.Pipeline;
import com.fluendo.jst.Query;
import com.fluendo.utils.Debug;

class TalosPipeline extends Pipeline implements PadListener,
		CapsListener {

	private String url;
	private String userId;
	private String password;
	private boolean enableAudio;
	private boolean enableVideo;
	private boolean keepAspect;
	private boolean ignoreAspect;
	private Component component;
	private int bufferSize = -1;
	private int bufferLow = -1;
	private int bufferHigh = -1;
	private URL documentBase = null;

	private Element httpsrc;
	private Element buffer;
	private Element demux;
	private Element videodec;
	private Element audiodec;
	private Element videosink;
	private Element audiosink;
	private Element v_queue, v_queue2, a_queue = null;
	private Element overlay;
	private Pad asinkpad, ovsinkpad;
	private Pad apad, vpad;
	private final Vector<Element> katedec = new Vector<Element>();
	private final Vector<Element> k_queue = new Vector<Element>();
	private Element kselector = null;

	public boolean usingJavaX = false;
	
	private boolean setupVideoDec(String name) {
		videodec = ElementFactory.makeByName(name, "videodec");
		if (videodec == null) {
			noSuchElement(name);
			return false;
		}
		add(videodec);
		return true;
	}

	@Override
    public void padAdded(Pad pad) {
		Caps caps = pad.getCaps();

		if (caps == null) {
			Debug.log(Debug.INFO, "pad added without caps: " + pad);
			return;
		}
		Debug.log(Debug.INFO, "pad added " + pad);

		String mime = caps.getMime();

		if (enableAudio && mime.equals("audio/x-vorbis")) {
			if (a_queue != null) {
				Debug.log(Debug.INFO,
						"More than one audio stream detected, ignoring all except first one");
				return;
			}

			a_queue = ElementFactory.makeByName("queue", "a_queue");
			if (a_queue == null) {
				noSuchElement("queue");
				return;
			}

			// if we already have a video queue: We want smooth audio playback
			// over frame completeness, so make the video queue leaky
			if (v_queue != null) {
				v_queue.setProperty("leaky", "2"); // 2 == Queue.LEAK_DOWNSTREAM
			}

			audiodec = ElementFactory.makeByName("vorbisdec", "audiodec");
			if (audiodec == null) {
				noSuchElement("vorbisdec");
				return;
			}

			a_queue.setProperty("maxBuffers", "100");

			add(a_queue);
			add(audiodec);

			pad.link(a_queue.getPad("sink"));
			a_queue.getPad("src").link(audiodec.getPad("sink"));
			if (!audiodec.getPad("src").link(asinkpad)) {
				postMessage(Message.newError(this, "audiosink already linked"));
				return;
			}

			apad = pad;

			audiodec.setState(PAUSE);
			a_queue.setState(PAUSE);
		} else if (enableVideo && mime.equals("video/x-theora")) {
			// Constructs a chain of the form
			// oggdemux -> v_queue -> theoradec -> v_queue2 -> videosink
			v_queue = ElementFactory.makeByName("queue", "v_queue");
			v_queue2 = ElementFactory.makeByName("queue", "v_queue2");
			if (v_queue == null) {
				noSuchElement("queue");
				return;
			}

			if (!setupVideoDec("theoradec"))
				return;

			// if we have audio: We want smooth audio playback
			// over frame completeness
			if (a_queue != null) {
				v_queue.setProperty("leaky", "2"); // 2 == Queue.LEAK_DOWNSTREAM
			}
			v_queue.setProperty("maxBuffers", "175");
			v_queue2.setProperty("maxBuffers", "1");

			add(v_queue);
			add(v_queue2);

			pad.link(v_queue.getPad("sink"));
			v_queue.getPad("src").link(videodec.getPad("sink"));
			videodec.getPad("src").link(v_queue2.getPad("sink"));
			if (!v_queue2.getPad("src").link(ovsinkpad)) {
				postMessage(Message.newError(this, "videosink already linked"));
				return;
			}

			vpad = pad;

			videodec.setState(PAUSE);
			v_queue.setState(PAUSE);
			v_queue2.setState(PAUSE);
		} else if (enableVideo && mime.equals("image/jpeg")) {
			if (!setupVideoDec("jpegdec")) {
				return;
			}
			videodec.setProperty("component", component);

			pad.link(videodec.getPad("sink"));
			if (!videodec.getPad("src").link(ovsinkpad)) {
				postMessage(Message.newError(this, "videosink already linked"));
				return;
			}

			videodec.setState(PAUSE);
		} else if (enableVideo && mime.equals("video/x-smoke")) {
			if (!setupVideoDec("smokedec")) {
				return;
			}
			videodec.setProperty("component", component);

			pad.link(videodec.getPad("sink"));
			if (!videodec.getPad("src").link(ovsinkpad)) {
				postMessage(Message.newError(this, "videosink already linked"));
				return;
			}
			vpad = pad;

			videodec.setState(PAUSE);
		} 
	}

    public boolean setPos(double aPos) {
        boolean res;
        com.fluendo.jst.Event event;

        /* get value, convert to PERCENT and construct seek event */
        event = com.fluendo.jst.Event.newSeek(Format.PERCENT,
                (int) (aPos * 100.0 * Format.PERCENT_SCALE));

        /* send event to pipeline */
        res = sendEvent(event);
        if (!res) {
            Debug.log(Debug.WARNING, "seek failed");
        }
        
        return res;
    }
    
    public boolean setTime(long time) {
        boolean res;
        
        com.fluendo.jst.Event event;

        /* get value, convert to PERCENT and construct seek event */
        event = com.fluendo.jst.Event.newSeek(Format.TIME,time);

        /* send event to pipeline */
        res = sendEvent(event);
        
        if (!res) {
            Debug.log(Debug.WARNING, "seek failed");
        }
        
        return res;
    }
    

	@Override
    public void padRemoved(Pad pad) {
		pad.unlink();
		if (pad == vpad) {
			Debug.log(Debug.INFO, "video pad removed " + pad);
			ovsinkpad.unlink();
			// oksinkpad.unlink(); // needed ????
			vpad = null;
		} else if (pad == apad) {
			Debug.log(Debug.INFO, "audio pad removed " + pad);
			asinkpad.unlink();
			apad = null;
		}
	}

	@Override
    public void noMorePads() {
		boolean changed = false;

		Debug.log(Debug.INFO, "all streams detected");

		if (apad == null && enableAudio) {
			Debug.log(Debug.INFO, "file has no audio, remove audiosink");
			audiosink.setState(STOP);
			remove(audiosink);
			audiosink = null;
			changed = true;
			if (videosink != null) {
				videosink.setProperty("max-lateness",
						Long.toString(Long.MAX_VALUE));
			}
		}
		if (vpad == null && enableVideo) {
			Debug.log(Debug.INFO, "file has no video, remove videosink");
			videosink.setState(STOP);
			if (overlay != null) {
				overlay.setState(STOP);
			}

			remove(videosink);
			remove(overlay);
			videosink = null;
			overlay = null;
			changed = true;
		}
		if (changed) {
			scheduleReCalcState();
		}
		
		super.noMorePads();
	}

	public boolean hasVideo() {
		return enableVideo && videosink != null;
	}

	/**
	 * force video sink to resize
	 */
	public void resizeVideo() {
		if (hasVideo()) {			
			videosink.setProperty("bounds", component.getBounds()	);
		}
	}
	
	public TalosPipeline() {
		super("pipeline");
		enableAudio = true;
		enableVideo = true;
	}

	public void setUrl(String anUrl) {
		url = anUrl;
	}

	public String getUrl() {
		return url;
	}

	public void setUserId(String aUserId) {
		userId = aUserId;
	}

	public void setKeepAspect(boolean keep) {
		keepAspect = keep;
	}

	public void setIgnoreAspect(boolean ignore) {
		ignoreAspect = ignore;
	}

	public void setPassword(String aPassword) {
		password = aPassword;
	}

	public void enableAudio(boolean b) {
		enableAudio = b;
	}

	public boolean isAudioEnabled() {
		return enableAudio;
	}

	public void enableVideo(boolean b) {
		enableVideo = b;
	}

	public boolean isVideoEnabled() {
		return enableVideo;
	}


	public void setComponent(Component c) {
		component = c;
	}

	public Component getComponent() {
		return component;
	}

	public void setDocumentBase(URL base) {
		documentBase = base;
	}

	public URL getDocumentBase() {
		return documentBase;
	}

	public void setBufferSize(int size) {
		bufferSize = size;
	}

	public int getBufferSize() {
		return bufferSize;
	}

	public void setBufferLow(int size) {
		bufferLow = size;
	}

	public int getBufferLow() {
		return bufferLow;
	}

	public void setBufferHigh(int size) {
		bufferHigh = size;
	}

	public int getBufferHigh() {
		return bufferHigh;
	}

	public void resize(Dimension d) {

		if (videosink == null || d == null) {
			return;
		}

		Rectangle bounds = new Rectangle(d);

		videosink.setProperty("bounds", bounds);
	}

	public boolean buildOgg() {
		demux = ElementFactory.makeByName("oggdemux", "demux");
		if (demux == null) {
			noSuchElement("oggdemux");
			return false;
		}

		buffer = ElementFactory.makeByName("queue", "buffer");
		if (buffer == null) {
			demux = null;
			noSuchElement("queue");
			return false;
		}
		buffer.setProperty("isBuffer", Boolean.TRUE);
		if (bufferSize != -1)
			buffer.setProperty("maxSize", new Integer(bufferSize * 1024));
		if (bufferLow != -1)
			buffer.setProperty("lowPercent", new Integer(bufferLow));
		if (bufferHigh != -1)
			buffer.setProperty("highPercent", new Integer(bufferHigh));

		add(demux);
		add(buffer);

		httpsrc.getPad("src").link(buffer.getPad("sink"));
		buffer.getPad("src").link(demux.getPad("sink"));
		demux.addPadListener(this);

		buffer.setState(PAUSE);
		demux.setState(PAUSE);

		return true;
	}

	public boolean buildMultipart() {
		demux = ElementFactory.makeByName("multipartdemux", "demux");
		if (demux == null) {
			noSuchElement("multipartdemux");
			return false;
		}
		add(demux);

		httpsrc.getPad("src").link(demux.getPad("sink"));

		demux.addPadListener(this);

		return true;
	}

	@Override
    public void capsChanged(Caps caps) {
		String mime = caps.getMime();

		if (mime.equals("application/ogg")) {
			buildOgg();
		} else if (mime.equals("multipart/x-mixed-replace")) {
			buildMultipart();
		} else {
			postMessage(Message.newError(this, "unknown type: " + mime));
		}
	}

	private void noSuchElement(String elemName) {
		postMessage(Message.newError(this, "no such element: " + elemName
				+ " (check plugins.ini)"));
	}

	private boolean build() {

		httpsrc = ElementFactory.makeByName("httpsrc", "httpsrc");
		if (httpsrc == null) {
			noSuchElement("httpsrc");
			return false;
		}

		httpsrc.setProperty("url", url);
		httpsrc.setProperty("userId", userId);
		httpsrc.setProperty("password", password);

		httpsrc.setProperty("documentBase", documentBase);

		add(httpsrc);

		httpsrc.getPad("src").addCapsListener(this);

		if (enableAudio) {
			audiosink = newAudioSink();
			if (audiosink == null) {
				enableAudio = false; // to suppress creation of audio decoder
										// pads

				component.repaint();
				// Don't stop unless video is disabled too
			} else {
				asinkpad = audiosink.getPad("sink");
				add(audiosink);
			}
		}
		if (enableVideo) {
			videosink = ElementFactory.makeByName("videosink", "videosink");
			if (videosink == null) {
				noSuchElement("videosink");
				return false;
			}

			videosink.setProperty("keep-aspect", keepAspect ? "true" : "false");
			videosink.setProperty("ignore-aspect", ignoreAspect ? "true"
					: "false");

			videosink.setProperty("component", component);
			resize(component.getSize());

			videosink.setProperty("max-lateness",
					Long.toString(enableAudio ? Clock.MSECOND * 20
							: Long.MAX_VALUE));
			add(videosink);

			ovsinkpad = videosink.getPad("sink");
		}
		if (audiosink == null && videosink == null) {
			postMessage(Message.newError(this,
					"Both audio and video are disabled, can't play anything"));
			return false;
		}

		return true;
	}

	protected Element newAudioSink() {
		com.fluendo.plugin.AudioSink s;
		try {
			Class.forName("javax.sound.sampled.AudioSystem");
			Class.forName("javax.sound.sampled.DataLine");
			usingJavaX = true;
			s = (com.fluendo.plugin.AudioSink) ElementFactory.makeByName(
					"audiosinkj2", "audiosink");
			Debug.log(Debug.INFO, "using high quality javax.sound backend");
		} catch (Throwable e) {
			try {
				Class.forName("sun.audio.AudioStream");
				Class.forName("sun.audio.AudioPlayer");
				s = (com.fluendo.plugin.AudioSink) ElementFactory.makeByName(
						"audiosinksa", "audiosink");
				Debug.log(Debug.INFO, "using low quality sun.audio backend");
			} catch (Throwable e2) {
				s = null;
				Debug.log(Debug.INFO, "No audio backend available");
			}
		}
		if (s == null) {
			Debug.warn("Failed to create an audio sink, continuing anyway");
			// noSuchElement ("audiosink");
			return null;
		}
		if (!s.test()) {
			return null;
		} else {
			return s;
		}
	}

	private boolean cleanup() {
		int n;
		Debug.log(Debug.INFO, "cleanup");
		if (httpsrc != null) {
			remove(httpsrc);
			httpsrc = null;
		}
		if (audiosink != null) {
			remove(audiosink);
			audiosink = null;
			asinkpad = null;
		}
		if (videosink != null) {
			remove(videosink);
			videosink = null;
		}
		if (overlay != null) {
			remove(overlay);
			overlay = null;
			ovsinkpad = null;
		}
		if (buffer != null) {
			remove(buffer);
			buffer = null;
		}
		if (demux != null) {
			demux.removePadListener(this);
			remove(demux);
			demux = null;
		}
		if (v_queue != null) {
			remove(v_queue);
			v_queue = null;
		}
		if (v_queue2 != null) {
			remove(v_queue2);
			v_queue2 = null;
		}
		if (a_queue != null) {
			remove(a_queue);
			a_queue = null;
		}
		if (videodec != null) {
			remove(videodec);
			videodec = null;
		}
		if (audiodec != null) {
			remove(audiodec);
			audiodec = null;
		}

		for (n = 0; n < katedec.size(); ++n) {
			if (k_queue.elementAt(n) != null) {
				remove(k_queue.elementAt(n));
			}
			if (katedec.elementAt(n) != null) {
				remove(katedec.elementAt(n));
			}
		}
		k_queue.removeAllElements();
		katedec.removeAllElements();

		if (kselector != null) {
			remove(kselector);
			kselector = null;
		}

		return true;
	}

	@Override
    protected int changeState(int transition) {
		int res;

		switch (transition) {
		case STOP_PAUSE:
			if (!build())
				return FAILURE;
			break;
		default:
			break;
		}

		res = super.changeState(transition);

		switch (transition) {
		case PAUSE_STOP:
			cleanup();
			break;
		default:
			break;
		}

		return res;
	}

	@Override
    protected boolean doSendEvent(com.fluendo.jst.Event event) {
		boolean res;

		if (event.getType() != com.fluendo.jst.Event.SEEK)
			return false;

		if (event.parseSeekFormat() != Format.PERCENT)
			return false;

		if (httpsrc == null)
			return false;

		res = httpsrc.getPad("src").sendEvent(event);
		getState(null, null, -1);

		return res;
	}

	protected long getTime() {
		Query q;
		long result = 0;

		q = Query.newPosition(Format.TIME);
		if (super.query(q)) {
			result = q.parsePositionValue();
		}
		return result;
	}

	protected int getNumKateStreams() {
		return katedec.size();
	}

	protected String getKateStreamCategory(int idx) {
		if (idx < 0 || idx >= katedec.size())
			return "";
		return String.valueOf(katedec.elementAt(idx)
				.getProperty("category"));
	}

	protected String getKateStreamLanguage(int idx) {
		if (idx < 0 || idx >= katedec.size())
			return "";
		return String.valueOf(katedec.elementAt(idx)
				.getProperty("language"));
	}

	public static void main(String[] args) {

		TalosPipeline p = new TalosPipeline();
		JFrame frame = new JFrame();
		Canvas c = new Canvas();
		c.setSize(500, 400);
		frame.getContentPane().add(c);
		p.setUrl(args[0]);
		p.setComponent(c);
		p.setState(Pipeline.PLAY);
		frame.pack();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	void setStepMode(boolean stepMode) {	    
	    v_queue.setProperty("leaky", stepMode ? "0" : "2"); // 2 == Queue.LEAK_DOWNSTREAM
	}
}
