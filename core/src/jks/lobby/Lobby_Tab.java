package jks.lobby;

import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Tabs;
import jks.net.Net_Transport;

/**
 * Online play's lobby, from a browser tab (r82) : what {@link Lobby_Client} is to a desktop, for a machine
 * that is a player and nothing else (d7). It speaks the same Lobby_Codec bytes, over the service's
 * WebSocket front (jks.lobby.Transport_Ws, r79) instead of the game's UDP socket, and it does the four
 * things a tab can : BROWSE, JOIN by code, OFFER its WebRTC description to the host it joined, and take
 * the host's ANSWER. No HOST (a tab cannot host), no ICE of its own (its RTCPeerConnection does that), no
 * PEER mirrored to it (nobody can punch to a tab).
 *
 * Built on two transports, both behind Net_Transport : the WebSocket, whose one peer is the service, and
 * the {@link Offerer} that makes the data channel the game then plays on. The WebSocket is this object's ;
 * the offerer is the caller's, because the session outlives the lobby screen.
 *
 * JDK-only and plain : the browser build compiles it (html/), and nettest drives it with java.net.http's
 * WebSocket and a fake offerer (Net_Tab_Checks.tabLobbyJoinsAndOffers).
 *
 * Not thread safe. Pump it from the thread that runs the loop.
 */
public final class Lobby_Tab
{
	/** How often the tab asks the service again while it waits, as the desktop lobby refreshes. */
	public static final long REFRESH_MS = Lobby_Client.REFRESH_MS;
	/**
	 * How long a host has to answer an offer. A host with no WebRTC (its natives did not load) never answers :
	 * the refusal is silence (r80), so the tab gives up on it after this long and says why.
	 */
	public static final long ANSWER_MS = 10_000;
	/** How long the channel has to open once answered : the host forgets a call silent for 10 s. */
	public static final long OPEN_MS = 10_000;

	/**
	 * The tab's end of a WebRTC call : a Net_Transport over RTCDataChannels (html's Transport_Channel ;
	 * jks.rtc.Transport_Rtc on a desktop playing a tab).
	 */
	public interface Offerer extends Net_Transport
	{
		/**
		 * Opens a data channel {ordered:false, maxRetransmits:0} toward a host, through the relay the service
		 * named in JOINED (null : none) as the call's TURN server. {@link Call#sdp()} is the whole offer once
		 * ICE gathering is complete.
		 */
		Call offer(Lobby_Message.Relay relay);
	}

	/** One offer and then its channel : {@link Net_Tabs.Tab#peer()} is where the session plays once {@link #open()}. */
	public interface Call extends Net_Tabs.Tab
	{
		/** The host's answer, as the service brought it : a stranger's text, which may not parse. Never throws for it. */
		void answered(String sdp);
	}

	public enum State
	{
		/** On the list, not joining anything. */
		IDLE,
		/** JOIN sent, JOINED not heard. */
		JOINING,
		/** Joined : describing this end, then waiting for the host's answer. */
		OFFERING,
		/** The host answered : the channel is being opened. */
		CONNECTING,
		/** The channel is open both ways : {@link Lobby_Tab#peer()} is the host. */
		OPEN,
		/** Refused, unanswered, or the channel failed : {@link Lobby_Tab#failure()} says which. */
		FAILED
	}

	private final Net_Transport socket;
	private final Net_Peer service;
	private final Offerer offerer;
	private final LongSupplier clock;

	private State state = State.IDLE;
	private String code;
	private Lobby_Message.Listing listing;
	private Lobby_Message.Joined joined;
	private Lobby_Message.Refused refused;
	private int outdated = -1;
	private boolean serviceLost;
	private Call call;
	private boolean offerSent;
	/** When the current state began, for its timeout. */
	private long since;
	private String failure;

