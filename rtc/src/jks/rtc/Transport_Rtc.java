package jks.rtc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Tabs;
import jks.net.Net_Transport;

/**
 * A browser tab's transport, seen from the desktop (r46, d7) : one WebRTC data channel per tab, each a
 * {@link Net_Peer} named "rtc/N", behind the same {@link Net_Transport} the UDP peers are behind, so
 * HostSession cannot tell a tab from a desktop.
 *
 * The channel is {ordered:false, maxRetransmits:0} : lossy and unordered, the contract this interface
 * already asks the protocol above to survive, and what a tab's side must open as well. A tab cannot
 * open a UDP socket (the sandbox), and this is the one unreliable thing it can open.
 *
 * WHAT IS NOT LIKE Transport_Udp, deliberately :
 * <ul>
 * <li>libwebrtc opens ITS OWN sockets, beside the game's one. The one-socket rule is about the lobby
 *     seeing the mapping game traffic arrives on ; here ICE finds its own way, with the servers passed
 *     in, and the lobby only carries the two descriptions.</li>
 * <li>A peer exists only once a call was made : {@link #answer} (the host, given a tab's offer) or
 *     {@link #offer} (a tab, or the gate playing one). {@link #resolve} of a name nobody called gives a
 *     peer nothing reaches, which times out like any other.</li>
 * <li>Descriptions are whole, not trickled : a call's {@link Call#sdp()} is null until ICE gathering is
 *     COMPLETE, so one message each way is the whole signalling.</li>
 * </ul>
 *
 * libwebrtc calls back on its own threads ; they only fill queues. Everything a caller sees is handed
 * over in {@link #pump}, on the caller's thread, as the interface promises. Audio is the headless module :
 * a transport never opens a sound device.
 */
public final class Transport_Rtc implements Net_Tabs
{
	/** How a data channel's peer is named : "rtc/" and a number, unique within this transport. */
	public static final String PREFIX = Net_Tabs.PREFIX;
	/** The label both ends give the channel. */
	public static final String LABEL = "game";
	public static final long DEFAULT_TIMEOUT_MS = 10_000;

	/** A STUN or TURN server, as an RTCIceServer url : "stun:host:port", "turn:host:port". */
	public static final class Server
	{
		public final String url, username, password;

		public Server(String url)
		{
			this(url, null, null);
		}

		public Server(String url, String username, String password)
		{
			this.url = url;
			this.username = username;
			this.password = password;
		}
	}

	/** One tab being put in touch, and then its channel. Read only from the thread that pumps. */
	public final class Call implements Net_Tabs.Tab
	{
		final Peer peer;
		final RTCPeerConnection connection;
		volatile RTCDataChannel channel;
		volatile String sdp, failure;
		volatile boolean gathered;

		Call(Peer peer, RTCPeerConnection connection)
		{
			this.peer = peer;
			this.connection = connection;
		}

		@Override
		public Net_Peer peer()
		{
			return peer;
		}

		/** This end's whole description, candidates included, or null while ICE is still gathering. */
		@Override
		public String sdp()
		{
			return sdp;
		}

		/** Why this call cannot go on, or null. A failed call's peer is reported lost. */
		@Override
		public String failure()
		{
			return failure;
		}

		/** True once the channel is open both ways : a packet sent now is on its way. */
		@Override
		public boolean open()
		{
			RTCDataChannel channel = this.channel;
			return channel != null && channel.getState() == RTCDataChannelState.OPEN;
		}

		/** Whether the channel keeps order : false, on both ends, or it is not the channel this game opens. */
		public boolean ordered()
		{
			RTCDataChannel channel = this.channel;
			return channel != null && channel.isOrdered();
		}

		/** How often a lost packet is sent again : 0. */
		public int maxRetransmits()
		{
			RTCDataChannel channel = this.channel;
			return channel == null ? -1 : channel.getMaxRetransmits();
		}