	/**
	 * @param socket the WebSocket to the service's front : this object's from now on, closed with it
	 * @param service the service's front as the socket resolves it ("host:port")
	 * @param offerer where the data channel is made : the caller's, never closed here
	 */
	public Lobby_Tab(Net_Transport socket, String service, Offerer offerer, LongSupplier clock)
	{
		this.socket = socket;
		this.service = socket.resolve(service);
		this.offerer = offerer;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- asking

	/** Asks for the open lobbies of this game's version : {@link #listing()} once the service answers. */
	public void browse()
	{
		send(new Lobby_Message.Browse());
	}

	/** Joins the lobby of this code, and offers the host a channel once joined. A join already under way is dropped. */
	public void join(String code)
	{
		this.code = code;
		joined = null;
		refused = null;
		call = null;
		offerSent = false;
		failure = null;
		to(State.JOINING);
		Lobby_Message.Join join = new Lobby_Message.Join();
		join.code = code;
		send(join);
	}

	/** Back to the list : what is joined is forgotten, and a channel being made is left to time out at the host. */
	public void stopJoining()
	{
		code = null;
		joined = null;
		call = null;
		to(State.IDLE);
	}

	// ---------------------------------------------------------------- every frame

	/** Delivers what the service said, then moves the join on : the offer out once whole, the timeouts. */
	public void pump()
	{
		socket.pump(listener);
		long now = clock.getAsLong();
		switch (state)
		{
			case JOINING :
				// The service is reliable (TCP) but may have been busy : ask again, as the desktop lobby does
				if (now - since >= REFRESH_MS)
				{
					Lobby_Message.Join join = new Lobby_Message.Join();
					join.code = code;
					send(join);
					since = now;
				}
				break;
			case OFFERING :
				if (call.failure() != null)
					fail("this browser could not make a WebRTC offer : " + call.failure());
				else if (!offerSent && call.sdp() != null)
				{
					// Whole, never trickled : one message each way is the whole signalling (r79)
					send(new Lobby_Message.Offer(code, call.sdp()));
					offerSent = true;
					since = now;
				}
				else if (offerSent && now - since >= ANSWER_MS)
					fail("the host did not answer : it cannot take browser players");
				break;
			case CONNECTING :
				if (call.failure() != null)
					fail(call.failure());
				else if (call.open())
					to(State.OPEN);
				else if (now - since >= OPEN_MS)
					fail("the connection to the host did not open");
				break;
			case OPEN :
				if (call.failure() != null)
					fail(call.failure());
				break;
			default :
				break;
		}
	}

	private final Net_Listener listener = new Net_Listener()
	{
		@Override
		public void received(Net_Peer from, ByteBuffer payload)
		{
			Lobby_Message message;
			try
			{
				message = Lobby_Codec.decode(payload);
			}
			catch (Net_Rejected e)
			{
				return;
			}
			serviceLost = false;
			switch (message.type())
			{
				case OUTDATED :
					outdated = ((Lobby_Message.Outdated) message).version;
					break;
				case LISTING :
					listing = (Lobby_Message.Listing) message;
					break;
				case JOINED :
					Lobby_Message.Joined yes = (Lobby_Message.Joined) message;
					if (state != State.JOINING || !yes.code.equals(code))
						break;
					joined = yes;
					call = offerer.offer(yes.relay);
					to(State.OFFERING);
					break;
				case ANSWER :
					Lobby_Message.Answer answer = (Lobby_Message.Answer) message;
					if (state != State.OFFERING || !offerSent || !answer.code.equals(code))
						break;
					call.answered(answer.sdp);
					to(State.CONNECTING);
					break;
				case REFUSED :
					Lobby_Message.Refused no = (Lobby_Message.Refused) message;
					if ((state == State.JOINING || state == State.OFFERING) && no.code != null && no.code.equals(code))
					{
						refused = no;
						fail("refused : " + no.reason);
					}
					break;
				default :
					break;
			}
		}

		@Override
		public void lost(Net_Peer peer)
		{
			serviceLost = true;
		}
	};

	private void to(State next)
	{
		state = next;
		since = clock.getAsLong();
	}

	private void fail(String why)
	{
		failure = why;
		to(State.FAILED);
	}

	private void send(Lobby_Message message)
	{
		socket.send(service, Lobby_Codec.encode(message));
	}

	// ---------------------------------------------------------------- what the screen reads

	public State state()
	{
		return state;
	}

	/** The open lobbies, as last listed : null until the service answered a {@link #browse()}. */
	public Lobby_Message.Listing listing()
	{
		return listing;
	}

	/** The code being joined, or null. */
	public String code()
	{
		return code;
	}

	/** What the service answered the join : where the host is, and the relay the call uses. Null until then. */
	public Lobby_Message.Joined joined()
	{
		return joined;
	}

	/** Why the service said no to the join, or null. */
	public Lobby_Message.Refused refused()
	{
		return refused;
	}

	/** The call being made, or null before JOINED. */
	public Call call()
	{
		return call;
	}

	/** The host, once the channel is {@link State#OPEN} : where the game's session plays. Null before. */
	public Net_Peer peer()
	{
		return state == State.OPEN ? call.peer() : null;
	}

	/** Why the join ended, in words a player can read, or null. */
	public String failure()
	{
		return failure;
	}

	/** The lobby version the service speaks when it is not ours, or -1. */
	public int serviceOutdated()
	{
		return outdated;
	}

	/** The WebSocket to the service closed or went silent. */
	public boolean serviceLost()
	{
		return serviceLost;
	}

	/** Where the data channel is made : the transport the game's session plays on, the caller's to close. */
	public Offerer offerer()
	{
		return offerer;
	}

	/** Closes the WebSocket. The offerer and its channel are the caller's. */
	public void close()
	{
		socket.close();
	}
}