		/** An offering end hands it the far end's answer. */
		public void answered(String answer)
		{
			try
			{
				connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, answer), set(this, "the answer"));
			}
			catch (Throwable e)
			{
				fail("the answer : " + e.getMessage());
			}
		}

		void fail(String why)
		{
			if (failure == null)
				failure = why;
			events.add(new Event(this, null));
		}
	}

	static final class Peer implements Net_Peer
	{
		final String address;

		Peer(String address)
		{
			this.address = address;
		}

		@Override
		public String address()
		{
			return address;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Peer && ((Peer) other).address.equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}

		@Override
		public String toString()
		{
			return address;
		}
	}

	/** Something libwebrtc said, for the pumping thread : a packet, or (bytes null) a call that failed or closed. */
	static final class Event
	{
		final Call call;
		final byte[] bytes;

		Event(Call call, byte[] bytes)
		{
			this.call = call;
			this.bytes = bytes;
		}
	}

	final PeerConnectionFactory factory;
	final RTCConfiguration configuration = new RTCConfiguration();
	final LongSupplier clock;
	final long timeoutMs;
	final Map<String, Call> calls = new LinkedHashMap<String, Call>();
	final Map<String, Long> lastHeard = new LinkedHashMap<String, Long>();
	final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<Event>();
	final AtomicInteger dropped = new AtomicInteger();
	int next = 1;
	boolean closed;

	/**
	 * A host's tabs, or null when this machine cannot have any : the rtc module ships only the natives of
	 * the machine that built it (rtc/build.gradle), so a dist built elsewhere has none for this one, and
	 * libwebrtc failing to load must cost the tabs, never the desktop players. Why is said on stderr.
	 */
	public static Transport_Rtc open(List<Server> servers)
	{
		try
		{
			return new Transport_Rtc(servers);
		}
		catch (Throwable e)
		{
			// UnsatisfiedLinkError, NoClassDefFoundError, ExceptionInInitializerError : a missing or foreign native
			System.err.println("rtc : no WebRTC on this machine, browser tabs cannot join : " + e);
			return null;
		}
	}

	/** STUN servers as {@link Server}s, from "host:port" : what a host gathers its public candidates with. */
	public static List<Server> stun(List<String> servers)
	{
		List<Server> made = new ArrayList<Server>();
		for (String server : servers)
			made.add(new Server("stun:" + server));
		return made;
	}

	public Transport_Rtc(List<Server> servers)
	{
		this(servers, () -> System.nanoTime() / 1_000_000L, DEFAULT_TIMEOUT_MS);
	}

	public Transport_Rtc(List<Server> servers, LongSupplier clock, long timeoutMs)
	{
		this.clock = clock;
		this.timeoutMs = timeoutMs;
		for (Server server : servers)
		{
			RTCIceServer ice = new RTCIceServer();
			ice.urls.add(server.url);
			if (server.username != null)
			{
				ice.username = server.username;
				ice.password = server.password;
			}
			configuration.iceServers.add(ice);
		}
		factory = new PeerConnectionFactory(new HeadlessAudioDeviceModule());
	}

	// ---------------------------------------------------------------- calls

	/** A tab's end, or the gate playing one : opens the channel and describes it. Send {@link Call#sdp()} to the host once it is not null. */
	public Call offer()
	{
		Call call = newCall();
		RTCDataChannelInit init = new RTCDataChannelInit();
		init.ordered = false;
		init.maxRetransmits = 0;
		opened(call, call.connection.createDataChannel(LABEL, init));
		call.connection.createOffer(new RTCOfferOptions(), described(call));
		return call;
	}

	/** The host's end : answers a tab's offer. Send {@link Call#sdp()} back to the tab once it is not null. */
	@Override
	public Call answer(String offer)
	{
		Call call = newCall();
		// A description that does not parse THROWS out of libwebrtc (a java.lang.Error) : it came from a
		// stranger, through the lobby, and must fail its call, never the host
		try
		{
			call.connection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offer), answering(call));
		}
		catch (Throwable e)
		{
			call.fail("the offer : " + e.getMessage());
		}
		return call;
	}

	SetSessionDescriptionObserver answering(Call call)
	{
		return new SetSessionDescriptionObserver()
		{
			@Override
			public void onSuccess()
			{
				call.connection.createAnswer(new RTCAnswerOptions(), described(call));
			}

			@Override
			public void onFailure(String error)
			{
				call.fail("the offer : " + error);
			}
		};
	}

	/** The call behind a peer, or null. */
	public Call call(Net_Peer peer)
	{
		return calls.get(peer.address());
	}

	Call newCall()
	{
		if (closed)
			throw new IllegalStateException("closed");
		Peer peer = new Peer(PREFIX + next++);
		Call[] made = new Call[1];
		RTCPeerConnection connection = factory.createPeerConnection(configuration, new PeerConnectionObserver()
		{
			@Override
			public void onIceCandidate(RTCIceCandidate candidate)
			{
				// Not trickled : the whole description is sent once gathering is complete
			}

			@Override
			public void onIceGatheringChange(RTCIceGatheringState state)
			{
				if (state == RTCIceGatheringState.COMPLETE && made[0] != null)
					ready(made[0]);
			}

			@Override
			public void onConnectionChange(RTCPeerConnectionState state)
			{
				if (made[0] != null && (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED))
					made[0].fail("the connection " + state);
			}

			@Override
			public void onDataChannel(RTCDataChannel channel)
			{
				if (made[0] != null)
					opened(made[0], channel);
			}
		});
		Call call = new Call(peer, connection);
		made[0] = call;
		calls.put(peer.address, call);
		lastHeard.put(peer.address, Long.valueOf(clock.getAsLong()));
		return call;
	}

	void opened(Call call, RTCDataChannel channel)
	{
		call.channel = channel;
		channel.registerObserver(new RTCDataChannelObserver()
		{
			@Override
			public void onBufferedAmountChange(long previousAmount)
			{
			}

			@Override
			public void onStateChange()
			{
				if (channel.getState() == RTCDataChannelState.CLOSED)
					call.fail("the channel closed");
			}

			@Override
			public void onMessage(RTCDataChannelBuffer buffer)
			{
				// Valid only during this call : copied for the pumping thread
				if (buffer.data.remaining() > MAX_PAYLOAD)
				{
					dropped.incrementAndGet();
					return;
				}
				byte[] bytes = new byte[buffer.data.remaining()];
				buffer.data.get(bytes);
				events.add(new Event(call, bytes));
			}
		});
	}

	CreateSessionDescriptionObserver described(Call call)
	{
		return new CreateSessionDescriptionObserver()
		{
			@Override
			public void onSuccess(RTCSessionDescription description)
			{
				call.connection.setLocalDescription(description, set(call, "this end's description"));
			}

			@Override
			public void onFailure(String error)
			{
				call.fail("describing this end : " + error);
			}
		};
	}

	SetSessionDescriptionObserver set(Call call, String what)
	{
		return new SetSessionDescriptionObserver()
		{
			@Override
			public void onSuccess()
			{
				// Gathering may have finished before the description was set : it is complete now either way
				if (call.connection.getIceGatheringState() == RTCIceGatheringState.COMPLETE)
					ready(call);
			}

			@Override
			public void onFailure(String error)
			{
				call.fail(what + " : " + error);
			}
		};
	}

	void ready(Call call)
	{
		RTCSessionDescription local = call.connection.getLocalDescription();
		if (local != null && call.sdp == null)
			call.sdp = local.sdp;
	}

	// ---------------------------------------------------------------- Net_Transport

	@Override
	public Net_Peer resolve(String address)
	{
		Call call = calls.get(address);
		if (call != null)
			return call.peer;
		// Nobody called it : a peer nothing reaches, forgotten after the timeout like a silent one
		lastHeard.putIfAbsent(address, Long.valueOf(clock.getAsLong()));
		return new Peer(address);
	}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		if (payload.remaining() > MAX_PAYLOAD)
			throw new IllegalArgumentException(payload.remaining() + " B is more than the " + MAX_PAYLOAD + " B a packet may carry");
		Call call = calls.get(peer.address());
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		if (call == null || !call.open())
		{
			dropped.incrementAndGet();
			return;
		}
		try
		{
			call.channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), true));
		}
		catch (Exception e)
		{
			dropped.incrementAndGet();
		}
	}

	@Override
	public int pump(Net_Listener listener)
	{
		int delivered = 0;
		List<Call> gone = new ArrayList<Call>();
		ByteBuffer in = ByteBuffer.allocate(MAX_PAYLOAD);
		for (Event event; (event = events.poll()) != null;)
		{
			if (!calls.containsKey(event.call.peer.address))
				continue;
			if (event.bytes == null)
			{
				if (!gone.contains(event.call))
					gone.add(event.call);
				continue;
			}
			lastHeard.put(event.call.peer.address, Long.valueOf(clock.getAsLong()));
			in.clear();
			in.put(event.bytes).flip();
			listener.received(event.call.peer, in);
			delivered++;
		}
		long now = clock.getAsLong();
		Iterator<Map.Entry<String, Long>> it = lastHeard.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, Long> heard = it.next();
			if (now - heard.getValue().longValue() < timeoutMs)
				continue;
			it.remove();
			Call call = calls.get(heard.getKey());
			if (call != null)
			{
				if (!gone.contains(call))
					gone.add(call);
			}
			else
				listener.lost(new Peer(heard.getKey()));
		}
		for (Call call : gone)
		{
			hangUp(call);
			listener.lost(call.peer);
		}
		return delivered;
	}

	/** Ends a call without reporting it lost : its own end said it was leaving, or the host let it go. */
	public void forget(Net_Peer peer)
	{
		Call call = calls.get(peer.address());
		if (call != null)
			hangUp(call);
		lastHeard.remove(peer.address());
	}

	void hangUp(Call call)
	{
		calls.remove(call.peer.address);
		lastHeard.remove(call.peer.address);
		RTCDataChannel channel = call.channel;
		if (channel != null)
		{
			channel.unregisterObserver();
			channel.close();
			channel.dispose();
		}
		call.connection.close();
	}

	@Override
	public int localPort()
	{
		return 0;
	}

	@Override
	public List<String> localAddresses()
	{
		// libwebrtc gathers its own
		return Collections.emptyList();
	}

	@Override
	public int dropped()
	{
		return dropped.get();
	}

	@Override
	public void close()
	{
		if (closed)
			return;
		closed = true;
		for (Call call : new ArrayList<Call>(calls.values()))
			hangUp(call);
		events.clear();
		factory.dispose();
	}
}
